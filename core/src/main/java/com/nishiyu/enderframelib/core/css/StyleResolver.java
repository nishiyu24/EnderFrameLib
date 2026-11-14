package com.nishiyu.enderframelib.core.css;

import com.nishiyu.enderframelib.core.css.model.*;
import com.nishiyu.enderframelib.core.css.CssSyntax.Declaration;
import com.nishiyu.enderframelib.core.css.LengthResolver.Metrics;
import com.nishiyu.enderframelib.core.css.Selector.RelativeSelector;
import com.nishiyu.enderframelib.core.css.Selector.SelectorToken;
import com.nishiyu.enderframelib.core.css.StyleSheet.CascadeOrigin;
import com.nishiyu.enderframelib.core.css.StyleSheet.ContainerQuery;
import com.nishiyu.enderframelib.core.css.StyleSheet.StyleRule;
import com.nishiyu.enderframelib.core.css.ValueComputer.CssWideKeyword;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.node.NodeType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * カスケード（どの宣言が勝つか）と、その結果から算出スタイルを確定する段。
 *
 * プロパティごとの知識（継承・初期値・ショートハンド・無効化種別）は
 * {@link PropertyRegistry} が持ち、ここには置かない。
 *
 * カスケードの順は CSS Cascade 5 のとおり、重要度 → オリジン（UA / ユーザー / 作者）→ レイヤー →
 * 詳細度 → 出現順。通常の宣言は後ろほど強く、{@code !important} は反転する。
 * {@code revert} はオリジンを、{@code revert-layer} はレイヤーを 1 つ巻き戻す。
 */
public class StyleResolver {

    public static StyleSet resolveStyleSet(HtmlNode node, Map<String, String> inheritedStyle, StyleSheet sheet) {
        boolean interactive = hasInteractiveRules(node, sheet);
        return new StyleSet(node, inheritedStyle, sheet, interactive);
    }

    /**
     * 指定された状態参照先のもとで、継承・シート規則（レイヤー＋詳細度＋出現順）・インラインスタイル・!important を
     * CSS カスケード順に沿って 1 パスで解決する。
     *
     * @param inheritedStyle 親の<b>算出</b>スタイル。継承するプロパティとカスタムプロパティの初期値になり、
     *                       {@code all: inherit} の参照先にもなる
     */
    public static Map<String, String> resolveDeclarations(HtmlNode node, Map<String, String> inheritedStyle,
                                                          StyleSheet sheet, StateLookup stateLookup) {
        return resolveDeclarations(node, null, inheritedStyle, sheet, stateLookup);
    }

    /**
     * 要素に付く疑似要素。{@code BEFORE} / {@code AFTER} は箱を作り（{@link #GENERATED}）、
     * {@code MARKER} はリスト項目のマーカーの、{@code PLACEHOLDER} は入力欄のプレースホルダーの、
     * {@code SELECTION} は選択範囲の、見た目の規則を受け取る。
     */
    public enum PseudoType {
        BEFORE, AFTER, MARKER, PLACEHOLDER, SELECTION;

        /** 持ち主の子として箱を作る疑似要素（{@code content} が箱を生む）。 */
        public static final List<PseudoType> GENERATED = List.of(BEFORE, AFTER);
    }

    /**
     * 疑似要素の算出スタイル。要素と同じカスケード（詳細度・宣言順・{@code !important}・ショートハンドの展開・
     * {@link ValueComputer}）を通し、継承元は持ち主の要素の算出スタイル。
     *
     * 何が壊れていたか: 以前は初回のビルドだけで、一致したルールの宣言を詳細度を見ずに {@code putAll} で
     * 重ね、合成した {@code <span>} の {@code style} 属性に焼き込んでいた。再スタイルでは擬似要素のルールを
     * すべて読み飛ばしていたので、{@code :hover::after} の色や内容が状態に追従しなかった。焼き込んだ span は
     * 要素のルール（{@code span { ... }}）にも当たっていた。
     *
     * @param node                持ち主の要素
     * @param parentComputedStyle 持ち主の算出スタイル
     * @param rootFontSize        rem の基準
     * @return 箱を作るなら算出スタイル。{@code ::before} / {@code ::after} は {@code content} が {@code none} / {@code normal}、
     *         または {@code display: none} なら null（CSS 2.2 §12.1）。{@code ::marker} / {@code ::placeholder} は
     *         規則が 1 つも掛からなければ null
     */
    public static ComputedStyle resolvePseudoStyle(HtmlNode node, PseudoType type, StyleSheet sheet,
                                                   StateLookup stateLookup,
                                                   ComputedStyle parentComputedStyle, Map<String, String> env,
                                                   float rootFontSize, float rootLineHeight, int viewportW, int viewportH) {
        StateLookup lookup = stateLookup != null ? stateLookup : SelectorMatcher.STATIC_ATTRIBUTES;
        ContainerBox container = node != null && type != null && sheet != null && parentComputedStyle != null
                && node.nodeType == NodeType.ELEMENT && !node.isPseudoNode && sheet.index().hasPseudoRules(type)
                ? lookup.nearestSizeContainer(node)
                : ContainerBox.NONE_SIZE;
        return resolvePseudoStyle(node, type, sheet, stateLookup, parentComputedStyle, env,
                rootFontSize, rootLineHeight, viewportW, viewportH, container);
    }

    /** 祖先から引き継いだ最寄りのサイズコンテナを受け取って疑似要素の算出スタイルを解決する。 */
    public static ComputedStyle resolvePseudoStyle(HtmlNode node, PseudoType type, StyleSheet sheet,
                                                   StateLookup stateLookup,
                                                   ComputedStyle parentComputedStyle, Map<String, String> env,
                                                   float rootFontSize, float rootLineHeight, int viewportW, int viewportH,
                                                   ContainerBox container) {
        if (node == null || type == null || sheet == null || parentComputedStyle == null) return null;
        if (node.nodeType != NodeType.ELEMENT || node.isPseudoNode) return null;
        // その疑似要素に掛かる規則が 1 つも無ければ、結果は無い（::before / ::after は生成されない。::marker / ::placeholder は
        // 継承だけで何も宣言されず null になる）。規則が 1 つも無い文書では、要素ごとにカスケードを解かない
        // （リストの項目・入力欄ごとに 920 B の表を作っていた）
        if (!sheet.index().hasPseudoRules(type)) return null;
        Map<String, String> parent = parentComputedStyle.asMap();
        Map<String, String> raw = resolveDeclarations(node, type, parent, sheet, stateLookup);
        if (container == null) {
            StateLookup lookup = stateLookup != null ? stateLookup : SelectorMatcher.STATIC_ATTRIBUTES;
            // 疑似要素にとっては、持ち主の要素もコンテナになり得る（cqw などの基準）
            container = lookup.nearestSizeContainer(node);
        }

        if (type != PseudoType.BEFORE && type != PseudoType.AFTER) {
            if (!declaresAnything(raw, parent)) return null;
            return finalizeComputedStyleOwned(raw, sheet, env, parent, parentComputedStyle.fontSizePx(), rootFontSize,
                    rootLineHeight, viewportW, viewportH, container);
        }

        if (!raw.containsKey(CssProperty.CONTENT)) return null; // content は継承しないので、宣言が無ければ none
        ComputedStyle computed = finalizeComputedStyleOwned(raw, sheet, env, parent,
                parentComputedStyle.fontSizePx(), rootFontSize, rootLineHeight, viewportW, viewportH, container);
        String content = computed.get(CssPropertyIndex.CONTENT);
        if (content == null) return null;
        content = content.trim();
        if (content.isEmpty() || content.equals("none") || content.equals("normal")) return null;
        if ("none".equals(computed.get(CssPropertyIndex.DISPLAY))) return null;
        return computed;
    }

    /** 継承した値のほかに、規則が宣言した値が 1 つでもあるか。 */
    private static boolean declaresAnything(Map<String, String> raw, Map<String, String> parent) {
        for (Map.Entry<String, String> e : raw.entrySet()) {
            String key = e.getKey();
            if (key.startsWith("--")) continue;
            if (!e.getValue().equals(parent.get(key))) return true;
            if (!PropertyRegistry.isInherited(key)) return true;
        }
        return false;
    }

    /**
     * 選択範囲の見た目（{@code ::selection}）。
     *
     * @param color           文字色（ARGB）。規則が無ければ null
     * @param backgroundColor 背景色（ARGB）。規則が無ければ null
     */
    public record SelectionStyle(Integer color, Integer backgroundColor) {
        public static final SelectionStyle NONE = new SelectionStyle(null, null);
    }

    /**
     * 要素の中の選択範囲に掛かる {@code ::selection} の見た目。ハイライト疑似要素は親のハイライトから継承するので、
     * 要素から文書のルートへ祖先をたどり、色ごとに最初に見つかった宣言を使う。
     */
    public static SelectionStyle resolveSelectionStyle(HtmlNode node, StyleSheet sheet,
                                                       StateLookup stateLookup) {
        if (node == null || sheet == null) return SelectionStyle.NONE;
        Integer color = null;
        Integer background = null;
        for (HtmlNode cur = node; cur != null && (color == null || background == null); cur = cur.parent) {
            if (cur.nodeType != NodeType.ELEMENT) continue;
            Map<String, String> declared = resolveDeclarations(cur, PseudoType.SELECTION, Map.of(), sheet, stateLookup);
            expandSelectionColors(declared);
            String c = declared.get(CssProperty.COLOR);
            String b = declared.get(CssProperty.BACKGROUND_COLOR);
            if (color == null && c != null && ColorParser.isColor(c) && !"currentcolor".equalsIgnoreCase(c.trim())) {
                color = ColorParser.parseColor(c);
            }
            if (background == null && b != null && ColorParser.isColor(b) && !"currentcolor".equalsIgnoreCase(b.trim())) {
                background = ColorParser.parseColor(b);
            }
        }
        return color == null && background == null ? SelectionStyle.NONE : new SelectionStyle(color, background);
    }

    /** {@code ::selection { background: red }} のショートハンドから背景色を取り出す。 */
    private static void expandSelectionColors(Map<String, String> declared) {
        String background = declared.get(CssProperty.BACKGROUND);
        if (background == null) return;
        Map<String, String> expanded = ShorthandExpanders.background(background);
        String color = expanded.get(CssProperty.BACKGROUND_COLOR);
        if (color != null) declared.putIfAbsent(CssProperty.BACKGROUND_COLOR, color);
    }

    /** ルールがこの対象（要素自身なら pseudo は null）に掛かるか。 */
    private static boolean targets(StyleRule rule, PseudoType pseudo) {
        return rule.pseudoElement == pseudo;
    }

    /** 規則がいま掛かり得るか: {@code @media} が真で、{@code @container} の条件がすべて満たされる。 */
    private static boolean isConditionMet(StyleRule rule, HtmlNode node, PseudoType pseudo,
                                          StateLookup lookup) {
        if (!rule.mediaActive) return false;
        for (ContainerQuery query : rule.containerQueries) {
            if (!matchesContainerQuery(query, node, pseudo, lookup)) return false;
        }
        return true;
    }

    /**
     * 最も近い、名前が合う問い合わせコンテナの寸法で条件を評価する。コンテナが無い・寸法が分からないときは偽。
     * 疑似要素の持ち主の要素は、その疑似要素にとってのコンテナになり得る。
     */
    private static boolean matchesContainerQuery(ContainerQuery query, HtmlNode node, PseudoType pseudo,
                                                 StateLookup lookup) {
        HtmlNode start = pseudo != null ? node : node.parent;
        for (HtmlNode cur = start; cur != null; cur = cur.parent) {
            ContainerBox box = lookup.containerOf(cur);
            if (box == null || !box.hasName(query.name())) continue;
            // 寸法の問い合わせは寸法を答えられるコンテナ、style() だけの問い合わせは名前が合う最も近い要素が相手
            if (query.styleOnly() || box.isSizeContainer()) {
                return AtRuleProcessor.evaluateContainerQuery(query.condition(), box);
            }
        }
        return false;
    }

    /** {@link ShareCache} に覚える 1 件: 確定した算出スタイルとビューポート依存の有無（生の宣言は保持しない）。 */
    public record Shared(ComputedStyle computed, boolean viewportDependent) {}

    /**
     * 同じ入力からは同じ結果になる「カスケード + 算出」を、兄弟・いとこで使い回すための表（Stylo の style sharing に相当）。
     *
     * 入力が同じとは、親の算出スタイル（の宣言表）・掛かった規則の列・親のフォントサイズ・ルートの基準・ビューポート・
     * コンテナの寸法がすべて同じこと。1000 行の同じ形の行は、この表で 1 行分しか解かれない。
     * 1 回の走査（{@code buildLayoutTree} / {@code restyleLayoutTree}）の間だけ使い、走査のたびに {@link #clear()} する
     * （シート・環境変数・状態が走査の途中で変わらない前提）。
     *
     * 使い回す条件に合わない要素（{@code style} 属性・{@code hidden}・{@code input}・{@code revert} を書く規則）は
     * {@link #resolve} が null を返し、従来の経路で解く。
     */
    public static final class ShareCache {
        private final Map<ShareKey, Shared> map = new HashMap<>();
        private final ShareKey probe = new ShareKey();
        private StyleSheet sheet;
        private Map<String, String> env;
        private int hits, misses;
        private int sheetVersion = -1;
        private static final int MAX_ENTRIES = 4096;
        /** 切り分け用: false なら使い回さず従来の経路で解く（結果は同じになるはず）。 */
        public static volatile boolean enabled = true;
        private static final java.util.concurrent.atomic.AtomicLong TOTAL_HITS = new java.util.concurrent.atomic.AtomicLong();

        /** 診断用: プロセス全体で使い回せた回数。 */
        public static long totalHits() { return TOTAL_HITS.get(); }

        public void clear() {
            map.clear();
            sheet = null;
            env = null;
        }

        /** 診断用: 使い回せた回数と解いた回数。 */
        public int hits() { return hits; }

        public int misses() { return misses; }

        /**
         * @return 使い回せる（または新しく解いて覚えた）結果。この要素が条件に合わなければ null
         */
        public Shared resolve(HtmlNode node, Map<String, String> parent, StyleSheet sheet, StateLookup lookup,
                              Map<String, String> env, float parentFontSize, float rootFontSize, float rootLineHeight,
                              int viewportW, int viewportH, ContainerBox container) {
            if (!enabled || node == null || sheet == null || lookup == null || parent == null) return null;
            if (node.attrs.containsKey("style") || node.inlineOverlay != null || node.attrs.containsKey("hidden")
                    || "input".equals(node.tag)) return null;
            int version = sheet.rules.version();
            // 再スタイルをまたいで残すので、シート・環境・規則の版が変わったら捨てる。件数にも上限を置く
            if (this.sheet != sheet || this.env != env || this.sheetVersion != version || map.size() >= MAX_ENTRIES) {
                map.clear();
                this.sheet = sheet;
                this.env = env;
                this.sheetVersion = version;
            }
            List<StyleRule> candidates = sheet.index().candidates(node);
            ShareKey key = probe;
            key.begin(candidates.size());
            for (int i = 0, size = candidates.size(); i < size; i++) {
                StyleRule rule = candidates.get(i);
                if (!targets(rule, null) || !rule.matches(node, lookup) || !isConditionMet(rule, node, null, lookup)) continue;
                key.add(rule);
            }
            key.finish(parent, parentFontSize, rootFontSize, rootLineHeight, viewportW, viewportH,
                    container.width(), container.height());
            Shared hit = map.get(key);
            if (hit != null) {
                hits++;
                TOTAL_HITS.incrementAndGet();
                return hit;
            }
            // 巻き戻し（revert）は UA / ユーザーのオリジンを別に追う経路でしか解けない
            for (int i = 0; i < key.n; i++) {
                if (key.rules[i].compiled().mentionsRevert) return null;
            }
            misses++;
            Map<String, String> raw = cascadeMatched(parent, sheet, key.rules, key.n);
            boolean viewportDependent = rawDependsOnViewport(raw);
            ComputedStyle computed = finalizeComputedStyleOwned(raw, sheet, env, parent, parentFontSize, rootFontSize,
                    rootLineHeight, viewportW, viewportH, container);
            Shared shared = new Shared(computed, viewportDependent);
            map.put(key.copy(), shared);
            return shared;
        }
    }

    /** 掛かった規則の列（カスケード順）で解く。{@code style} 属性と {@code revert} が無いときの {@link #resolveDeclarations} と同じ結果。 */
    private static Map<String, String> cascadeMatched(Map<String, String> parent, StyleSheet sheet,
                                                      StyleRule[] matched, int count) {
        Map<String, String> result = new StyleTable();
        inheritInto(parent, result);
        applyRegisteredInitialValues(result, parent, sheet);
        CascadeState state = new CascadeState(result, null, null, parent);
        for (int i = 0; i < count; i++) state.apply(matched[i]);
        return result;
    }

    /** {@link ShareCache} の鍵。規則は同一性で比べる。照会用の 1 個を使い回し、見つからなかったときだけ複製する。 */
    private static final class ShareKey {
        StyleRule[] rules = new StyleRule[16];
        int n;
        Map<String, String> parent;
        float parentFontSize, rootFontSize, rootLineHeight, containerW, containerH;
        int viewportW, viewportH;
        int hash;

        void begin(int capacity) {
            n = 0;
            if (rules.length < capacity) rules = new StyleRule[capacity];
        }

        void add(StyleRule rule) {
            rules[n++] = rule;
        }

        void finish(Map<String, String> parent, float pfs, float rfs, float rlh, int vw, int vh, float cw, float ch) {
            this.parent = parent;
            this.parentFontSize = pfs;
            this.rootFontSize = rfs;
            this.rootLineHeight = rlh;
            this.viewportW = vw;
            this.viewportH = vh;
            this.containerW = cw;
            this.containerH = ch;
            int h = System.identityHashCode(parent);
            for (int i = 0; i < n; i++) h = h * 31 + System.identityHashCode(rules[i]);
            h = h * 31 + Float.floatToIntBits(pfs);
            h = h * 31 + Float.floatToIntBits(rfs);
            h = h * 31 + Float.floatToIntBits(rlh);
            h = h * 31 + vw * 7919 + vh;
            h = h * 31 + Float.floatToIntBits(cw);
            h = h * 31 + Float.floatToIntBits(ch);
            this.hash = h;
        }

        ShareKey copy() {
            ShareKey k = new ShareKey();
            k.rules = java.util.Arrays.copyOf(rules, n);
            k.n = n;
            k.parent = parent;
            k.parentFontSize = parentFontSize;
            k.rootFontSize = rootFontSize;
            k.rootLineHeight = rootLineHeight;
            k.containerW = containerW;
            k.containerH = containerH;
            k.viewportW = viewportW;
            k.viewportH = viewportH;
            k.hash = hash;
            return k;
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof ShareKey k)) return false;
            if (hash != k.hash || n != k.n || parent != k.parent || viewportW != k.viewportW || viewportH != k.viewportH
                    || Float.floatToIntBits(parentFontSize) != Float.floatToIntBits(k.parentFontSize)
                    || Float.floatToIntBits(rootFontSize) != Float.floatToIntBits(k.rootFontSize)
                    || Float.floatToIntBits(rootLineHeight) != Float.floatToIntBits(k.rootLineHeight)
                    || Float.floatToIntBits(containerW) != Float.floatToIntBits(k.containerW)
                    || Float.floatToIntBits(containerH) != Float.floatToIntBits(k.containerH)) {
                return false;
            }
            for (int i = 0; i < n; i++) if (rules[i] != k.rules[i]) return false;
            return true;
        }
    }

    private static Map<String, String> resolveDeclarations(HtmlNode node, PseudoType pseudo,
                                                           Map<String, String> inheritedStyle, StyleSheet sheet,
                                                           StateLookup stateLookup) {
        StateLookup lookup = stateLookup != null ? stateLookup : SelectorMatcher.STATIC_ATTRIBUTES;
        Map<String, String> parent = inheritedStyle != null ? inheritedStyle : Map.of();
        Map<String, String> result = new StyleTable();

        inheritInto(parent, result);
        applyRegisteredInitialValues(result, parent, sheet);

        List<StyleRule> candidates = sheet != null && node != null ? sheet.index().candidates(node) : List.of();

        // style 属性を持つ要素は少ないので、インラインの宣言は属性があるときだけ解く（CompiledDeclarations を直接キャッシュする）
        CompiledDeclarations inlineNormal = null;
        CompiledDeclarations inlineImportant = null;
        boolean trackRevert = false;
        if (pseudo == null && node != null && (node.attrs.containsKey("style") || node.inlineOverlay != null)) {
            InlineStyleEntry cachedInline = resolveInlineStyleEntry(node);
            if (node.inlineOverlay != null) {
                Map<String, String> overlaid = cachedInline != null && cachedInline.normalRaw() != null
                        ? new LinkedHashMap<>(cachedInline.normalRaw()) : new LinkedHashMap<>();
                node.inlineOverlay.overlayInline(node, overlaid);
                if (!overlaid.isEmpty()) inlineNormal = CompiledDeclarations.of(overlaid);
                if (cachedInline != null) {
                    inlineImportant = cachedInline.important();
                    trackRevert = cachedInline.impRevert();
                }
                trackRevert |= (inlineNormal != null && inlineNormal.mentionsRevert);
            } else if (cachedInline != null) {
                inlineNormal = cachedInline.normal();
                inlineImportant = cachedInline.important();
                trackRevert = cachedInline.normalRevert() || cachedInline.impRevert();
            }
        }

        // revert の巻き戻し先。UA オリジンの宣言だけでカスケードした結果（継承値を含む）と、それにユーザーオリジンを足した結果。
        // revert / revert-layer を書いた宣言も、UA の枠線色を読む部品（input）も無ければ誰も読まないので、コピーを作らない
        boolean trackOrigins = pseudo == null && node != null && "input".equals(node.tag) || trackRevert;
        for (int i = 0; !trackOrigins && i < candidates.size(); i++) {
            trackOrigins = candidates.get(i).compiled().mentionsRevert;
        }
        Map<String, String> uaValues = trackOrigins ? StyleTable.copyOf(result) : null;
        Map<String, String> userValues = trackOrigins ? StyleTable.copyOf(result) : null;

        // 規則はカスケード順（弱い順）に並んでいる。通常の宣言 → style 属性の通常の宣言 →
        // 作者の !important → style 属性の !important → ユーザー・UA の !important の順に重ねる。
        CascadeState state = new CascadeState(result, uaValues, userValues, parent);
        boolean inlineNormalDone = false;
        boolean inlineImportantDone = false;
        if (sheet != null && node != null) {
            for (StyleRule rule : candidates) {
                boolean important = rule.specificity.important();
                if (important && !inlineNormalDone) {
                    state.applyInline(inlineNormal);
                    inlineNormalDone = true;
                }
                if (important && !inlineImportantDone && rule.specificity.origin().tier() < CascadeOrigin.AUTHOR.tier()) {
                    state.applyInline(inlineImportant);
                    inlineImportantDone = true;
                }
                if (!targets(rule, pseudo) || !rule.matches(node, lookup) || !isConditionMet(rule, node, pseudo, lookup)) {
                    continue;
                }
                state.apply(rule);
            }
        }
        if (!inlineNormalDone) state.applyInline(inlineNormal);
        if (!inlineImportantDone) state.applyInline(inlineImportant);

        if (uaValues != null && pseudo == null && node != null) adjustForAppearance(node, result, uaValues);
        return result;
    }

    private record InlineStyleEntry(int attrVersion, CompiledDeclarations normal, CompiledDeclarations important,
                                    Map<String, String> normalRaw, boolean normalRevert, boolean impRevert) {}

    private static InlineStyleEntry resolveInlineStyleEntry(HtmlNode node) {
        int attrVer = node.attrVersion();
        if (node.inlineStyleCache instanceof InlineStyleEntry cached && cached.attrVersion() == attrVer) {
            return cached;
        }
        String styleText = node.attrs.get("style");
        if (styleText == null) {
            node.inlineStyleCache = null;
            return null;
        }
        Map<String, String> normal = new LinkedHashMap<>();
        Map<String, String> important = null;
        for (Declaration decl : CssSyntax.parseDeclarationList(styleText)) {
            Map<String, String> target;
            if (decl.important()) {
                if (important == null) important = new LinkedHashMap<>();
                target = important;
            } else {
                target = normal;
            }
            target.remove(decl.name());
            target.put(decl.name(), decl.valueText());
        }
        CompiledDeclarations compiledNormal = normal.isEmpty() ? null : CompiledDeclarations.of(normal);
        CompiledDeclarations compiledImp = (important == null || important.isEmpty()) ? null : CompiledDeclarations.of(important);
        Map<String, String> rawNormal = (node.inlineOverlay != null && !normal.isEmpty()) ? normal : null;
        InlineStyleEntry entry = new InlineStyleEntry(attrVer, compiledNormal, compiledImp, rawNormal,
                compiledNormal != null && compiledNormal.mentionsRevert,
                compiledImp != null && compiledImp.mentionsRevert);
        node.inlineStyleCache = entry;
        return entry;
    }

    private static final String[] SIDES = {"top", "right", "bottom", "left"};
    private static final String[] CORNERS = {"top-left", "top-right", "bottom-right", "bottom-left"};

    /**
     * UA の部品で描くチェックボックス・ラジオボタン（{@code appearance} が none 以外）の算出値の調整。
     * Chrome（LayoutTheme::AdjustStyle）と同じく、作者の枠線・padding・角丸は効かない（0 に戻す）。
     * 枠の色は UA のスタイルシートだけでカスケードした値（状態ごとの部品の色。ua.css の :hover / :checked など）に
     * 置き換え、描画側はこれを部品の輪・強調色として読む。背景色は算出値に残るが描画に使わない。
     *
     * 何が壊れていたか: 以前は作者の border / background / padding がそのまま部品の見た目と大きさに効き、
     * Chrome と違って見えた。
     */
    private static void adjustForAppearance(HtmlNode node, Map<String, String> result, Map<String, String> uaValues) {
        boolean toggle = isNativeToggle(node, result);
        if (!toggle && !isNativeRange(node, result)) return;
        // UA の宣言はショートハンド（border など）のまま入っているので、展開してから辺ごとの色を読む
        Map<String, String> ua = StyleTable.copyOf(uaValues);
        PropertyRegistry.expandShorthands(ua);
        for (int i = 0; i < 4; i++) {
            String side = SIDES[i];
            if (toggle) {
                result.put("border-" + side + "-width", "0px");
                result.put("padding-" + side, "0px");
                result.put("border-" + CORNERS[i] + "-radius", "0px");
            }
            String uaColor = ua.get("border-" + side + "-color");
            if (uaColor != null) result.put("border-" + side + "-color", uaColor);
        }
    }

    /**
     * {@code appearance} が none でないレンジ。チェックボックスと違い、作者の枠線と padding は大きさに効く（Chrome の実測）が、
     * 描画は部品の溝とつまみだけなので、枠の色だけを UA の状態ごとの強調色に置き換える。
     */
    public static boolean isNativeRange(HtmlNode node, Map<String, String> style) {
        if (!"input".equals(node.tag)) return false;
        if (!"range".equalsIgnoreCase(node.attrs.getOrDefault("type", "").trim())) return false;
        String appearance = style.get(CssProperty.APPEARANCE);
        return appearance != null && !"none".equalsIgnoreCase(appearance.trim());
    }

    /** {@code appearance} が none でないチェックボックス・ラジオボタン。 */
    public static boolean isNativeToggle(HtmlNode node, Map<String, String> style) {
        if (!"input".equals(node.tag)) return false;
        String type = node.attrs.getOrDefault("type", "").trim().toLowerCase(java.util.Locale.ROOT);
        if (!type.equals("checkbox") && !type.equals("radio")) return false;
        String appearance = style.get(CssProperty.APPEARANCE);
        return appearance != null && !"none".equalsIgnoreCase(appearance.trim());
    }

    /**
     * カスケードを重ねていく途中の状態。規則の宣言を、オリジンとレイヤーが変わるたびに巻き戻し先の
     * スナップショットを取りながら、宣言順に適用する。
     */
    private static final class CascadeState {
        private final Map<String, String> result;
        private final Map<String, String> uaValues;
        private final Map<String, String> userValues;
        private final Map<String, String> parent;

        /** 現在のレイヤー（と重要度・オリジン）の始まりの時点の結果。{@code revert-layer} の巻き戻し先。 */
        private Map<String, String> layerBase;
        private CascadeOrigin currentOrigin;
        private int currentLayer;
        private boolean currentImportant;
        private boolean started;

        CascadeState(Map<String, String> result, Map<String, String> uaValues, Map<String, String> userValues,
                     Map<String, String> parent) {
            this.result = result;
            this.uaValues = uaValues;
            this.userValues = userValues;
            this.parent = parent;
            this.layerBase = uaValues != null ? StyleTable.copyOf(result) : null;
        }

        void apply(StyleRule rule) {
            StyleSheet.Specificity s = rule.specificity;
            if (!started || s.origin() != currentOrigin || s.layer() != currentLayer || s.important() != currentImportant) {
                if (uaValues != null) layerBase = StyleTable.copyOf(result);
                currentOrigin = s.origin();
                currentLayer = s.layer();
                currentImportant = s.important();
                started = true;
            }
            switch (s.origin()) {
                case USER_AGENT -> {
                    applyDeclarations(result, rule.compiled(), null, layerBase, parent);
                    if (uaValues != null) {
                        applyDeclarations(uaValues, rule.compiled(), null, uaValues, parent);
                        applyDeclarations(userValues, rule.compiled(), null, userValues, parent);
                    }
                }
                case USER -> {
                    applyDeclarations(result, rule.compiled(), uaValues, layerBase, parent);
                    if (uaValues != null) applyDeclarations(userValues, rule.compiled(), uaValues, userValues, parent);
                }
                default -> applyDeclarations(result, rule.compiled(), userValues, layerBase, parent);
            }
        }

        /** style 属性の宣言。作者のスタイルの層に入っていない宣言と同じ位置で重なる。 */
        void applyInline(CompiledDeclarations declarations) {
            if (declarations == null) return;
            if (currentOrigin != CascadeOrigin.AUTHOR || currentLayer != StyleSheet.LayerTree.UNLAYERED) {
                if (uaValues != null) layerBase = StyleTable.copyOf(result);
                currentOrigin = CascadeOrigin.AUTHOR;
                currentLayer = StyleSheet.LayerTree.UNLAYERED;
            }
            applyDeclarations(result, declarations, userValues, layerBase, parent);
        }
    }

    /**
     * 登録済みのカスタムプロパティ（{@code @property}）の初期値と継承の有無を反映する。
     * 継承しないプロパティは親から受け取らず、初期値があれば初期値から始める。継承するプロパティは
     * 親が値を持っていなければ初期値から始める。
     */
    private static void applyRegisteredInitialValues(Map<String, String> result, Map<String, String> parent,
                                                     StyleSheet sheet) {
        if (sheet == null || sheet.registeredProperties.isEmpty()) return;
        for (CustomPropertyRegistration registration : sheet.registeredProperties.values()) {
            String name = registration.name();
            if (!registration.inherits()) result.remove(name);
            if (!result.containsKey(name) && registration.initialValue() != null) {
                result.put(name, registration.initialValue());
            }
        }
    }

    /** カスケードの初期値: 親から継承するロングハンドと {@code --*} を写す。親が算出スタイルなら配列を直接走査する。 */
    static void inheritInto(Map<String, String> parent, Map<String, String> result) {
        if (ComputedStyle.copyInheritable(parent, result)) return;
        for (Map.Entry<String, String> entry : parent.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith("--") || inheritsAsLonghand(key)) result.put(key, entry.getValue());
        }
    }

    /** 継承の初期値として親から受け取るのはロングハンドだけ（ショートハンドは展開済みの結果が届く）。 */
    private static boolean inheritsAsLonghand(String key) {
        PropertyRegistry.Property p = PropertyRegistry.get(key);
        return p != null && p.inherited() && !p.isShorthand();
    }

    /**
     * 宣言を宣言順に適用する。
     *
     * ショートハンドを適用するときは、先行するルールのロングハンド（入れ子のショートハンドを含む）を
     * 消してから置く。こうしないと算出時の展開が {@code putIfAbsent} なので、後に書いた
     * ショートハンドが前に書いたロングハンドに負ける。
     *
     * @param revertSource {@code revert} の巻き戻し先（1 つ下のオリジンの結果）。UA オリジンの宣言を適用するときは
     *                     null（UA 自身の {@code revert} は {@code unset} になる）
     * @param layerBase    {@code revert-layer} の巻き戻し先（今のレイヤーの始まりの時点の結果）
     */
    private static void applyDeclarations(Map<String, String> target, CompiledDeclarations decls,
                                          Map<String, String> revertSource, Map<String, String> layerBase,
                                          Map<String, String> parent) {
        StyleTable table = target instanceof StyleTable t ? t : null;
        for (int i = 0; i < decls.n; i++) {
            String key = decls.names[i];
            CssWideKeyword keyword = decls.wide[i];

            if (decls.isAll[i]) {
                if (keyword != null) applyAll(target, keyword, revertSource, layerBase, parent);
                continue;
            }
            if (keyword == CssWideKeyword.REVERT && revertSource != null) {
                revert(target, key, revertSource);
                continue;
            }
            if (keyword == CssWideKeyword.REVERT_LAYER) {
                revert(target, key, layerBase);
                continue;
            }
            // 解釈できないショートハンド・色は宣言ごと無効（先行するロングハンドを消さない）。判定は解析時に済んでいる
            if (decls.invalid[i]) continue;

            int[] subIds = decls.subIds[i];
            if (subIds.length != 0) {
                String[] subNames = decls.subNames[i];
                for (int j = 0; j < subIds.length; j++) {
                    if (table != null && subIds[j] >= 0) table.putById(subIds[j], null);
                    else target.remove(subNames[j]);
                }
            }
            int id = decls.ids[i];
            if (table != null && id >= 0) {
                table.putValueById(id, keyword == CssWideKeyword.REVERT ? CompiledDeclarations.UNSET : decls.cssValues[i]);
            } else {
                target.put(key, keyword == CssWideKeyword.REVERT ? "unset" : decls.values[i]);
            }
        }
    }

    /** 色の型のプロパティなら、値が色として解けるか。色の型でなければ常に真。 */
    static boolean isValidColorValue(PropertyRegistry.Property p, String key, String value) {
        if (p == null || p.type() != PropertyRegistry.ValueType.COLOR || CssWideKeyword.of(value) != null) return true;
        // outline-color だけは invert を取る（CSS UI 4 §3.4）
        if ("outline-color".equals(key) && "invert".equalsIgnoreCase(value.trim())) return true;
        return ColorParser.isColor(value);
    }

    private static void revert(Map<String, String> target, String key, Map<String, String> source) {
        List<String> keys = new ArrayList<>();
        keys.add(key);
        keys.addAll(PropertyRegistry.subproperties(key));
        for (String k : keys) {
            String v = source.get(k);
            if (v != null) target.put(k, v);
            else target.remove(k);
        }
    }

    /** {@code all} はカスタムプロパティと {@code direction} 以外のすべてを書き換える。 */
    private static void applyAll(Map<String, String> target, CssWideKeyword keyword,
                                 Map<String, String> revertSource, Map<String, String> layerBase,
                                 Map<String, String> parent) {
        target.keySet().removeIf(k -> !k.startsWith("--") && !"direction".equals(k));
        Map<String, String> source = keyword == CssWideKeyword.REVERT_LAYER ? layerBase
                : keyword == CssWideKeyword.REVERT ? revertSource : null;
        if (source != null) {
            source.forEach((k, v) -> {
                if (!k.startsWith("--") && !"direction".equals(k)) target.put(k, v);
            });
            return;
        }
        for (String longhand : PropertyRegistry.allLonghands()) {
            boolean fromParent = keyword == CssWideKeyword.INHERIT
                    || (keyword != CssWideKeyword.INITIAL && PropertyRegistry.isInherited(longhand));
            String value = fromParent ? parent.get(longhand) : null;
            if (value == null) value = PropertyRegistry.initialValue(longhand);
            if (value != null) target.put(longhand, value);
        }
    }

    private static final String[] VIEWPORT_SUFFIXES = {"w", "h", "i", "b", "min", "max"};

    /** 末尾の単位（w / h / i / b / min / max）が {@code at} から始まり、その直後が英字でないか。 */
    private static boolean viewportSuffixAt(String v, int at) {
        for (String suffix : VIEWPORT_SUFFIXES) {
            int end = at + suffix.length();
            if (v.regionMatches(true, at, suffix, 0, suffix.length())
                    && (end >= v.length() || !isAsciiLetter(v.charAt(end)))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    /**
     * 値がビューポート（vw/vh…）・コンテナ（cqw…）の単位、{@code var()}、{@code env()} を含むか。
     * 正規表現（{@code Matcher}）を作らずに判定する。
     */
    public static boolean valueDependsOnViewport(String v) {
        int n = v.length();
        for (int i = 0; i < n; i++) {
            char c = v.charAt(i);
            if (c == '(' && i >= 3) {
                if (v.regionMatches(true, i - 3, "var", 0, 3) || v.regionMatches(true, i - 3, "env", 0, 3)) return true;
            }
            if (c >= '0' && c <= '9' && i + 1 < n) {
                int j = i + 1;
                char d = v.charAt(j);
                if ((d == 's' || d == 'S' || d == 'l' || d == 'L' || d == 'd' || d == 'D') && j + 1 < n
                        && (v.charAt(j + 1) == 'v' || v.charAt(j + 1) == 'V') && viewportSuffixAt(v, j + 2)) {
                    return true;
                }
                if ((d == 'v' || d == 'V') && viewportSuffixAt(v, j + 1)) return true;
                if ((d == 'c' || d == 'C') && j + 1 < n && (v.charAt(j + 1) == 'q' || v.charAt(j + 1) == 'Q')
                        && viewportSuffixAt(v, j + 2)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 生の宣言のどれかの値が {@link #valueDependsOnViewport} を満たすか。{@link StyleTable} なら、値の文字列を毎回走査せず
     * 正規の値（{@link CssValue}）が覚えた答えを引く（同じ値は全要素で 1 回しか解析しない）。
     */
    public static boolean rawDependsOnViewport(Map<String, String> raw) {
        if (raw instanceof StyleTable table) return table.anyValueDependsOnViewport();
        for (String value : raw.values()) {
            if (value != null && valueDependsOnViewport(value)) return true;
        }
        return false;
    }

    /**
     * このノードの対話状態（:hover / :active / :focus*）が変わると、シート内のどこかの規則の照合が変わり得るか。
     * セレクタのどの位置の複合セレクタでもよい（{@code .parent:hover .child} は {@code .parent} で真）。
     * その複合セレクタより左（祖先・先行する兄弟）も状態を無視して照合し、右側は照合しない（保守的に真）。
     *
     * 何が壊れていたか: 以前は状態を持つ複合セレクタを左側の結合子を見ずに node に当てていたので、
     * {@code .menu .item:hover} が {@code .menu} の外の {@code .item} でも真になっていた。
     */
    public static boolean hasInteractiveRules(HtmlNode node, StyleSheet sheet) {
        // 対話規則だけの部分集合を見る（シートの全規則は走査しない）。詳細は StyleStateAutomaton
        return com.nishiyu.enderframelib.core.kernel.compiler.StyleStateAutomaton.interactiveMask(node, sheet) != 0;
    }

    /**
     * 兄弟結合子（{@code +} / {@code ~}）の左側に状態擬似クラスを持つ規則があるか。
     * あれば、状態の変わった要素の後続の兄弟（とその子孫）も再スタイルしなければならない
     * （{@code input:checked + label}、{@code .tab:hover ~ .panel}）。
     */
    public static boolean hasSiblingStateRules(StyleSheet sheet) {
        if (sheet == null) return false;
        return sheet.hasSiblingStateRules(rule -> rule.stateMask != 0 && hasSiblingStateRule(rule.parsedSelector()));
    }

    /** {@code :is(input:checked + label) span} のように :is() / :not() の中の兄弟結合子も見る。 */
    private static boolean hasSiblingStateRule(Selector selector) {
        List<SelectorToken> compounds = selector.compounds();
        List<Character> combinators = selector.combinators();
        boolean stateOnLeft = false;
        for (int i = 0; i < compounds.size(); i++) {
            SelectorToken token = compounds.get(i);
            for (Selector nested : token.notSelectors) {
                if (hasSiblingStateRule(nested)) return true;
            }
            for (List<Selector> group : token.anyOfSelectors) {
                for (Selector nested : group) {
                    if (hasSiblingStateRule(nested)) return true;
                }
            }
            for (List<RelativeSelector> group : token.hasSelectors) {
                for (RelativeSelector relative : group) {
                    if (hasSiblingStateRule(relative.selector())) return true;
                }
            }
            if (i >= combinators.size()) break;
            stateOnLeft |= token.dependentStateMask() != 0;
            char c = combinators.get(i);
            if (stateOnLeft && (c == '+' || c == '~')) return true;
        }
        return false;
    }

    /**
     * 2 つの算出スタイルの差分がレイアウト（measure / layout）の再実行を要するかどうかを判定する。
     * ペイント専用プロパティしか変わっていなければ false を返し、repaint のみで済ませる。
     */
    /** 直近の判定の記憶（古い・新しいスタイルの組 → 結果）。共有された算出スタイルは同じ組が行ごとに何度も来るので、差分を取り直さない。 */
    private static final class LayoutDiffSlot {
        ComputedStyle oldStyle;
        ComputedStyle newStyle;
        boolean affects;
    }

    private static final int LAYOUT_DIFF_SIZE = 256;
    private static final ThreadLocal<LayoutDiffSlot[]> LAYOUT_DIFF = ThreadLocal.withInitial(() -> {
        LayoutDiffSlot[] slots = new LayoutDiffSlot[LAYOUT_DIFF_SIZE];
        for (int i = 0; i < slots.length; i++) slots[i] = new LayoutDiffSlot();
        return slots;
    });

    public static boolean isLayoutAffectingChange(ComputedStyle oldStyle, ComputedStyle newStyle) {
        if (oldStyle == null || newStyle == null) return true;
        if (oldStyle == newStyle) return false;
        LayoutDiffSlot[] cache = LAYOUT_DIFF.get();
        int slot = (System.identityHashCode(oldStyle) * 31 + System.identityHashCode(newStyle)) & (cache.length - 1);
        LayoutDiffSlot hit = cache[slot];
        if (hit.oldStyle == oldStyle && hit.newStyle == newStyle) return hit.affects;
        boolean affects = computeLayoutAffectingChange(oldStyle, newStyle);
        hit.oldStyle = oldStyle;
        hit.newStyle = newStyle;
        hit.affects = affects;
        return affects;
    }

    private static boolean computeLayoutAffectingChange(ComputedStyle oldStyle, ComputedStyle newStyle) {
        if (oldStyle == null || newStyle == null) return true;
        return oldStyle.hasLayoutAffectingDiffFrom(newStyle);
    }

    public static ComputedStyle finalizeComputedStyle(Map<String, String> rawStyle, StyleSheet sheet, Map<String, String> env) {
        return finalizeComputedStyle(rawStyle, sheet, env, ComputedStyle.DEFAULT_FONT_SIZE, ComputedStyle.DEFAULT_FONT_SIZE);
    }

    /**
     * 親のフォントサイズを基準に算出スタイルを確定する（親の算出スタイルとビューポートが分からない呼び出し用）。
     * {@code inherit} は初期値になり、{@code font-size} の {@code vw} / {@code vh} は 0 で解かれる。
     */
    public static ComputedStyle finalizeComputedStyle(Map<String, String> rawStyle, StyleSheet sheet,
                                                      Map<String, String> env,
                                                      float parentFontSize, float rootFontSize) {
        return finalizeComputedStyle(rawStyle, sheet, env, Map.of(), parentFontSize, rootFontSize, 0, 0);
    }

    /**
     * カスケードの結果から算出スタイルを確定する。
     *
     * 順序は CSS と同じで、{@code var()} の置換 → ショートハンドの展開 → 算出値
     * （CSS 全域キーワード・{@code font-size}・{@code em} / {@code rem}・{@code currentcolor}）。
     *
     * @param parentStyle    親の算出スタイル（{@code inherit} の参照先）
     * @param parentFontSize 親のフォントサイズ（px）。{@code font-size} 自身の em / % の基準
     * @param rootFontSize   ルート要素のフォントサイズ（px）。{@code rem} の基準
     */
    public static ComputedStyle finalizeComputedStyle(Map<String, String> rawStyle, StyleSheet sheet,
                                                      Map<String, String> env, Map<String, String> parentStyle,
                                                      float parentFontSize, float rootFontSize,
                                                      int viewportW, int viewportH) {
        return finalizeComputedStyle(rawStyle, sheet, env, parentStyle, parentFontSize, rootFontSize,
                viewportW, viewportH, ContainerBox.NONE_SIZE);
    }

    /**
     * 問い合わせコンテナの寸法を足した算出。{@code cqw} などのコンテナ単位は、この寸法で px に解く。
     *
     * @param container 最も近い祖先の問い合わせコンテナ。無ければ {@link ContainerBox#NONE_SIZE}
     */
    public static ComputedStyle finalizeComputedStyle(Map<String, String> rawStyle, StyleSheet sheet,
                                                      Map<String, String> env, Map<String, String> parentStyle,
                                                      float parentFontSize, float rootFontSize,
                                                      int viewportW, int viewportH,
                                                      ContainerBox container) {
        return finalizeComputedStyle(rawStyle, sheet, env, parentStyle, parentFontSize, rootFontSize, Float.NaN,
                viewportW, viewportH, container);
    }

    /**
     * ルート要素の行の高さを足した算出。{@code rlh} は、この高さ（px）で px に解く。
     *
     * @param rootLineHeight ルート要素の行の高さ（px）。分からなければ NaN（ルートの {@code normal} の高さ）
     */
    public static ComputedStyle finalizeComputedStyle(Map<String, String> rawStyle, StyleSheet sheet,
                                                      Map<String, String> env, Map<String, String> parentStyle,
                                                      float parentFontSize, float rootFontSize, float rootLineHeight,
                                                      int viewportW, int viewportH,
                                                      ContainerBox container) {
        return finalizeComputedStyleInternal(StyleTable.copyOf(rawStyle), rawStyle, sheet, env, parentStyle,
                parentFontSize, rootFontSize, rootLineHeight, viewportW, viewportH, container);
    }

    /** 呼び出し側が生の {@link StyleTable} の所有権を手放せる場合に防御的コピーを省く経路。 */
    public static ComputedStyle finalizeComputedStyleOwned(Map<String, String> rawStyle, StyleSheet sheet,
                                                           Map<String, String> env, Map<String, String> parentStyle,
                                                           float parentFontSize, float rootFontSize, float rootLineHeight,
                                                           int viewportW, int viewportH,
                                                           ContainerBox container) {
        StyleTable style = (rawStyle instanceof StyleTable owned && owned.extras == null)
                ? owned
                : StyleTable.copyOf(rawStyle);
        return finalizeComputedStyleInternal(style, rawStyle, sheet, env, parentStyle, parentFontSize, rootFontSize,
                rootLineHeight, viewportW, viewportH, container);
    }

    private static ComputedStyle finalizeComputedStyleInternal(StyleTable style, Map<String, String> rawStyle,
                                                               StyleSheet sheet, Map<String, String> env,
                                                               Map<String, String> parentStyle, float parentFontSize,
                                                               float rootFontSize, float rootLineHeight, int viewportW,
                                                               int viewportH, ContainerBox container) {
        float ownFontSize = estimateFontSize(rawStyle, parentFontSize, rootFontSize, viewportW, viewportH);
        float rootLineHeightPx = Float.isNaN(rootLineHeight)
                ? rootFontSize * LengthResolver.NORMAL_LINE_HEIGHT_EM : rootLineHeight;
        Metrics typedMetrics = new Metrics(0, viewportW, viewportH, ownFontSize, rootFontSize,
                ownFontSize * LengthResolver.NORMAL_LINE_HEIGHT_EM, rootLineHeightPx,
                container.width(), container.height());
        substituteVariables(style, rawStyle, sheet, env, parentStyle != null ? parentStyle : Map.of(), typedMetrics);
        PropertyRegistry.expandShorthands(style);
        ValueComputer.compute(style, new ValueComputer.Context(
                parentStyle != null ? parentStyle : Map.of(), parentFontSize, rootFontSize, viewportW, viewportH,
                container.width(), container.height(), rootLineHeight));
        return ComputedStyle.ofOwned(style, parentFontSize, rootFontSize);
    }

    /** 登録したカスタムプロパティの絶対化に使う、この要素のフォントサイズ（var() を含むなら親のまま）。 */
    private static float estimateFontSize(Map<String, String> raw, float parentFontSize, float rootFontSize,
                                          int viewportW, int viewportH) {
        String fontSize = raw instanceof StyleTable table ? table.textById(CssPropertyIndex.FONT_SIZE) : raw.get(CssProperty.FONT_SIZE);
        if (fontSize == null || fontSize.contains("var(") || CssWideKeyword.of(fontSize) != null) return parentFontSize;
        return ValueComputer.resolveFontSize(fontSize,
                new ValueComputer.Context(Map.of(), parentFontSize, rootFontSize, viewportW, viewportH,
                        Float.NaN, Float.NaN));
    }

    /**
     * カスタムプロパティの参照を解く。参照先は要素自身（継承分を含む）→ {@code :root} の変数表 →
     * 埋め込み側の環境変数（{@code --name} を {@code name} で引く）の順。
     * 算出時に無効になった宣言は {@code unset} に、無効なカスタムプロパティは削除する。
     *
     * 登録したカスタムプロパティ（{@code @property}）は、解いた値が構文に合うかを確かめて算出値（絶対化した形）にする。
     * 合わなければ算出時に無効で、継承するなら親の算出値、しなければ初期値になる。
     */
    private static void substituteVariables(StyleTable style, Map<String, String> raw,
                                            StyleSheet sheet, Map<String, String> env,
                                            Map<String, String> parent, Metrics metrics) {
        Function<String, CustomPropertyRegistration> registrations =
                name -> sheet == null ? null : sheet.registeredProperties.get(name);
        VarResolver vars = new VarResolver(name -> {
            String v = raw.get(name);
            if (v == null && sheet != null) v = sheet.variables.get(name);
            if (v == null && env != null) v = env.get(name.substring(2));
            // 空のカスタムプロパティは未定義と同じに扱う（フォールバックが効く。従来の挙動）
            return v == null || v.isBlank() ? null : v;
        }, (name, resolved) -> {
            CustomPropertyRegistration registration = registrations.apply(name);
            if (registration == null) return resolved;
            String computed = registration.compute(resolved, metrics);
            if (computed != null) return computed;
            // 算出時に無効: 継承するなら親の算出値、そうでなければ初期値
            String fallback = registration.inherits() ? parent.get(name) : null;
            return fallback != null ? fallback : registration.initialValue();
        });
        // 添字を持つ宣言: var() を含む値だけを解く（添字で配列を直接走査する）
        CssValue[] values = style.values;
        for (int id = 0; id < values.length; id++) {
            CssValue cv = values[id];
            if (cv == null || !cv.containsVar()) continue;
            String value = cv.text;
            String key = ComputedStyle.CssPropertyId.name(id);
            String resolved = vars.substitute(value);
            if (resolved != null && !isValidColorValue(PropertyRegistry.get(key), key, resolved)) resolved = null;
            style.putById(id, resolved != null ? resolved : "unset");
        }
        // 添字を持たない宣言: {@code --*}（と未登録の名前）
        if (style.extras == null) return;
        for (String key : style.extras.keySet().toArray(new String[0])) {
            String value = style.extras.get(key);
            if (key.startsWith("--")) {
                // 参照も登録も無い普通のカスタムプロパティ（継承で大量に届く）は、解いた値が宣言そのもの
                if (value != null && !value.isBlank() && !value.contains("var(") && registrations.apply(key) == null) continue;
                String resolved = vars.resolveCustomProperty(key);
                if (resolved == null) style.remove(key);
                else style.put(key, resolved);
            } else if (value != null && value.contains("var(")) {
                String resolved = vars.substitute(value);
                if (resolved != null && !isValidColorValue(PropertyRegistry.get(key), key, resolved)) resolved = null;
                style.put(key, resolved != null ? resolved : "unset");
            }
        }
    }

    /**
     * 1 つの DOM ノードに対するスタイル解決の文脈。
     *
     * 以前は {@code base / hover / active / focus} の 4 マップに分けて {@code putAll} で重ねていたため、
     * 詳細度や出現順に関わらず {@code :hover} が基底スタイルに勝ち、{@code :active} が {@code :focus} に勝っていた。
     * 現在は状態をカスケードの入力（{@link StateLookup}）として渡し、
     * 詳細度と出現順に従った単一のカスケードで解決する。
     */
    static final class VarResolver {

        /** 置換結果がこの長さを超えたら無効にする（{@code --a: var(--b) var(--b)} の倍々ゲーム対策）。 */
        private static final int MAX_LENGTH = 65536;

        private final Function<String, String> lookup;
        /** 解いた値を、登録済みのプロパティの型で算出値にする（登録が無ければそのまま返す）。 */
        private final java.util.function.BiFunction<String, String, String> finisher;

        /**
         * @param lookup カスタムプロパティ名（{@code --x}）→ 置換前の値。未定義なら null
         */
        VarResolver(Function<String, String> lookup) {
            this(lookup, (name, resolved) -> resolved);
        }

        VarResolver(Function<String, String> lookup, java.util.function.BiFunction<String, String, String> finisher) {
            this.lookup = lookup;
            this.finisher = finisher;
        }

        /** {@code var()} を含まない値に置き換える。算出時に無効なら null。 */
        String substitute(String value) {
            return substitute(value, new HashSet<>());
        }

        /** カスタムプロパティ {@code name} の算出値（参照を解いた値）。循環・未定義なら null。 */
        String resolveCustomProperty(String name) {
            return resolveVariable(name, new HashSet<>());
        }

        private String substitute(String value, Set<String> resolving) {
            if (value == null) return null;
            if (!value.contains("var(")) return value;

            StringBuilder out = new StringBuilder();
            int i = 0;
            int len = value.length();
            while (i < len) {
                int start = indexOfVar(value, i);
                if (start < 0) {
                    out.append(value, i, len);
                    break;
                }
                out.append(value, i, start);
                int open = start + 3; // "var" の後ろの '('
                int close = matchingParen(value, open);
                if (close < 0) return null; // 閉じていない var( は構文として無効

                String args = value.substring(open + 1, close);
                int comma = topLevelComma(args);
                String name = (comma >= 0 ? args.substring(0, comma) : args).trim();
                String fallback = comma >= 0 ? args.substring(comma + 1).trim() : null;
                if (!name.startsWith("--")) return null;

                String resolved = resolveVariable(name, resolving);
                if (resolved == null && fallback != null) resolved = substitute(fallback, resolving);
                if (resolved == null) return null;

                out.append(resolved);
                if (out.length() > MAX_LENGTH) return null;
                i = close + 1;
            }
            return out.toString();
        }

        private String resolveVariable(String name, Set<String> resolving) {
            if (!resolving.add(name)) return null; // 循環
            try {
                String raw = lookup.apply(name);
                String substituted = raw == null ? null : substitute(raw, resolving);
                return finisher.apply(name, substituted);
            } finally {
                resolving.remove(name);
            }
        }

        /** 識別子の途中（{@code --my-var(} など）ではない {@code var(} の位置。 */
        private static int indexOfVar(String s, int from) {
            int idx = s.indexOf("var(", from);
            while (idx > 0) {
                char prev = s.charAt(idx - 1);
                if (!Character.isLetterOrDigit(prev) && prev != '-' && prev != '_') break;
                idx = s.indexOf("var(", idx + 1);
            }
            return idx;
        }

        private static int matchingParen(String s, int open) {
            int depth = 0;
            char quote = 0;
            for (int i = open; i < s.length(); i++) {
                char c = s.charAt(i);
                if (quote != 0) {
                    if (c == quote) quote = 0;
                } else if (c == '"' || c == '\'') {
                    quote = c;
                } else if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                    if (depth == 0) return i;
                }
            }
            return -1;
        }

        private static int topLevelComma(String s) {
            int depth = 0;
            char quote = 0;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (quote != 0) {
                    if (c == quote) quote = 0;
                } else if (c == '"' || c == '\'') {
                    quote = c;
                } else if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                } else if (c == ',' && depth == 0) {
                    return i;
                }
            }
            return -1;
        }
    }
}
