package com.nishiyu.enderframelib.core.css;

import com.nishiyu.enderframelib.core.css.StyleResolver.Shared;
import com.nishiyu.enderframelib.core.css.StyleSheet.StyleRule;
import com.nishiyu.enderframelib.core.css.model.*;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.node.NodeType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
public final class ShareCache {
    private final Map<ShareKey, Shared> map = new HashMap<>();
    private final ShareKey probe = new ShareKey();
    private StyleSheet sheet;
    private Map<String, String> env;
    private int hits, misses;
    private int sheetVersion = -1;
    private static final int MAX_ENTRIES = 4096;
    /** 切り分け用: false なら使い回さず従来の経路で解く（結果は同じになるはず）。 */
    static volatile boolean enabled = true;
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
    public Shared resolve(HtmlNode node, Style parent, StyleSheet sheet, StateLookup lookup,
                          Map<String, String> env, float parentFontSize, float rootFontSize, float rootLineHeight,
                          int viewportW, int viewportH, ContainerBox container) {
        if (!enabled || node == null || sheet == null || lookup == null || parent == null) return null;
        if (node.attrs.containsKey("style") || node.inlineOverlay != null || node.attrs.containsKey("hidden")
                || com.nishiyu.enderframelib.core.dom.node.ElementTraits.isInputTag(node.tag)) return null;
        int version = sheet.rules.version();
        // 再スタイルをまたいで残すので、シート・環境・規則の版が変わったら捨てる。件数にも上限を置く
        if (this.sheet != sheet || this.env != env || this.sheetVersion != version || map.size() >= MAX_ENTRIES) {
            map.clear();
            this.sheet = sheet;
            this.env = env;
            this.sheetVersion = version;
        }
        // テキストの節はセレクタの対象ではない（継承値だけを持つ）。* > など要素向けの規則の width が空白の箱に掛かっていた
        List<StyleRule> candidates = node.nodeType == NodeType.TEXT ? List.of() : sheet.index().candidates(node);
        ShareKey key = probe;
        key.begin(candidates.size());
        for (int i = 0, size = candidates.size(); i < size; i++) {
            StyleRule rule = candidates.get(i);
            if (!StyleResolver.targets(rule, null) || !rule.matches(node, lookup) || !StyleResolver.isConditionMet(rule, node, null, lookup)) continue;
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
        Map<String, String> raw = StyleResolver.cascadeMatched(parent, sheet, key.rules, key.n);
        boolean viewportDependent = StyleResolver.rawDependsOnViewport(raw);
        ComputedStyle computed = StyleResolver.finalizeComputedStyleOwned(raw, sheet, env, parent, parentFontSize, rootFontSize,
                rootLineHeight, viewportW, viewportH, container);
        Shared shared = new Shared(computed, viewportDependent);
        map.put(key.copy(), shared);
        return shared;
    }

    /** {@link ShareCache} の鍵。規則は同一性で比べる。照会用の 1 個を使い回し、見つからなかったときだけ複製する。 */
    private static final class ShareKey {
        StyleRule[] rules = new StyleRule[16];
        int n;
        Style parent;
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

        void finish(Style parent, float pfs, float rfs, float rlh, int vw, int vh, float cw, float ch) {
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
}
