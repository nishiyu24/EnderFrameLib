package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.CssProperty;
import com.nishiyu.enderframelib.core.css.PropertyRegistry;
import com.nishiyu.enderframelib.core.dom.HtmlNode;
import com.nishiyu.enderframelib.core.dom.HtmlNode.NodeType;
import com.nishiyu.enderframelib.core.layout.LayoutBox.Display.Inner;
import com.nishiyu.enderframelib.core.text.TextShaper;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 要素ツリー（DOM と 1:1 の {@link LayoutBox}）からボックスツリーを組み立てる（軸 6）。
 *
 * レイアウトの入力は DOM ではなくこのツリー。DOM に無い箱をここで作る:
 * <ul>
 *   <li>{@code display: contents} の要素は箱を作らず、子が親の子に繰り上がる</li>
 *   <li>ブロックコンテナの中でブロックとインラインが混ざると、インラインの並びを匿名ブロックで包む
 *       （CSS 2.2 §9.2.1.1）</li>
 *   <li>表の構造が欠けていると匿名の table / table-row / table-cell を補う（CSS 2.2 §17.2.1）</li>
 *   <li>{@code list-item} に {@code ::marker} を作り、CSS カウンタ（{@code counter-reset} /
 *       {@code counter-increment} / {@code counter-set} と暗黙の {@code list-item}）を数える</li>
 *   <li>{@code ::before} / {@code ::after} の {@code content} を、カウンタを含めてここで評価する</li>
 * </ul>
 *
 * 要素ツリー（{@link LayoutBox#elementChildren}）は変えず、{@link LayoutBox#children} を作り直す。
 * 再スタイルは要素ツリーを辿るので、箱の組み替えはスタイルの継承に影響しない。
 *
 * LIMITATION(制約): インラインボックスの中にブロックがあるとき、インラインを分割しない
 * （ブロックはインラインの中に置かれたまま組まれる）。flex / grid コンテナの中のテキストは
 * 匿名の flex / grid アイテムで包まず、テキストの箱がそのままアイテムになる。
 * {@code <ol reversed>} は逆順に数えない。README の "Not supported" に対応。
 */
public final class BoxTreeBuilder {

    private final Counters counters = new Counters();
    /** 匿名ボックスの id の通し番号（持ち主ごと）。再レイアウトしても同じ id になるよう決定的に振る。 */
    private final Map<LayoutBox, Integer> anonymousCounts = new IdentityHashMap<>();

    private BoxTreeBuilder() {}

    /** {@code root} 以下のボックスツリーを要素ツリーから作り直す。 */
    public static void build(LayoutBox root) {
        BoxTreeBuilder builder = new BoxTreeBuilder();
        builder.counters.apply(root);
        builder.buildBox(root);
        root.linkParents();
    }

    private void buildBox(LayoutBox box) {
        List<LayoutBox> items = new ArrayList<>();
        LayoutBox marker = box.display.listItem() ? createMarker(box, counters) : null;
        if (marker != null) items.add(marker);
        collectItems(box, items);
        box.children.clear();
        for (LayoutBox child : fixup(box, items)) box.addChild(child);
    }

    /**
     * 箱の子になる箱を文書順に集める。{@code display: contents} の子はその子を代わりに入れる。
     * カウンタの適用もこの順で行う（CSS のカウンタは要素ツリーの文書順で数える）。
     */
    private void collectItems(LayoutBox element, List<LayoutBox> out) {
        int mark = counters.mark();
        for (LayoutBox child : element.elementChildren) {
            counters.apply(child);
            if (child.node.isPseudoNode) evaluateGeneratedContent(child, counters);
            if (child.display.isContents()) {
                collectItems(child, out);
            } else {
                buildBox(child);
                out.add(child);
            }
        }
        // counter-reset の有効範囲は要素自身・子孫・後続の兄弟とその子孫。親の子を数え終えたら閉じる
        counters.release(mark);
    }

    // ==========================================
    // 匿名ボックス
    // ==========================================

    private List<LayoutBox> fixup(LayoutBox parent, List<LayoutBox> items) {
        List<LayoutBox> result = fixupTable(parent, items);
        if (parent.display.isBlockContainer()) result = wrapInlineRuns(parent, result);
        return result;
    }

    /** 表の構造の補完（CSS 2.2 §17.2.1 の「欠けた子」と「欠けた親」）。 */
    private List<LayoutBox> fixupTable(LayoutBox parent, List<LayoutBox> items) {
        Inner inner = parent.display.inner();
        if (inner == Inner.TABLE) {
            return wrapRuns(parent, dropWhitespace(items), c -> !c.display.isNone() && !isProperTableChild(c), "table-row");
        }
        if (inner == Inner.TABLE_ROW_GROUP) {
            return wrapRuns(parent, dropWhitespace(items), c -> !c.display.isNone() && c.display.inner() != Inner.TABLE_ROW, "table-row");
        }
        if (inner == Inner.TABLE_ROW) {
            return wrapRuns(parent, dropWhitespace(items), c -> !c.display.isNone() && c.display.inner() != Inner.TABLE_CELL, "table-cell");
        }
        if (items.stream().noneMatch(c -> c.display.isTableInternal())) return items;

        // 行の外にあるセルの並びは匿名の行で、表の外にある行・行グループの並びは匿名の表で包む
        List<LayoutBox> withRows = wrapRuns(parent, items, c -> c.display.inner() == Inner.TABLE_CELL, "table-row");
        String tableDisplay = parent.display.isInlineLevel() ? "inline-table" : "table";
        return wrapRuns(parent, withRows, this::isProperTableChild, tableDisplay);
    }

    private boolean isProperTableChild(LayoutBox box) {
        Inner inner = box.display.inner();
        return inner == Inner.TABLE_ROW_GROUP || inner == Inner.TABLE_ROW
                || inner == Inner.TABLE_CAPTION || inner == Inner.TABLE_COLUMN;
    }

    /**
     * 条件を満たす子の連続を 1 つの匿名ボックスで包む。並びの間にある空白だけのテキストは
     * 表の箱どうしの間では存在しないものとして扱う（CSS 2.2 §17.2.1 の 1.3）。
     */
    private List<LayoutBox> wrapRuns(LayoutBox parent, List<LayoutBox> items,
                                            Predicate<LayoutBox> belongsToRun, String anonymousDisplay) {
        List<LayoutBox> result = new ArrayList<>();
        List<LayoutBox> run = new ArrayList<>();
        List<LayoutBox> pendingWhitespace = new ArrayList<>();
        for (LayoutBox item : items) {
            if (belongsToRun.test(item)) {
                pendingWhitespace.clear();
                run.add(item);
            } else if (!run.isEmpty() && isCollapsibleWhitespace(item)) {
                pendingWhitespace.add(item);
            } else {
                flushRun(parent, run, anonymousDisplay, result);
                result.addAll(pendingWhitespace);
                pendingWhitespace.clear();
                result.add(item);
            }
        }
        flushRun(parent, run, anonymousDisplay, result);
        return result;
    }

    private void flushRun(LayoutBox parent, List<LayoutBox> run, String anonymousDisplay, List<LayoutBox> out) {
        if (run.isEmpty()) return;
        LayoutBox anon = createAnonymous(parent, anonymousDisplay);
        for (LayoutBox child : fixup(anon, run)) anon.addChild(child);
        out.add(anon);
        run.clear();
    }

    private static List<LayoutBox> dropWhitespace(List<LayoutBox> items) {
        List<LayoutBox> kept = new ArrayList<>(items.size());
        for (LayoutBox item : items) {
            if (!isCollapsibleWhitespace(item)) kept.add(item);
        }
        return kept;
    }

    /**
     * ブロックコンテナがブロックレベルの子を持つなら、インラインレベルの子の並びを匿名ブロックで包む。
     *
     * 何が壊れていたか: 以前はインラインの並びとブロックを同じ親の中で直接組み、インラインの行の後の
     * ブロックが「親の先頭の子」として親の上端に置かれていた（インラインと重なっていた）。
     */
    private List<LayoutBox> wrapInlineRuns(LayoutBox parent, List<LayoutBox> items) {
        if (items.stream().noneMatch(BoxTreeBuilder::isInFlowBlockLevel)) return items;

        List<LayoutBox> result = new ArrayList<>();
        List<LayoutBox> run = new ArrayList<>();
        for (LayoutBox item : items) {
            if (isInFlowBlockLevel(item)) {
                flushInlineRun(parent, run, result);
                result.add(item);
            } else if (isOutOfFlow(item) && run.isEmpty()) {
                // 並びの外にある位置指定・非表示・外側マーカーは包まない（並びの中なら一緒に包む）
                result.add(item);
            } else {
                run.add(item);
            }
        }
        flushInlineRun(parent, run, result);
        return result;
    }

    private void flushInlineRun(LayoutBox parent, List<LayoutBox> run, List<LayoutBox> out) {
        if (run.isEmpty()) return;
        boolean hasContent = run.stream().anyMatch(c -> !isOutOfFlow(c) && !isCollapsibleWhitespace(c));
        if (hasContent) {
            LayoutBox anon = createAnonymous(parent, "block");
            for (LayoutBox child : run) anon.addChild(child);
            out.add(anon);
        } else {
            // 畳まれる空白だけの並びは行ボックスを作らない（CSS 2.2 §9.2.1.1）
            for (LayoutBox child : run) {
                if (!isCollapsibleWhitespace(child)) out.add(child);
            }
        }
        run.clear();
    }

    private static boolean isInFlowBlockLevel(LayoutBox box) {
        return !isOutOfFlow(box) && !box.display.isInlineLevel();
    }

    /**
     * 匿名ブロックを作るかどうかの判断に加わらない箱。
     * float は通常フローの外なので、ブロックの並びに数えない（行の中の float は行と一緒に包む）。
     */
    private static boolean isOutOfFlow(LayoutBox box) {
        return box.display.isNone() || box.isAbsolute || box.isOutsideMarker
                || !"none".equals(box.style.getOrDefault(CssProperty.FLOAT, "none"));
    }

    private static boolean isCollapsibleWhitespace(LayoutBox box) {
        if (box.node.nodeType != NodeType.TEXT) return false;
        if (!TextShaper.WhiteSpace.of(box.style.get(CssProperty.WHITE_SPACE)).collapses()) return false;
        return box.node.text == null || box.node.text.isBlank();
    }

    /** 匿名ボックスは親の継承プロパティだけを受け継ぎ、それ以外は初期値になる（CSS 2.2 §9.2.1.1）。 */
    private LayoutBox createAnonymous(LayoutBox parent, String display) {
        int index = anonymousCounts.merge(parent, 1, Integer::sum);
        HtmlNode node = new HtmlNode("div");
        node.parent = parent.node;
        node.setId(parent.node.getId() + "_anon" + index);
        return LayoutBox.anonymous(node, generatedStyle(parent, Map.of(CssProperty.DISPLAY, display)));
    }

    /**
     * 持ち主の算出スタイルが変わったあと、その中の匿名ボックスとマーカーのスタイルを作り直す。
     *
     * 何が壊れていたか（W3 で入れた落とし穴）: 色など描画だけの変化は再レイアウトを通らず、
     * ボックスツリーも組み直されないので、{@code li:hover { color }} でマーカーの色が変わらなかった。
     * 要素の箱は再スタイルが要素ツリーを辿って直すが、DOM に無い箱はここで直す。
     */
    public static void restyleGeneratedBoxes(LayoutBox owner) {
        for (LayoutBox child : owner.children) {
            if (!child.isAnonymous) continue;
            Map<String, String> overrides = child.node.isPseudoNode
                    ? MARKER_OVERRIDES
                    : Map.of(CssProperty.DISPLAY, child.style.getOrDefault(CssProperty.DISPLAY, "block"));
            child.updateStyle(generatedStyle(owner, overrides));
            if (child.node.isPseudoNode) {
                for (LayoutBox text : child.children) text.updateStyle(child.style);
            } else {
                restyleGeneratedBoxes(child);
            }
        }
    }

    private static ComputedStyle generatedStyle(LayoutBox owner, Map<String, String> overrides) {
        Map<String, String> props = inheritedProperties(owner.style);
        props.putAll(overrides);
        return new ComputedStyle(props, owner.style.fontSizePx(), owner.style.rootFontSizePx());
    }

    private static Map<String, String> inheritedProperties(ComputedStyle style) {
        Map<String, String> props = new LinkedHashMap<>();
        style.asMap().forEach((key, value) -> {
            if (key.startsWith("--") || PropertyRegistry.isInherited(key)) props.put(key, value);
        });
        return props;
    }

    // ==========================================
    // ::marker と生成内容
    // ==========================================

    /**
     * リスト項目の {@code ::marker}。{@code list-style-position: outside} なら通常フローの外に置かれ、
     * {@link BlockLayoutEngine} が最初の行の左に並べる。{@code inside} なら先頭のインライン内容になる。
     *
     * LIMITATION(制約): {@code ::marker} のセレクタは照合せず、マーカーはリスト項目の継承値で描く。
     * {@code list-style-image} は読まない。README の "Not supported" に対応。
     */
    private static LayoutBox createMarker(LayoutBox listItem, Counters counters) {
        String text = markerText(listItem.style.getOrDefault(CssProperty.LIST_STYLE_TYPE, "disc"),
                counters.value(LIST_ITEM));
        if (text == null || text.isEmpty()) return null;

        ComputedStyle style = generatedStyle(listItem, MARKER_OVERRIDES);

        HtmlNode node = new HtmlNode("span");
        node.isPseudoNode = true;
        node.parent = listItem.node;
        node.setId(listItem.node.getId() + "_marker");
        HtmlNode textNode = HtmlNode.createTextNode(text);
        textNode.parent = node;
        textNode.setId(node.getId() + "_txt");

        // DOM に無い箱なので匿名ボックスとして持つ（描画は中のテキストだけ）
        LayoutBox marker = LayoutBox.anonymous(node, style);
        marker.isOutsideMarker = !"inside".equals(listItem.style.getOrDefault(CssProperty.LIST_STYLE_POSITION, "outside"));
        marker.addChild(new LayoutBox(textNode, style));
        return marker;
    }

    /** UA の ::marker と同じ（空白を畳まず、text-transform を掛けない）。 */
    private static final Map<String, String> MARKER_OVERRIDES = Map.of(
            CssProperty.DISPLAY, "inline-block",
            CssProperty.WHITE_SPACE, "pre",
            CssProperty.TEXT_TRANSFORM, "none");

    /** {@code list-style-type} からマーカーの文字列を作る（記号は後ろに空白、序数は「. 」を付ける）。 */
    static String markerText(String listStyleType, int ordinal) {
        String type = listStyleType == null ? "disc" : listStyleType.trim();
        if (type.isEmpty() || type.equals("none")) return null;
        if (type.length() >= 2 && (type.startsWith("\"") || type.startsWith("'"))) {
            return type.substring(1, type.length() - 1);
        }
        String symbol = symbolMarker(type);
        if (symbol != null) return symbol + " ";
        return formatCounter(ordinal, type) + ". ";
    }

    private static String symbolMarker(String type) {
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "disc" -> "•";
            case "circle" -> "◦";
            case "square" -> "▪";
            case "disclosure-open" -> "▾";
            case "disclosure-closed" -> "▸";
            default -> null;
        };
    }

    /** カウンタの値を counter style で書く。知らない名前は {@code decimal}（CSS Counter Styles 3）。 */
    static String formatCounter(int value, String style) {
        String type = style == null ? "decimal" : style.trim().toLowerCase(Locale.ROOT);
        String symbol = symbolMarker(type);
        if (symbol != null) return symbol;
        return switch (type) {
            case "none" -> "";
            case "decimal-leading-zero" -> (value >= 0 && value < 10 ? "0" : "") + value;
            case "lower-alpha", "lower-latin" -> alphabetic(value, 'a', 26);
            case "upper-alpha", "upper-latin" -> alphabetic(value, 'A', 26);
            case "lower-greek" -> alphabetic(value, 'α', 24);
            case "lower-roman" -> roman(value).toLowerCase(Locale.ROOT);
            case "upper-roman" -> roman(value);
            default -> Integer.toString(value);
        };
    }

    /** a, b, …, z, aa, ab, …（0 以下は表せないので decimal に落とす）。 */
    private static String alphabetic(int value, char first, int radix) {
        if (value <= 0) return Integer.toString(value);
        StringBuilder sb = new StringBuilder();
        int n = value;
        while (n > 0) {
            n--;
            sb.insert(0, (char) (first + n % radix));
            n /= radix;
        }
        return sb.toString();
    }

    /** ローマ数字は 1〜3999 だけ表せる。範囲外は decimal に落とす。 */
    private static String roman(int value) {
        if (value <= 0 || value >= 4000) return Integer.toString(value);
        int[] values = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        String[] numerals = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
        StringBuilder sb = new StringBuilder();
        int n = value;
        for (int i = 0; i < values.length; i++) {
            while (n >= values[i]) {
                sb.append(numerals[i]);
                n -= values[i];
            }
        }
        return sb.toString();
    }

    /**
     * {@code ::before} / {@code ::after} の {@code content} を評価して、擬似要素のテキストにする。
     *
     * 何が壊れていたか: 以前はパーサが {@code content} の前後の引用符を外すだけだったので、
     * {@code "(" attr(title) ")"} のような連結が文字列のまま出ていた。カウンタも読めなかった。
     */
    private static void evaluateGeneratedContent(LayoutBox pseudo, Counters counters) {
        String content = pseudo.style.get(CssProperty.CONTENT);
        if (content == null || pseudo.elementChildren.isEmpty()) return;
        LayoutBox textBox = pseudo.elementChildren.getFirst();
        if (textBox.node.nodeType != NodeType.TEXT) return;
        textBox.node.text = evaluateContent(content, pseudo.node.parent, counters);
        textBox.updateStyle(textBox.style);
    }

    /** {@code content} の値（文字列・{@code attr()}・{@code counter()}・{@code counters()} の並び）を評価する。 */
    static String evaluateContent(String content, HtmlNode owner, Counters counters) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        int n = content.length();
        while (i < n) {
            char c = content.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '"' || c == '\'') {
                int end = i + 1;
                StringBuilder s = new StringBuilder();
                while (end < n && content.charAt(end) != c) {
                    char ch = content.charAt(end);
                    if (ch == '\\' && end + 1 < n) {
                        end++;
                        ch = content.charAt(end);
                    }
                    s.append(ch);
                    end++;
                }
                out.append(s);
                i = Math.min(n, end + 1);
            } else {
                int open = content.indexOf('(', i);
                int space = indexOfWhitespace(content, i);
                if (open >= 0 && (space < 0 || open < space)) {
                    int close = matchingParen(content, open);
                    String name = content.substring(i, open).trim().toLowerCase(Locale.ROOT);
                    String args = content.substring(open + 1, close).trim();
                    out.append(evaluateFunction(name, args, owner, counters));
                    i = close + 1;
                } else {
                    // open-quote などのキーワード。引用符の入れ子は持たないので何も出さない
                    i = space < 0 ? n : space;
                }
            }
        }
        return out.toString();
    }

    private static String evaluateFunction(String name, String args, HtmlNode owner, Counters counters) {
        List<String> parts = splitArgs(args);
        return switch (name) {
            case "attr" -> owner != null && !parts.isEmpty() ? owner.attrs.getOrDefault(parts.getFirst(), "") : "";
            case "counter" -> parts.isEmpty() ? ""
                    : formatCounter(counters.value(parts.get(0)), parts.size() > 1 ? parts.get(1) : "decimal");
            case "counters" -> {
                if (parts.size() < 2) yield "";
                String separator = unquote(parts.get(1));
                String style = parts.size() > 2 ? parts.get(2) : "decimal";
                List<String> formatted = new ArrayList<>();
                for (int v : counters.values(parts.get(0))) formatted.add(formatCounter(v, style));
                if (formatted.isEmpty()) formatted.add(formatCounter(0, style));
                yield String.join(separator, formatted);
            }
            default -> "";
        };
    }

    private static List<String> splitArgs(String args) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < args.length(); i++) {
            char c = args.charAt(i);
            if (quote != 0) {
                current.append(c);
                if (c == quote) quote = 0;
            } else if (c == '"' || c == '\'') {
                quote = c;
                current.append(c);
            } else if (c == ',') {
                parts.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (!current.isEmpty()) parts.add(current.toString().trim());
        return parts;
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && (s.startsWith("\"") || s.startsWith("'"))) return s.substring(1, s.length() - 1);
        return s;
    }

    private static int indexOfWhitespace(String s, int from) {
        for (int i = from; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) return i;
        }
        return -1;
    }

    private static int matchingParen(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return i;
        }
        return s.length() - 1;
    }

    // ==========================================
    // CSS カウンタ
    // ==========================================

    /** リスト項目が暗黙に数えるカウンタ（CSS Lists 3）。 */
    static final String LIST_ITEM = "list-item";

    /**
     * 文書順に辿りながら持つカウンタの実体の積み上げ。
     * 同名の実体は後から作られたもの（内側）が見える。{@code counters()} は外側から全部を読む。
     */
    static final class Counters {

        private final List<String> names = new ArrayList<>();
        private final List<Integer> values = new ArrayList<>();

        int mark() {
            return names.size();
        }

        void release(int mark) {
            while (names.size() > mark) {
                names.removeLast();
                values.removeLast();
            }
        }

        int value(String name) {
            int i = innermost(name);
            return i < 0 ? 0 : values.get(i);
        }

        List<Integer> values(String name) {
            List<Integer> result = new ArrayList<>();
            for (int i = 0; i < names.size(); i++) {
                if (names.get(i).equals(name)) result.add(values.get(i));
            }
            return result;
        }

        /**
         * 要素の {@code counter-reset} → {@code counter-increment} → {@code counter-set} を適用する
         * （CSS Lists 3 の順。{@code <li value>} が増分の後に効くのはこの順のため）。
         */
        void apply(LayoutBox box) {
            if (box.node.nodeType == NodeType.TEXT) return;
            ComputedStyle style = box.style;
            HtmlNode node = box.node;

            Map<String, Integer> resets = parseCounterList(style.get(CssProperty.COUNTER_RESET), 0);
            // <ol start> は list-item の初期値を変える表示上のヒント（HTML の Rendering 節）
            if (resets.containsKey(LIST_ITEM) && "ol".equalsIgnoreCase(node.tag) && node.attrs.containsKey("start")) {
                Integer start = parseInt(node.attrs.get("start"));
                if (start != null) resets.put(LIST_ITEM, start - 1);
            }
            resets.forEach(this::reset);

            Map<String, Integer> increments = parseCounterList(style.get(CssProperty.COUNTER_INCREMENT), 1);
            if (box.display.listItem() && !increments.containsKey(LIST_ITEM)) increments.put(LIST_ITEM, 1);
            increments.forEach(this::increment);

            Map<String, Integer> sets = parseCounterList(style.get(CssProperty.COUNTER_SET), 0);
            if (box.display.listItem() && !sets.containsKey(LIST_ITEM) && node.attrs.containsKey("value")) {
                Integer value = parseInt(node.attrs.get("value"));
                if (value != null) sets.put(LIST_ITEM, value);
            }
            sets.forEach(this::set);
        }

        private void reset(String name, int value) {
            names.add(name);
            values.add(value);
        }

        private void increment(String name, int by) {
            int i = innermost(name);
            if (i < 0) {
                reset(name, by);
            } else {
                values.set(i, values.get(i) + by);
            }
        }

        private void set(String name, int value) {
            int i = innermost(name);
            if (i < 0) reset(name, value);
            else values.set(i, value);
        }

        private int innermost(String name) {
            for (int i = names.size() - 1; i >= 0; i--) {
                if (names.get(i).equals(name)) return i;
            }
            return -1;
        }

        /** {@code "a 2 b"} → {@code {a=2, b=既定値}}。{@code none} と空は空の表。 */
        private static Map<String, Integer> parseCounterList(String value, int defaultValue) {
            Map<String, Integer> result = new LinkedHashMap<>();
            if (value == null) return result;
            String v = value.trim();
            if (v.isEmpty() || v.equalsIgnoreCase("none")) return result;
            String pending = null;
            for (String token : v.split("\\s+")) {
                Integer number = parseInt(token);
                if (number != null && pending != null) {
                    result.put(pending, number);
                    pending = null;
                } else if (number == null) {
                    if (pending != null) result.put(pending, defaultValue);
                    pending = token;
                }
            }
            if (pending != null) result.put(pending, defaultValue);
            return result;
        }

        private static Integer parseInt(String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException | NullPointerException e) {
                return null;
            }
        }
    }
}
