package com.nishiyu.enderframelib.core.css;

import com.nishiyu.enderframelib.core.css.model.*;
import com.nishiyu.enderframelib.core.css.CssSyntax.ComponentValue;
import com.nishiyu.enderframelib.core.css.CssSyntax.Token;
import com.nishiyu.enderframelib.core.css.CssSyntax.Type;
import com.nishiyu.enderframelib.core.css.CssParser.CssTokenizer;
import com.nishiyu.enderframelib.core.css.Selector.AttrMatcher;
import com.nishiyu.enderframelib.core.css.Selector.DocumentPseudoClass;
import com.nishiyu.enderframelib.core.css.Selector.Nth;
import com.nishiyu.enderframelib.core.css.Selector.RelativeSelector;
import com.nishiyu.enderframelib.core.css.Selector.SelectorToken;
import com.nishiyu.enderframelib.core.css.StyleResolver.PseudoType;
import com.nishiyu.enderframelib.core.css.StyleSheet.CascadeOrigin;
import com.nishiyu.enderframelib.core.css.StyleSheet.Specificity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * セレクタ文字列を {@link Selector} に変換するパーサ。
 *
 * 以前は {@code sel.contains(":hover")} → {@code sel.replace(":hover", "")} という
 * 破壊的な文字列手術を疑似クラスごとに繰り返し、最後に残ったものをタグ名として扱っていた。
 * そのため {@code [href$=".png"]} のような属性セレクタが丸ごと壊れて
 * 「マッチしない」ではなく「全要素にマッチする」という危険な壊れ方をしていた。
 * ここでは文字単位に走査して、タグ・ID・クラス・属性・疑似クラス・疑似要素を個別に取り出す。
 *
 * ネスト（CSS Nesting）では、入れ子の規則のセレクタの {@code &} が親のセレクタリストを
 * {@code :is()} と同じ意味（詳細度は引数の最大）で指す。最上位の {@code &} は {@code :scope}。
 */
public final class SelectorParser {

    private static final Pattern NTH_AN_B = Pattern.compile("([+-]?\\d*)n(?:\\s*([+-])\\s*(\\d+))?");
    private static final Pattern NTH_B = Pattern.compile("[+-]?\\d+");

    private SelectorParser() {}

    public static Selector parse(String raw) {
        return parse(raw, CascadeOrigin.AUTHOR, false);
    }

    public static Selector parse(String raw, CascadeOrigin origin, boolean important) {
        return parse(raw, origin, important, null);
    }

    /**
     * 入れ子の規則のセレクタを読む。{@code &} を含まないセレクタは相対セレクタとして、先頭に
     * {@code & }（子孫結合子）が付いたものとして読む（{@code .b} は {@code & .b}、{@code > .b} は {@code & > .b}）。
     *
     * @param parents 親の規則のセレクタ（{@code &} の指す先）
     */
    public static Selector parseNested(String raw, CascadeOrigin origin, boolean important, List<Selector> parents) {
        String text = raw == null ? "" : CssSyntax.trimCssWhitespace(raw);
        if (!containsNestingSelector(text)) text = "& " + text;
        return parse(text, origin, important, parents);
    }

    private static boolean containsNestingSelector(String text) {
        for (Token t : CssSyntax.tokenize(text)) {
            if (t.isDelim('&')) return true;
        }
        return false;
    }

    /** @param parents {@code &} の指す先。null なら最上位（{@code &} は {@code :scope}） */
    public static Selector parse(String raw, CascadeOrigin origin, boolean important, List<Selector> parents) {
        String text = raw == null ? "" : CssSyntax.trimCssWhitespace(raw);

        List<SelectorToken> compounds = new ArrayList<>();
        List<Character> combinators = new ArrayList<>();
        int[] spec = new int[3]; // {id, class, type}

        PseudoElement pseudoElement = PseudoElement.NONE;

        List<String> parts = splitByCombinators(text, combinators);
        // 空のセレクタ、先頭・末尾の結合子、連続した結合子（"> a"・"a >"・"a > + b"）は無効
        boolean structureInvalid = parts.isEmpty() || combinators.size() != parts.size() - 1
                || combinators.contains(null);
        combinators.replaceAll(c -> c == null ? ' ' : c);
        while (!combinators.isEmpty() && combinators.size() >= parts.size()) combinators.removeLast();

        for (int i = 0; i < parts.size(); i++) {
            SelectorToken token = new SelectorToken();
            PseudoElement pe = parseCompoundInto(parts.get(i), token, spec, parents);
            if (pe != PseudoElement.NONE) pseudoElement = pe;
            // 擬似要素は最後の複合セレクタにしか書けない
            if (pe != PseudoElement.NONE && i != parts.size() - 1) token.invalid = true;
            compounds.add(token);
        }

        if (compounds.isEmpty()) {
            compounds.add(new SelectorToken());
        }
        if (structureInvalid) compounds.getFirst().invalid = true;

        boolean isHover = false;
        boolean isActive = false;
        boolean isFocus = false;
        int stateMask = 0;
        for (SelectorToken t : compounds) {
            // 結合子の有無に関わらず、ルール全体が依存する状態マスクを集約する
            isHover |= t.parsedHover;
            isActive |= t.parsedActive;
            isFocus |= t.parsedFocus;
            stateMask |= t.dependentStateMask();
        }

        PseudoType renderedPseudo = pseudoElement.type;

        // 疑似要素は構造上の照合対象から外すため、セレクタ本体のテキストからは取り除いた形を保持する
        String bodyText = text;
        if (renderedPseudo == PseudoType.BEFORE) bodyText = bodyText.replace("::before", "").replace(":before", "");
        if (renderedPseudo == PseudoType.AFTER) bodyText = bodyText.replace("::after", "").replace(":after", "");

        Specificity specificity = new Specificity(origin, important, spec[0], spec[1], spec[2]);
        return new Selector(CssSyntax.trimCssWhitespace(bodyText), compounds, combinators, renderedPseudo,
                isHover, isActive, isFocus, stateMask, specificity);
    }

    /** 後方互換用。複合セレクタ 1 つを既存のトークンに書き込む。 */
    public static void parseCompoundInto(String sel, SelectorToken out) {
        parseCompoundInto(sel, out, new int[3], null);
    }

    /** 構文上の擬似要素の種別。{@code type} が null のもの（{@code OTHER}）は描かない。 */
    private enum PseudoElement {
        NONE(null), BEFORE(PseudoType.BEFORE), AFTER(PseudoType.AFTER), MARKER(PseudoType.MARKER),
        PLACEHOLDER(PseudoType.PLACEHOLDER), SELECTION(PseudoType.SELECTION), OTHER(null);

        final PseudoType type;

        PseudoElement(PseudoType type) {
            this.type = type;
        }
    }

    /**
     * 複合セレクタ 1 つ（結合子を含まない）をパースして {@code out} に書き込み、
     * 詳細度の加算分を {@code spec} に足し込む。解釈できない部分があれば {@code out.invalid} を立てる。
     */
    private static PseudoElement parseCompoundInto(String sel, SelectorToken out, int[] spec, List<Selector> parents) {
        out.rawSelector = sel;
        PseudoElement pseudoElement = PseudoElement.NONE;

        int i = 0;
        int len = sel.length();
        boolean anySeen = false;

        while (i < len) {
            char c = sel.charAt(i);
            // 擬似要素の後に書けるのは擬似クラスだけ
            if (pseudoElement != PseudoElement.NONE && c != ':') out.invalid = true;

            if (c == '*') {
                // 型セレクタ（と *）は複合セレクタの先頭にしか書けない
                if (anySeen) out.invalid = true;
                out.tag = "*";
                i++;
            } else if (c == '&') {
                applyNesting(out, spec, parents);
                i++;
            } else if (c == '#') {
                // `#1` や `#-2` は id の名前にならない（ハッシュトークンが識別子で始まらない）
                int end = CssSyntax.startsIdentifier(sel, i + 1) ? readIdent(sel, i + 1) : i + 1;
                if (end > i + 1) {
                    out.id = unescape(sel.substring(i + 1, end));
                    spec[0]++;
                } else {
                    out.invalid = true;
                }
                i = Math.max(end, i + 1);
            } else if (c == '.') {
                int end = CssSyntax.startsIdentifier(sel, i + 1) ? readIdent(sel, i + 1) : i + 1;
                if (end > i + 1) {
                    out.classes.add(unescape(sel.substring(i + 1, end)));
                    spec[1]++;
                } else {
                    out.invalid = true;
                }
                i = Math.max(end, i + 1);
            } else if (c == '[') {
                int end = skipBalanced(sel, i, '[', ']');
                if (sel.charAt(end - 1) != ']' || !parseAttribute(sel.substring(i + 1, end - 1), out)) {
                    out.invalid = true;
                }
                spec[1]++;
                i = end;
            } else if (c == ':') {
                boolean doubleColon = i + 1 < len && sel.charAt(i + 1) == ':';
                int nameStart = i + (doubleColon ? 2 : 1);
                int nameEnd = readIdent(sel, nameStart);
                String name = sel.substring(nameStart, nameEnd).toLowerCase(Locale.ROOT);
                if (name.isEmpty()) out.invalid = true;

                String arg = null;
                int next = nameEnd;
                if (nameEnd < len && sel.charAt(nameEnd) == '(') {
                    next = skipBalanced(sel, nameEnd, '(', ')');
                    if (sel.charAt(next - 1) != ')') out.invalid = true;
                    arg = CssSyntax.trimCssWhitespace(sel.substring(nameEnd + 1, Math.max(nameEnd + 1, next - 1)));
                }

                PseudoElement pe = applyPseudo(name, arg, doubleColon, out, spec, parents);
                if (pe != PseudoElement.NONE) {
                    if (pseudoElement != PseudoElement.NONE) out.invalid = true;
                    pseudoElement = pe;
                }
                i = Math.max(next, i + 1);
            } else {
                int end = readIdent(sel, i);
                if (end > i && !anySeen) {
                    out.tag = unescape(sel.substring(i, end)).toLowerCase(Locale.ROOT);
                    spec[2]++;
                    i = end;
                } else {
                    // 解釈できない文字（名前空間の | など）や、途中に現れた型セレクタ
                    out.invalid = true;
                    i = Math.max(end, i + 1);
                }
            }
            anySeen = true;
        }
        return pseudoElement;
    }

    /**
     * {@code &}。入れ子の規則の中では親のセレクタリストを {@code :is()} と同じに扱う
     * （擬似要素を含む親は {@code &} が指す要素にならないので外す）。最上位では {@code :scope}（文書のルート）。
     */
    private static void applyNesting(SelectorToken out, int[] spec, List<Selector> parents) {
        if (parents == null) {
            out.isRoot = true;
            spec[1]++;
            return;
        }
        List<Selector> usable = new ArrayList<>();
        for (Selector parent : parents) {
            if (parent.pseudoElement() == null) usable.add(parent);
        }
        out.anyOfSelectors.add(usable);
        addMaxSpecificity(usable, spec);
    }

    /** 疑似クラス／疑似要素 1 つをトークンに反映する。 */
    private static PseudoElement applyPseudo(String name, String arg, boolean doubleColon,
                                             SelectorToken out, int[] spec, List<Selector> parents) {
        // ::before / :before（旧記法）はいずれも疑似要素として扱う
        if (name.equals("before") || name.equals("after")) {
            if (arg != null) out.invalid = true;
            spec[2]++;
            return name.equals("before") ? PseudoElement.BEFORE : PseudoElement.AFTER;
        }
        if (doubleColon || name.equals("first-line") || name.equals("first-letter")) {
            if (arg != null && !name.equals("highlight")) out.invalid = true;
            spec[2]++;
            switch (name) {
                case "marker" -> {
                    return PseudoElement.MARKER;
                }
                case "placeholder", "-webkit-input-placeholder", "-moz-placeholder" -> {
                    return PseudoElement.PLACEHOLDER;
                }
                case "selection" -> {
                    return PseudoElement.SELECTION;
                }
                default -> {
                    // 描かない擬似要素。詳細度は数えるが、要素そのものには一致させない
                    // （以前は ::placeholder や ::marker の宣言が要素自身に掛かっていた）
                    if (!isKnownUnrenderedPseudoElement(name)) out.invalid = true;
                    out.unrenderedPseudoElement = true;
                    return PseudoElement.OTHER;
                }
            }
        }
        if ((arg != null) != takesArgument(name)) {
            out.invalid = true;
            return PseudoElement.NONE;
        }

        switch (name) {
            case "hover" -> {
                out.parsedHover = true;
                out.stateFlags |= ElementState.HOVER;
            }
            case "active" -> {
                out.parsedActive = true;
                out.stateFlags |= ElementState.ACTIVE;
            }
            case "focus" -> {
                out.parsedFocus = true;
                out.stateFlags |= ElementState.FOCUS;
            }
            case "focus-visible" -> {
                out.parsedFocusVisible = true;
                out.stateFlags |= ElementState.FOCUS_VISIBLE;
            }
            case "focus-within" -> {
                out.parsedFocusWithin = true;
                out.stateFlags |= ElementState.FOCUS_WITHIN;
            }
            case "checked" -> {
                out.isChecked = true;
                out.stateFlags |= ElementState.CHECKED;
            }
            case "disabled" -> {
                out.isDisabled = true;
                out.stateFlags |= ElementState.DISABLED;
            }
            case "enabled" -> {
                out.isEnabled = true;
                out.absentStateFlags |= ElementState.DISABLED;
            }
            case "indeterminate" -> {
                out.isIndeterminate = true;
                out.stateFlags |= ElementState.INDETERMINATE;
            }
            case "open" -> {
                out.isOpen = true;
                out.stateFlags |= ElementState.OPEN;
            }
            case "required" -> out.isRequired = true;
            case "optional" -> out.isOptional = true;
            case "link", "any-link" -> out.isLink = true;
            // 履歴を持たないので、どのリンクも未訪問
            case "visited" -> out.isVisited = true;
            case "placeholder-shown" -> out.documentPseudoClasses.add(DocumentPseudoClass.PLACEHOLDER_SHOWN);
            case "read-only" -> out.documentPseudoClasses.add(DocumentPseudoClass.READ_ONLY);
            case "read-write" -> out.documentPseudoClasses.add(DocumentPseudoClass.READ_WRITE);
            case "valid" -> out.documentPseudoClasses.add(DocumentPseudoClass.VALID);
            case "invalid" -> out.documentPseudoClasses.add(DocumentPseudoClass.INVALID);
            case "in-range" -> out.documentPseudoClasses.add(DocumentPseudoClass.IN_RANGE);
            case "out-of-range" -> out.documentPseudoClasses.add(DocumentPseudoClass.OUT_OF_RANGE);
            case "defined" -> out.documentPseudoClasses.add(DocumentPseudoClass.DEFINED);
            case "target" -> out.documentPseudoClasses.add(DocumentPseudoClass.TARGET);
            case "first-child" -> out.nths.add(new Nth(0, 1, false, false));
            case "last-child" -> out.nths.add(new Nth(0, 1, false, true));
            case "only-child" -> {
                out.nths.add(new Nth(0, 1, false, false));
                out.nths.add(new Nth(0, 1, false, true));
            }
            case "first-of-type" -> out.nths.add(new Nth(0, 1, true, false));
            case "last-of-type" -> out.nths.add(new Nth(0, 1, true, true));
            case "only-of-type" -> {
                out.nths.add(new Nth(0, 1, true, false));
                out.nths.add(new Nth(0, 1, true, true));
            }
            case "nth-child", "nth-last-child", "nth-of-type", "nth-last-of-type" -> {
                Nth nth = parseNthArgument(arg, name, parents, spec);
                if (nth == null) out.invalid = true;
                else out.nths.add(nth);
            }
            case "empty" -> out.isEmpty = true;
            // スコープの根を作る仕組み（@scope・querySelector の起点）が無いので :scope は :root
            case "root", "scope" -> out.isRoot = true;
            case "lang" -> {
                List<String> ranges = parseLanguageRanges(arg);
                if (ranges == null) out.invalid = true;
                else out.langRanges.add(ranges);
            }
            case "dir" -> {
                String direction = CssSyntax.trimCssWhitespace(arg).toLowerCase(Locale.ROOT);
                if (direction.equals("ltr") || direction.equals("rtl")) out.directions.add(direction);
                else out.invalid = true;
            }
            case "has" -> {
                // :has() は寛容でないリスト。相対セレクタが 1 つでも無効なら :has() ごと無効。詳細度は引数の最大
                List<RelativeSelector> list = parseRelativeList(arg, parents);
                if (list == null) {
                    out.invalid = true;
                } else {
                    out.hasSelectors.add(list);
                    addMaxSpecificity(list.stream().map(RelativeSelector::selector).toList(), spec);
                }
                return PseudoElement.NONE;
            }
            case "not" -> {
                // :not() は寛容でないリスト。引数が 1 つでも無効なら :not() ごと無効。詳細度は引数の最大
                List<Selector> list = parseNestedList(arg, false, parents);
                if (list == null) {
                    out.invalid = true;
                } else {
                    out.notSelectors.addAll(list);
                    addMaxSpecificity(list, spec);
                }
                return PseudoElement.NONE;
            }
            case "is", "where" -> {
                // 寛容なリスト。無効な引数だけを捨てる（全部捨てたら何にも一致しない）
                List<Selector> list = parseNestedList(arg, true, parents);
                out.anyOfSelectors.add(list);
                // :is() は引数の最大の詳細度、:where() は常に 0
                if (name.equals("is")) addMaxSpecificity(list, spec);
                return PseudoElement.NONE;
            }
            default -> {
                // LIMITATION: 上に無い擬似クラス（:user-valid・:autofill・:fullscreen など）は
                // 無効なセレクタとして扱う（ブラウザが知らない擬似クラスを扱うのと同じ）。README の "Not supported"。
                out.invalid = true;
                return PseudoElement.NONE;
            }
        }
        spec[1]++;
        return PseudoElement.NONE;
    }

    private static boolean takesArgument(String name) {
        return switch (name) {
            case "not", "is", "where", "has", "lang", "dir",
                 "nth-child", "nth-last-child", "nth-of-type", "nth-last-of-type" -> true;
            default -> false;
        };
    }

    private static boolean isKnownUnrenderedPseudoElement(String name) {
        return switch (name) {
            case "first-line", "first-letter", "backdrop", "file-selector-button", "cue", "spelling-error",
                 "grammar-error", "target-text", "highlight" -> true;
            // ブラウザもベンダ接頭辞の擬似要素は知らなくても無効にしない
            default -> name.startsWith("-webkit-") || name.startsWith("-moz-");
        };
    }

    /**
     * {@code :not()} / {@code :is()} / {@code :where()} の引数のセレクタリスト。擬似要素は引数に書けない。
     * 寛容でない（{@code forgiving == false}）リストに無効な引数があるか、リストが空なら null。
     */
    private static List<Selector> parseNestedList(String arg, boolean forgiving, List<Selector> parents) {
        List<Selector> out = new ArrayList<>();
        for (String inner : CssTokenizer.splitSelectorList(arg == null ? "" : arg)) {
            Selector s = parse(inner, CascadeOrigin.AUTHOR, false, parents);
            boolean ok = s.isValid() && s.pseudoElement() == null
                    && s.compounds().stream().noneMatch(t -> t.unrenderedPseudoElement);
            if (!ok) {
                if (forgiving) continue;
                return null;
            }
            out.add(s);
        }
        if (!forgiving && out.isEmpty()) return null;
        return out;
    }

    /**
     * {@code :has()} の相対セレクタのリスト（{@code > img}・{@code + p}・{@code .a .b}）。
     * 先頭の結合子が無ければ子孫。擬似要素と入れ子の {@code :has()} は書けない。無効なものが 1 つでもあれば null。
     */
    private static List<RelativeSelector> parseRelativeList(String arg, List<Selector> parents) {
        List<RelativeSelector> out = new ArrayList<>();
        for (String item : CssTokenizer.splitSelectorList(arg == null ? "" : arg)) {
            String text = CssSyntax.trimCssWhitespace(item);
            char combinator = ' ';
            if (!text.isEmpty() && (text.charAt(0) == '>' || text.charAt(0) == '+' || text.charAt(0) == '~')) {
                combinator = text.charAt(0);
                text = CssSyntax.trimCssWhitespace(text.substring(1));
            }
            if (text.isEmpty()) return null;
            Selector s = parse(text, CascadeOrigin.AUTHOR, false, parents);
            boolean ok = s.isValid() && s.pseudoElement() == null
                    && s.compounds().stream().noneMatch(t -> t.unrenderedPseudoElement || !t.hasSelectors.isEmpty());
            if (!ok) return null;
            out.add(new RelativeSelector(combinator, s));
        }
        return out.isEmpty() ? null : out;
    }

    /** {@code :lang()} の言語範囲のリスト（小文字）。{@code en}・{@code "*-US"}・{@code de, fr}。空や無効なら null。 */
    private static List<String> parseLanguageRanges(String arg) {
        List<String> out = new ArrayList<>();
        for (List<ComponentValue> part : CssSyntax.split(CssSyntax.parseComponentValues(arg), Type.COMMA)) {
            if (part.size() != 1) return null;
            Token t = CssSyntax.tokenOf(part.getFirst());
            if (t == null || (t.type() != Type.IDENT && t.type() != Type.STRING) || t.value().isEmpty()) return null;
            out.add(t.value().toLowerCase(Locale.ROOT));
        }
        return out.isEmpty() ? null : out;
    }

    private static void addMaxSpecificity(List<Selector> list, int[] spec) {
        Specificity max = null;
        for (Selector s : list) {
            if (max == null || s.specificity().compareTo(max) > 0) max = s.specificity();
        }
        if (max == null) return;
        spec[0] += max.id();
        spec[1] += max.clazz();
        spec[2] += max.type();
    }

    /**
     * {@code :nth-*()} の引数。{@code An+B}、{@code :nth-child} / {@code :nth-last-child} では
     * 後ろに {@code of S}（数える兄弟をセレクタに合うものだけにする）を書ける。{@code S} の詳細度は加算される。
     */
    private static Nth parseNthArgument(String arg, String name, List<Selector> parents, int[] spec) {
        boolean ofType = name.endsWith("of-type");
        boolean fromEnd = name.startsWith("nth-last");
        if (arg == null) return null;
        String an = arg;
        List<Selector> filter = List.of();
        int of = indexOfWord(arg, "of");
        if (of >= 0) {
            if (ofType) return null;
            an = arg.substring(0, of);
            filter = parseNestedList(arg.substring(of + 2), false, parents);
            if (filter == null) return null;
            addMaxSpecificity(filter, spec);
        }
        Nth nth = parseNth(an, ofType, fromEnd);
        return nth == null ? null : new Nth(nth.a(), nth.b(), ofType, fromEnd, filter);
    }

    /** 空白で区切られた単語 {@code word}（{@code of}）の位置。見つからなければ -1。 */
    private static int indexOfWord(String text, String word) {
        String lower = text.toLowerCase(Locale.ROOT);
        int from = 0;
        while (true) {
            int at = lower.indexOf(word, from);
            if (at < 0) return -1;
            boolean before = at > 0 && CssSyntax.isCssWhitespace(lower.charAt(at - 1));
            boolean after = at + word.length() < lower.length() && CssSyntax.isCssWhitespace(lower.charAt(at + word.length()));
            if (before && after) return at;
            from = at + 1;
        }
    }

    /**
     * {@code An+B}（CSS Syntax 3 §6）。{@code odd} / {@code even} / {@code 3n} / {@code -n+3} / {@code 4n + 1} / {@code 5}。
     * 解釈できなければ null。
     *
     * 何が壊れていたか: 以前は odd / even / 2n / 2n+1 / 整数しか読めず、それ以外を「指定なし」にしていたので
     * {@code :nth-child(3n)} が全要素に一致していた。
     */
    static Nth parseNth(String arg, boolean ofType, boolean fromEnd) {
        if (arg == null) return null;
        String a = CssSyntax.trimCssWhitespace(arg).toLowerCase(Locale.ROOT);
        if (a.equals("odd")) return new Nth(2, 1, ofType, fromEnd);
        if (a.equals("even")) return new Nth(2, 0, ofType, fromEnd);
        try {
            if (NTH_B.matcher(a).matches()) {
                return new Nth(0, Integer.parseInt(a), ofType, fromEnd);
            }
            Matcher m = NTH_AN_B.matcher(a);
            if (!m.matches()) return null;
            int step = switch (m.group(1)) {
                case "", "+" -> 1;
                case "-" -> -1;
                default -> Integer.parseInt(m.group(1));
            };
            int offset = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
            if ("-".equals(m.group(2))) offset = -offset;
            return new Nth(step, offset, ofType, fromEnd);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 角括弧の中身（括弧自体は含まない）を属性条件としてトークンに反映する。解釈できなければ false。 */
    private static boolean parseAttribute(String inner, SelectorToken out) {
        String body = CssSyntax.trimCssWhitespace(inner);
        if (body.isEmpty()) return false;

        int eq = body.indexOf('=');
        if (eq < 0) {
            if (readIdent(body, 0) != body.length()) return false;
            out.attrMatchers.add(new AttrMatcher(unescape(body).toLowerCase(Locale.ROOT), (char) 0, "", false));
            return true;
        }

        char opChar = eq > 0 ? body.charAt(eq - 1) : '=';
        boolean hasOpPrefix = opChar == '^' || opChar == '$' || opChar == '*' || opChar == '~' || opChar == '|';
        String name = CssSyntax.trimCssWhitespace(body.substring(0, hasOpPrefix ? eq - 1 : eq));
        if (name.isEmpty() || readIdent(name, 0) != name.length()) return false;

        String rest = CssSyntax.trimCssWhitespace(body.substring(eq + 1));
        boolean ignoreCase = false;
        // 大文字小文字の修飾子 i / s（Selectors 4 §6.3）
        if (rest.length() >= 3 && CssSyntax.isCssWhitespace(rest.charAt(rest.length() - 2))) {
            char flag = Character.toLowerCase(rest.charAt(rest.length() - 1));
            if (flag == 'i' || flag == 's') {
                ignoreCase = flag == 'i';
                rest = CssSyntax.trimCssWhitespace(rest.substring(0, rest.length() - 2));
            }
        }
        String value;
        if (!rest.isEmpty() && (rest.charAt(0) == '"' || rest.charAt(0) == '\'')) {
            if (rest.length() < 2 || rest.charAt(rest.length() - 1) != rest.charAt(0)) return false;
            value = rest.substring(1, rest.length() - 1);
        } else {
            if (rest.isEmpty() || readIdent(rest, 0) != rest.length()) return false;
            value = unescape(rest);
        }
        out.attrMatchers.add(new AttrMatcher(unescape(name).toLowerCase(Locale.ROOT),
                hasOpPrefix ? opChar : '=', value, ignoreCase));
        return true;
    }

    /**
     * セレクタを結合子で分割する。
     * 角括弧・丸括弧の中の空白や記号、エスケープされた文字では分割しない。
     * 結合子は compounds の隣接ペアに対応する順で {@code combinatorsOut} に積まれる。
     * 先頭・末尾の結合子や連続した結合子は null を積むか数を合わせないことで呼び出し側に知らせる。
     */
    private static List<String> splitByCombinators(String sel, List<Character> combinatorsOut) {
        List<String> parts = new ArrayList<>();
        int i = 0;
        int len = sel.length();
        Character pending = null;

        while (i < len) {
            while (i < len && CssSyntax.isCssWhitespace(sel.charAt(i))) i++;
            if (i >= len) break;

            char c = sel.charAt(i);
            if (c == '>' || c == '+' || c == '~') {
                if (pending != null || parts.isEmpty()) combinatorsOut.add(null);
                pending = c;
                i++;
                continue;
            }

            int start = i;
            while (i < len) {
                char d = sel.charAt(i);
                if (CssSyntax.isCssWhitespace(d) || d == '>' || d == '+' || d == '~') break;
                if (d == '\\') {
                    i = skipEscape(sel, i);
                    continue;
                }
                if (d == '[') {
                    i = skipBalanced(sel, i, '[', ']');
                    continue;
                }
                if (d == '(') {
                    i = skipBalanced(sel, i, '(', ')');
                    continue;
                }
                i++;
            }

            String part = sel.substring(start, i);
            if (part.isEmpty()) continue;
            if (!parts.isEmpty()) combinatorsOut.add(pending != null ? pending : ' ');
            parts.add(part);
            pending = null;
        }
        if (pending != null) combinatorsOut.add(null);
        return parts;
    }

    /** 識別子（タグ名・クラス名・属性名などに使える文字とエスケープ）の終端位置。 */
    private static int readIdent(String s, int start) {
        int i = start;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\') {
                i = skipEscape(s, i);
            } else if (Character.isLetterOrDigit(c) || c == '-' || c == '_' || c > 127) {
                i++;
            } else {
                break;
            }
        }
        return i;
    }

    /** {@code i} 位置のバックスラッシュから始まるエスケープ（1〜6 桁の 16 進と続く空白 1 つ、または任意の 1 文字）の次。 */
    private static int skipEscape(String s, int i) {
        int j = i + 1;
        if (j >= s.length()) return j;
        if (Character.digit(s.charAt(j), 16) < 0) return j + 1;
        int end = Math.min(s.length(), j + 6);
        while (j < end && Character.digit(s.charAt(j), 16) >= 0) j++;
        if (j < s.length() && CssSyntax.isCssWhitespace(s.charAt(j))) j++;
        return j;
    }

    /**
     * 識別子のエスケープを解く（{@code .hover\:bg} → {@code hover:bg}、{@code #\31 23} → {@code 123}）。
     *
     * 何が壊れていたか: 以前はバックスラッシュで識別子が切れて残りを読み飛ばしたので、
     * {@code .sm\:hidden} が {@code .sm} として照合されていた。
     */
    private static String unescape(String s) {
        if (s.indexOf('\\') < 0) return s;
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                sb.append(c);
                i++;
                continue;
            }
            int next = skipEscape(s, i);
            String body = CssSyntax.trimCssWhitespace(s.substring(i + 1, next));
            if (!body.isEmpty() && Character.digit(body.charAt(0), 16) >= 0) {
                int cp = Integer.parseInt(body, 16);
                sb.appendCodePoint(cp == 0 || cp > Character.MAX_CODE_POINT ? 0xFFFD : cp);
            } else {
                sb.append(s, i + 1, next);
            }
            i = next;
        }
        return sb.toString();
    }

    /**
     * {@code start} 位置の {@code open} に対応する {@code close} の「次の」位置を返す。
     * 引用符の中は数えない。閉じが無ければ文字列長。
     */
    private static int skipBalanced(String s, int start, char open, char close) {
        int depth = 0;
        int i = start;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '"' || c == '\'') {
                i = skipQuoted(s, i);
                continue;
            }
            if (c == open) depth++;
            else if (c == close) {
                depth--;
                if (depth == 0) return i + 1;
            }
            i++;
        }
        return s.length();
    }

    private static int skipQuoted(String s, int i) {
        char quote = s.charAt(i);
        int j = i + 1;
        while (j < s.length()) {
            char c = s.charAt(j);
            if (c == '\\') {
                j += 2;
                continue;
            }
            if (c == quote) return j + 1;
            j++;
        }
        return s.length();
    }
}
