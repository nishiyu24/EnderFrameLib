package com.nishiyu.enderframelib.core.kernel.compiler;

import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.SelectorMatcher;
import com.nishiyu.enderframelib.core.css.StyleSheet;
import com.nishiyu.enderframelib.core.css.model.ElementState;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import java.util.function.IntFunction;

/**
 * 対話状態（:hover / :active / :focus*）の AOT 解析と、状態 → 算出スタイルの遷移表。
 *
 * <ol>
 *   <li>{@link #interactiveMask}: 要素にマッチし得る規則から、結果を変え得る状態ビットの集合を求める。
 *       シートの全規則ではなく {@link StyleSheet#interactiveRules()}（対話規則だけ）を見る。</li>
 *   <li>{@link StateTable}: マスクの立つビットだけを詰めた添字（最大 2^5 通り）で、状態の組み合わせごとの
 *       {@link ComputedStyle} を引く小配列。状態が変わったときは、セレクタ照合もカスケードもせず、
 *       添字を引くだけ。変化の種類（{@link #PAINT_ONLY} / {@link #LAYOUT}）も遷移ごとに 1 回だけ求めて覚える。</li>
 * </ol>
 */
public final class StyleStateAutomaton {
    private StyleStateAutomaton() {}

    /** 状態が変わっても何も変わらない。 */
    public static final byte NONE = 0;
    /** 描画だけを描き直せば済む。 */
    public static final byte PAINT_ONLY = 1;
    /** 寸法・配置に効くのでレイアウトからやり直す。 */
    public static final byte LAYOUT = 2;

    /**
     * {@code node} の対話状態によって照合結果が変わり得る {@link ElementState} のビット集合。0 なら状態に依存しない。
     * セレクタの左側（祖先・先行する兄弟）は状態を無視して照合する（保守的）。
     */
    public static int interactiveMask(HtmlNode node, StyleSheet sheet) {
        if (node == null || sheet == null) return 0;
        int mask = 0;
        for (var rule : sheet.interactiveRules()) {
            var selector = rule.parsedSelector();
            var compounds = selector.compounds();
            for (int i = 0; i < compounds.size(); i++) {
                int m = compounds.get(i).dependentStateMask() & ElementState.INTERACTIVE_MASK;
                if (m != 0 && (mask & m) != m && SelectorMatcher.matchesPrefixIgnoringState(selector, i, node)) {
                    mask |= m;
                }
            }
        }
        return mask;
    }

    /**
     * {@code node} の算出スタイルが「自身の状態」だけで決まるか。対象に当たり得る状態つき規則（左側は状態を無視して照合）が、
     * 末尾以外の複合セレクタに状態を持たず、{@code :has()} も使わなければ真。
     * （シート全体の {@link StyleSheet#stateOnSubjectOnly()} より細かく、無関係な規則が表の利用を妨げない）
     */
    public static boolean stateOnlyOnSubject(HtmlNode node, StyleSheet sheet) {
        if (node == null || sheet == null) return false;
        for (var rule : sheet.rules) {
            if (rule.stateMask == 0) continue;
            var selector = rule.parsedSelector();
            var compounds = selector.compounds();
            int last = compounds.size() - 1;
            if (!SelectorMatcher.matchesPrefixIgnoringState(selector, last, node)) continue;
            if (selector.usesHas()) return false;
            for (int i = 0; i < last; i++) {
                if (compounds.get(i).dependentStateMask() != 0) return false;
            }
        }
        return true;
    }

    /** 状態ビットのうち {@code mask} の立つ位置だけを詰めた添字（PEXT 相当）。 */
    public static int compress(int stateBits, int mask) {
        int out = 0, k = 0;
        for (int m = mask; m != 0; m &= m - 1) {
            int bit = m & -m;
            if ((stateBits & bit) != 0) out |= 1 << k;
            k++;
        }
        return out;
    }

    /** 状態の組み合わせごとの算出スタイルと、遷移ごとの変化の種類。要素ごとに 1 つ。 */
    public static final class StateTable {
        private final int mask;
        private final ComputedStyle[] styles;
        private final byte[] kinds; // [from * n + to]、未計算は -1
        private final Object[] attachments;
        private final IntFunction<ComputedStyle> resolver;

        /**
         * @param mask     {@link #interactiveMask} の結果（0 でない）
         * @param resolver 状態ビット（{@link ElementState} のフラグ。{@code mask} の部分集合）から算出スタイルを作る。
         *                 組み合わせは初めて必要になるまで呼ばれない。
         */
        public StateTable(int mask, IntFunction<ComputedStyle> resolver) {
            if (mask == 0) throw new IllegalArgumentException("mask == 0 の要素に表は要らない");
            this.mask = mask;
            int n = 1 << Integer.bitCount(mask);
            this.styles = new ComputedStyle[n];
            this.attachments = new Object[n];
            this.kinds = new byte[n * n];
            java.util.Arrays.fill(kinds, (byte) -1);
            this.resolver = resolver;
        }

        public int mask() { return mask; }

        public int size() { return styles.length; }

        /** 解いた組み合わせの数。 */
        public int resolvedCount() {
            int c = 0;
            for (ComputedStyle s : styles) if (s != null) c++;
            return c;
        }

        /** 状態に対応する算出スタイル（初回だけ {@code resolver} を呼ぶ）。{@code mask} 外のビットは無視する。 */
        public ComputedStyle style(int stateBits) {
            int idx = compress(stateBits, mask);
            ComputedStyle s = styles[idx];
            if (s == null) styles[idx] = s = resolver.apply(expand(idx));
            return s;
        }

        /** 状態の組み合わせに結びつけた付属物（解決時に一緒に作った生宣言など）。無ければ null。 */
        public Object attachment(int stateBits) { return attachments[compress(stateBits, mask)]; }

        public void attach(int stateBits, Object value) { attachments[compress(stateBits, mask)] = value; }

        /** 詰めた添字を元の状態ビットに戻す。 */
        private int expand(int idx) {
            int bits = 0, k = 0;
            for (int m = mask; m != 0; m &= m - 1) {
                if ((idx & (1 << k)) != 0) bits |= m & -m;
                k++;
            }
            return bits;
        }

        /** {@code from} → {@code to} の遷移で必要な無効化（{@link #NONE} / {@link #PAINT_ONLY} / {@link #LAYOUT}）。 */
        public byte changeKind(int fromBits, int toBits) {
            int f = compress(fromBits, mask), t = compress(toBits, mask);
            int slot = f * styles.length + t;
            byte k = kinds[slot];
            if (k >= 0) return k;
            k = classify(style(fromBits), style(toBits));
            kinds[slot] = k;
            return k;
        }

        private static byte classify(ComputedStyle a, ComputedStyle b) {
            if (a == b || ComputedStyle.sameDeclarations(a, b)) return NONE;
            byte[] result = {NONE};
            ComputedStyle.diff(a, b, (name, paintOnly, inherited) -> {
                if (!paintOnly) result[0] = LAYOUT;
                else if (result[0] == NONE) result[0] = PAINT_ONLY;
            });
            return result[0];
        }
    }
}
