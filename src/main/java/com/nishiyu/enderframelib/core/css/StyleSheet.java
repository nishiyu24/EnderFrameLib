package com.nishiyu.enderframelib.core.css;

import com.nishiyu.enderframelib.core.css.model.*;

import com.nishiyu.enderframelib.core.css.StyleResolver.PseudoType;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * パース済みスタイルシートと、それを構成する型（ルール・詳細度・カスケードのオリジン・レイヤー）。
 */
public class StyleSheet {

    /**
     * カスケード順（重要度 → オリジン → レイヤー → 詳細度 → 出現順）に並んだ規則。
     * 変更のたびに増える版番号を持ち、規則の索引（{@link RuleIndex}）の作り直しの目印にする。
     */
    public static final class RuleList extends ArrayList<StyleRule> {
        private static final long serialVersionUID = 1L;

        public int version() {
            return modCount;
        }
    }

    public final RuleList rules = new RuleList();
    /** 名前 → {@code @keyframes}。同じ名前は後のものが勝つ。 */
    public final Map<String, Keyframes> keyframes = new LinkedHashMap<>();
    /** 名前 → 作者の {@code @counter-style}。同じ名前は後のものが勝つ。 */
    public final Map<String, CounterStyle> counterStyles = new LinkedHashMap<>();
    public final Map<String, String> variables = new HashMap<>();
    /** {@code @property} で登録したカスタムプロパティ（名前は {@code --x}）。シートごとに持つ（文書の登録簿）。 */
    public final Map<String, CustomPropertyRegistration> registeredProperties = new LinkedHashMap<>();
    public final LayerTree layers = new LayerTree();

    private RuleIndex index;
    private int indexedVersion = -1;
    private CssParser.MediaEnvironment mediaEnvironment = CssParser.MediaEnvironment.of(0, 0);

    /** 規則を引くための索引。規則が変わっていれば作り直す。 */
    public RuleIndex index() {
        if (index == null || indexedVersion != rules.version()) {
            index = RuleIndex.build(rules);
            indexedVersion = rules.version();
        }
        return index;
    }

    /** {@code container-type} / {@code container-name} / {@code container} を宣言する規則があるか。あれば、レイアウト後にコンテナの寸法を測る。 */
    public boolean declaresContainers() {
        for (StyleRule rule : rules) {
            for (String property : rule.properties.keySet()) {
                if (property.equals(CssProperty.CONTAINER_TYPE) || property.equals(CssProperty.CONTAINER_NAME)
                        || property.equals("container")) {
                    return true;
                }
            }
        }
        return false;
    }

    /** {@code :has()} を使う規則があるか。あれば、子孫・後続の兄弟の変化で祖先も再スタイルする。 */
    public boolean usesHasSelectors() {
        for (StyleRule rule : rules) {
            if (rule.parsedSelector().usesHas()) return true;
        }
        return false;
    }

    private boolean usesInherit;
    private int usesInheritVersion = -1;

    /**
     * どれかの規則が {@code inherit} を宣言値に取るか。取るなら、親の非継承プロパティの変化が子の算出値に届き得るので、
     * 親が変われば子孫も算出し直す。取らなければ、継承しないプロパティだけの変化で子孫を巻き込まない。
     */
    public boolean usesInheritKeyword() {
        if (usesInheritVersion != rules.version()) {
            boolean found = false;
            for (StyleRule rule : rules) {
                for (String value : rule.properties.values()) {
                    if (ValueComputer.CssWideKeyword.of(value) == ValueComputer.CssWideKeyword.INHERIT) {
                        found = true;
                        break;
                    }
                }
                if (found) break;
            }
            usesInherit = found;
            usesInheritVersion = rules.version();
        }
        return usesInherit;
    }

    /** {@code @container} の中の規則があるか。あれば、コンテナの寸法が変わると再スタイルが要る。 */
    public boolean usesContainerQueries() {
        for (StyleRule rule : rules) {
            if (!rule.containerQueries.isEmpty()) return true;
        }
        return false;
    }

    /**
     * 別のシート（UA シートへ作者シートを重ねるなど）の中身をこのシートの後ろへ足す。
     * 規則の順は呼び出し側が {@link #sortCascade()} で整える。
     */
    public void merge(StyleSheet other) {
        rules.addAll(other.rules);
        keyframes.putAll(other.keyframes);
        counterStyles.putAll(other.counterStyles);
        variables.putAll(other.variables);
        registeredProperties.putAll(other.registeredProperties);
        layers.merge(other.layers);
    }

    private InvalidationSets invalidationSets;
    private int invalidationVersion = -1;

    /**
     * 規則から作った無効化セット。class・id・属性・状態の変化が、変化した要素から見てどの範囲の
     * スタイルを変え得るかを引ける。規則が変わっていれば作り直す。
     */
    public InvalidationSets invalidationSets() {
        if (invalidationSets == null || invalidationVersion != rules.version()) {
            invalidationSets = InvalidationSets.build(rules);
            invalidationVersion = rules.version();
        }
        return invalidationSets;
    }

    /** 規則の {@code @media} の条件を評価している環境（ビューポートの大きさと好み）。 */
    public CssParser.MediaEnvironment mediaEnvironment() {
        return mediaEnvironment;
    }

    /**
     * メディア環境（ビューポートの大きさなど）を差し替え、{@code @media} の条件を評価し直す。
     * CSS は読み直さない。どれかの規則の有効・無効が変わったら true（呼び出し側は再スタイルする）。
     * 環境が同じなら何もせず false。
     */
    public boolean updateMediaEnvironment(CssParser.MediaEnvironment next) {
        mediaEnvironment = next;
        boolean changed = false;
        for (StyleRule rule : rules) {
            boolean active = true;
            for (String condition : rule.mediaQueries) {
                if (!AtRuleProcessor.evaluateMediaQuery(condition, next)) {
                    active = false;
                    break;
                }
            }
            if (active != rule.mediaActive) {
                rule.mediaActive = active;
                changed = true;
            }
        }
        return changed;
    }

    /** 現在のメディア環境で掛かる規則の数。 */
    public int activeRuleCount() {
        int count = 0;
        for (StyleRule rule : rules) {
            if (rule.mediaActive) count++;
        }
        return count;
    }

    /** レイヤーの順位を規則へ配り、カスケード順（安定ソート）に並べ直す。 */
    public void sortCascade() {
        for (StyleRule rule : rules) {
            rule.setLayerRank(layers.rankOf(rule.layer));
        }
        rules.sort(StyleRule::compareSpecificity);
    }

    public record ContainerQuery(String name, String condition, boolean styleOnly) {

        public static ContainerQuery of(String name, String condition) {
            return new ContainerQuery(name, condition, onlyStyleFeatures(condition));
        }

        /** 条件が {@code style()} の問い合わせだけでできているか（寸法の特性を含まない）。 */
        private static boolean onlyStyleFeatures(String condition) {
            boolean sawStyle = false;
            for (CssSyntax.ComponentValue v : CssSyntax.parseComponentValues(condition)) {
                if (v instanceof CssSyntax.Function f) {
                    if (!f.is("style")) return false;
                    sawStyle = true;
                } else if (v instanceof CssSyntax.Block) {
                    return false;
                }
            }
            return sawStyle;
        }
    }

    /**
     * 1 本のセレクタと、その宣言ブロック。
     * セレクタのパースと照合は {@link SelectorParser} / {@link SelectorMatcher} が受け持ち、
     * このクラスはルールとしての組み合わせ（宣言・詳細度・疑似クラス状態・レイヤー・条件）を保持する。
     */
    public static class StyleRule {

        private Selector parsed;

        /** 疑似要素を取り除いたセレクタ本体のテキスト。 */
        public String selector;
        public Specificity specificity;

        public boolean isHover;
        public boolean isActive;
        public boolean isFocus;
        public int stateMask;
        public boolean isBefore;
        public boolean isAfter;
        /** 規則が掛かる疑似要素。要素自身に掛かる規則は null。 */
        public PseudoType pseudoElement;

        /** 宣言順を保つため LinkedHashMap。 */
        public Map<String, String> properties = new LinkedHashMap<>();

        /** 規則が属する {@code @layer} の名前の道筋。層の無い規則は空。 */
        public List<String> layer = List.of();
        /** 外側から順に並んだ {@code @container} の条件。全部が満たされたときだけ規則が掛かる。 */
        public List<ContainerQuery> containerQueries = List.of();
        /** 外側から順に並んだ {@code @media} の条件。ビューポートに対して全部が真のときだけ規則が掛かる。 */
        public List<String> mediaQueries = List.of();
        /** 現在のメディア環境で {@link #mediaQueries} が全部真か。{@link StyleSheet#updateMediaEnvironment} が更新する。 */
        public boolean mediaActive = true;

        public StyleRule(String selector) {
            this(SelectorParser.parse(selector, CascadeOrigin.AUTHOR, false));
        }

        public StyleRule(String selector, CascadeOrigin origin, boolean important) {
            this(SelectorParser.parse(selector, origin, important));
        }

        public StyleRule(Selector parsed) {
            this.parsed = parsed;
            this.selector = parsed.text();
            this.specificity = parsed.specificity();
            this.isHover = parsed.isHover();
            this.isActive = parsed.isActive();
            this.isFocus = parsed.isFocus();
            this.stateMask = parsed.stateMask();
            this.isBefore = parsed.isBefore();
            this.isAfter = parsed.isAfter();
            this.pseudoElement = parsed.pseudoElement();
        }

        public Selector parsedSelector() {
            return parsed;
        }

        /** オリジンを差し替える（UA シートに作者シートを重ねるときに使う）。 */
        public void setOrigin(CascadeOrigin origin) {
            this.parsed = parsed.withOrigin(origin);
            this.specificity = specificity.withOrigin(origin);
        }

        void setLayerRank(int rank) {
            this.specificity = specificity.withLayer(rank);
        }

        public boolean matches(HtmlNode node) {
            return SelectorMatcher.matches(parsed, node);
        }

        public boolean matches(HtmlNode node, StateLookup stateLookup) {
            return SelectorMatcher.matches(parsed, node, stateLookup);
        }

        /** カスケード順（弱い順）の比較子。 */
        public static int compareSpecificity(StyleRule a, StyleRule b) {
            return a.specificity.compareTo(b.specificity);
        }
    }

    /**
     * CSS のカスケードの優先順位（重要度・オリジン・レイヤー・詳細度）。
     *
     * 以前は「セレクタ文字列の文字数カウント」と {@code += 10000} のマジック定数だったため、
     * 桁溢れ（クラスが 10 個あると ID 1 個を超える）と {@code ::before} が class より強くなる
     * 逆転が起きていた。ここでは (origin, important, layer, id, class, type) のタプルで比較する。
     *
     * 比較の強さは CSS Cascade 5 §6.1 の順に important → origin → layer → id → class → type。
     * 通常の宣言ではオリジンもレイヤーも後ろの方が強く、層に入っていない宣言が最も強い。
     * {@code !important} ではどちらも反転する（UA が最強、層は先に宣言したものが強く、層に入っていない宣言が最弱）。
     *
     * @param layer レイヤーの順位。{@link LayerTree#UNLAYERED}（最大値）は層に入っていない宣言
     */
    public record Specificity(CascadeOrigin origin, boolean important, int layer, int id, int clazz, int type)
            implements Comparable<Specificity> {

        /** 層に入っていない宣言の詳細度。 */
        public Specificity(CascadeOrigin origin, boolean important, int id, int clazz, int type) {
            this(origin, important, LayerTree.UNLAYERED, id, clazz, type);
        }

        /** セレクタ文字列から詳細度を計算する（オリジンは作者スタイル）。 */
        public static Specificity calculate(String selector) {
            return calculate(selector, CascadeOrigin.AUTHOR);
        }

        /** セレクタ文字列から詳細度を計算する。 */
        public static Specificity calculate(String selector, CascadeOrigin origin) {
            return SelectorParser.parse(selector, origin, false).specificity();
        }

        public Specificity withOrigin(CascadeOrigin newOrigin) {
            return new Specificity(newOrigin, important, layer, id, clazz, type);
        }

        public Specificity withImportant(boolean newImportant) {
            return new Specificity(origin, newImportant, layer, id, clazz, type);
        }

        public Specificity withLayer(int newLayer) {
            return new Specificity(origin, important, newLayer, id, clazz, type);
        }

        @Override
        public int compareTo(Specificity other) {
            if (this.important != other.important) return this.important ? 1 : -1;
            if (this.origin != other.origin) {
                // !important 同士ではオリジンの順が反転し、UA の !important が最強になる。
                // style 属性は作者の層に数えるので、作者の !important の中で最強になる。
                if (this.important && this.origin.tier() != other.origin.tier()) {
                    return Integer.compare(other.origin.tier(), this.origin.tier());
                }
                return Integer.compare(this.origin.rank(), other.origin.rank());
            }
            if (this.layer != other.layer) {
                return this.important ? Integer.compare(other.layer, this.layer) : Integer.compare(this.layer, other.layer);
            }
            if (this.id != other.id) return Integer.compare(this.id, other.id);
            if (this.clazz != other.clazz) return Integer.compare(this.clazz, other.clazz);
            return Integer.compare(this.type, other.type);
        }
    }

    /**
     * カスケードのオリジン。
     * 以前は {@code specificity += 10000} というマジック定数で表現されていたものを型にしたもの。
     * 宣言順が同じなら、オリジンの強い側が勝つ。
     */
    public enum CascadeOrigin {
        /** UA スタイルシート（ライブラリ組み込みの既定値）。最弱。 */
        USER_AGENT(0, 0),
        /** ユーザースタイルシート（利用者が持ち込む上書き）。UA より強く、作者より弱い。 */
        USER(1, 1),
        /** 作者スタイルシート（HTML の {@code <style>} やテーマ）。 */
        AUTHOR(2, 2),
        /** 要素の {@code style="..."} 属性。作者のスタイルの中で最強。 */
        INLINE(3, 2);

        private final int rank;
        private final int tier;

        CascadeOrigin(int rank, int tier) {
            this.rank = rank;
            this.tier = tier;
        }

        /** 通常の宣言の強さの順。 */
        public int rank() {
            return rank;
        }

        /** {@code !important} の反転で数える段（UA / ユーザー / 作者。style 属性は作者と同じ段）。 */
        public int tier() {
            return tier;
        }
    }

    /**
     * 規則を右端の複合セレクタの id / class / タグで振り分けた索引。
     *
     * 何が壊れていたか: 以前は要素ごとにシートの全規則を順に照合していたので、規則が増えると要素数に比例して遅くなった。
     * ここでは、要素が持つ id・class・タグに当たる箱の規則だけを候補にする（どれにも当たらない規則は
     * 「全称」の箱に入り、毎回候補になる）。候補はカスケード順を保って返す。
     */
    public static final class RuleIndex {
        private final List<StyleRule> rules;
        private final Map<String, int[]> byId = new HashMap<>();
        private final Map<String, int[]> byClass = new HashMap<>();
        private final Map<String, int[]> byTag = new HashMap<>();
        private final int[] universal;

        private RuleIndex(List<StyleRule> rules) {
            this.rules = List.copyOf(rules);
            Map<String, List<Integer>> ids = new HashMap<>();
            Map<String, List<Integer>> classes = new HashMap<>();
            Map<String, List<Integer>> tags = new HashMap<>();
            List<Integer> rest = new ArrayList<>();
            for (int i = 0; i < this.rules.size(); i++) {
                Selector.SelectorToken subject = this.rules.get(i).parsedSelector().subject();
                if (subject.id != null) {
                    ids.computeIfAbsent(subject.id, k -> new ArrayList<>()).add(i);
                } else if (!subject.classes.isEmpty()) {
                    classes.computeIfAbsent(subject.classes.getFirst(), k -> new ArrayList<>()).add(i);
                } else if (!"*".equals(subject.tag)) {
                    tags.computeIfAbsent(subject.tag, k -> new ArrayList<>()).add(i);
                } else {
                    rest.add(i);
                }
            }
            ids.forEach((k, v) -> byId.put(k, toArray(v)));
            classes.forEach((k, v) -> byClass.put(k, toArray(v)));
            tags.forEach((k, v) -> byTag.put(k, toArray(v)));
            universal = toArray(rest);
        }

        static RuleIndex build(List<StyleRule> rules) {
            return new RuleIndex(rules);
        }

        private static int[] toArray(List<Integer> list) {
            int[] out = new int[list.size()];
            for (int i = 0; i < out.length; i++) out[i] = list.get(i);
            return out;
        }

        /** {@code node} に当たり得る規則を、カスケード順（弱い順）で返す。照合はしていない。 */
        public List<StyleRule> candidates(HtmlNode node) {
            if (node == null) return List.of();
            int[] gathered = new int[16];
            int count = 0;
            int[][] groups = new int[4][];
            int groupCount = 0;
            groups[groupCount++] = universal;
            int[] tag = byTag.get(node.tag);
            if (tag != null) groups[groupCount++] = tag;
            String id = node.attrs.get("id");
            int[] idRules = id == null || id.isEmpty() ? null : byId.get(id);
            if (idRules != null) groups[groupCount++] = idRules;

            List<int[]> classGroups = null;
            String classAttr = node.attrs.get("class");
            if (classAttr != null && !classAttr.isBlank() && !byClass.isEmpty()) {
                for (String name : classAttr.trim().split("\\s+")) {
                    int[] bucket = byClass.get(name);
                    if (bucket == null) continue;
                    if (classGroups == null) classGroups = new ArrayList<>();
                    classGroups.add(bucket);
                }
            }

            for (int g = 0; g < groupCount; g++) {
                gathered = ensureCapacity(gathered, count + groups[g].length);
                System.arraycopy(groups[g], 0, gathered, count, groups[g].length);
                count += groups[g].length;
            }
            if (classGroups != null) {
                for (int[] bucket : classGroups) {
                    gathered = ensureCapacity(gathered, count + bucket.length);
                    System.arraycopy(bucket, 0, gathered, count, bucket.length);
                    count += bucket.length;
                }
            }
            java.util.Arrays.sort(gathered, 0, count);
            List<StyleRule> out = new ArrayList<>(count);
            int previous = -1;
            for (int i = 0; i < count; i++) {
                // 同じ要素が同じ class を 2 回書いても（class="a a"）規則は 1 回だけ数える
                if (gathered[i] == previous) continue;
                previous = gathered[i];
                out.add(rules.get(gathered[i]));
            }
            return out;
        }

        private static int[] ensureCapacity(int[] array, int needed) {
            return needed <= array.length ? array : java.util.Arrays.copyOf(array, Math.max(needed, array.length * 2));
        }

        public int size() {
            return rules.size();
        }
    }

    /**
     * {@code @layer} の木。層の順は「最初に名前が現れた順」で決まり（入れ子は親の中で）、
     * 親の層に直接書かれた規則はその子の層より後ろ（強い側）に来る。
     * 順位は後順の通し番号で、層に入っていない規則だけが {@link #UNLAYERED}（最大値）を持つ。
     */
    public static final class LayerTree {

        /** 層に入っていない規則の順位。 */
        public static final int UNLAYERED = Integer.MAX_VALUE;

        private static final class Node {
            final Map<String, Node> children = new LinkedHashMap<>();
        }

        private final Node root = new Node();
        private int anonymousCount;
        /** 道筋 → 後順の通し番号。層が増えたら作り直す。 */
        private Map<List<String>, Integer> ranks;

        /** 層の名前の道筋を登録する（途中の層も登録される）。既にあれば順は変わらない。 */
        public void declare(List<String> path) {
            Node node = root;
            for (String name : path) {
                Node child = node.children.get(name);
                if (child == null) {
                    child = new Node();
                    node.children.put(name, child);
                    ranks = null;
                }
                node = child;
            }
        }

        /** 名前を持たない層の道筋用の、重ならない名前を作る。 */
        public String newAnonymousName() {
            return "\u0000anonymous-" + anonymousCount++;
        }

        public boolean isEmpty() {
            return root.children.isEmpty();
        }

        /** 道筋の順位。空の道筋（層に入っていない）は {@link #UNLAYERED}。登録されていない道筋は最強の層の次。 */
        public int rankOf(List<String> path) {
            if (path.isEmpty()) return UNLAYERED;
            if (ranks == null) {
                ranks = new LinkedHashMap<>();
                number(root, List.of(), ranks);
            }
            Integer rank = ranks.get(path);
            return rank != null ? rank : ranks.size();
        }

        /** 後順で番号を振る。子の層を先に、その親の層を後に数えるので、親に直接書いた規則が子の層より強くなる。 */
        private static void number(Node node, List<String> path, Map<List<String>, Integer> out) {
            for (Map.Entry<String, Node> child : node.children.entrySet()) {
                List<String> childPath = new ArrayList<>(path);
                childPath.add(child.getKey());
                number(child.getValue(), childPath, out);
                out.put(List.copyOf(childPath), out.size());
            }
        }

        /** 別の木の層を、順を保ってこの木の後ろへ足す（既にある名前は元の位置のまま）。 */
        public void merge(LayerTree other) {
            mergeNode(root, other.root);
            anonymousCount = Math.max(anonymousCount, other.anonymousCount);
            ranks = null;
        }

        private static void mergeNode(Node into, Node from) {
            for (Map.Entry<String, Node> e : from.children.entrySet()) {
                mergeNode(into.children.computeIfAbsent(e.getKey(), k -> new Node()), e.getValue());
            }
        }
    }
}
