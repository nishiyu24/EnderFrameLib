package com.nishiyu.enderframelib.core.kernel.compiler;

import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.StyleSheet;
import com.nishiyu.enderframelib.core.css.model.ElementState;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import java.util.Map;
import java.util.function.IntFunction;

/**
 * 要素ごとの「状態 → 算出スタイル」の使い回し（{@link StyleStateAutomaton.StateTable} の持ち主）。
 *
 * <p>:hover / :active / :focus で再スタイルが起きたとき、継承元・シート・属性・ビューポートが前回と同じなら、
 * セレクタ照合もカスケードもせず、状態の組み合わせで表を引くだけで算出スタイルが得られる。入力が 1 つでも
 * 変われば別の「枠」を使う（古い結果を返さない）。</p>
 *
 * <p>親が :hover で見た目を切り替える子（テキストなど）は、継承元が 2 通りを往復する。枠を {@link #FRAMES} 個持ち、
 * 往復の両側をそれぞれ覚えておくので、往復のたびに表を作り直さない。</p>
 */
public final class StateStyleMemo {

    /** 確定した算出スタイルと、ビューポート寸法への依存の有無（生宣言の表は保持しない）。 */
    public record Entry(ComputedStyle computed, boolean viewportDependent) {}

    private static final int FRAMES = 4;

    /** 入力の鍵と、それに対する結果（表、または対話状態に依存しない単独の結果）。 */
    private final class Frame {
        StyleSheet sheet;
        int sheetVersion;
        Map<String, String> inherited;
        Map<String, String> env;
        float parentFontSize, rootFontSize, rootLineHeight;
        int viewportW, viewportH, attrVersion, childCount, baseBits, mask;
        /** 入力は鍵と一致するが、この要素は表を使えない（状態を祖先・兄弟から読む規則が当たる）。 */
        boolean blocked;
        StyleStateAutomaton.StateTable table;
        Entry single;

        boolean matches(HtmlNode node, StyleSheet sheet, Map<String, String> inherited, Map<String, String> env,
                        float pfs, float rfs, float rlh, int vw, int vh, int stateBits) {
            return this.sheet == sheet && sheetVersion == sheet.rules.version()
                    && (this.inherited == inherited || (this.inherited != null && this.inherited.equals(inherited)))
                    && (this.env == env || (this.env != null && this.env.equals(env)))
                    && Float.compare(parentFontSize, pfs) == 0 && Float.compare(rootFontSize, rfs) == 0
                    && Float.compare(rootLineHeight, rlh) == 0 && viewportW == vw && viewportH == vh
                    && attrVersion == node.attrVersion() && childCount == node.children.size()
                    && baseBits == (stateBits & ~ElementState.INTERACTIVE_MASK);
        }
    }

    private final Frame[] frames = new Frame[FRAMES];
    private int next;
    private Frame current;
    private IntFunction<Entry> pending;

    private final IntFunction<ComputedStyle> resolver = bits -> {
        Entry e = pending.apply(bits | current.baseBits);
        current.table.attach(bits, e);
        return e.computed();
    };

    /** 表を引いた回数・枠を作った回数（診断・テスト用）。 */
    public int hits, builds;

    /**
     * @param eligible 呼び出し側が確かめた使用条件（コンテナクエリ無し・{@code :has()} 無し…）
     * @param stateBits 要素自身の現在の状態（{@code ElementState} のビット）
     * @param compute  状態ビットから、通常の経路で {@link Entry} を作る関数
     */
    /**
     * 解決済みの結果があればそれを返し、無ければ（計算が要るなら）null。クロージャを作らず割り当て 0 で引くための入口で、
     * 返したときは {@link #resolve} と同じ結果・同じ統計（{@link #hits}）になる。
     */
    public Entry peek(HtmlNode node, StyleSheet sheet, Map<String, String> inherited, Map<String, String> env,
                      float parentFontSize, float rootFontSize, float rootLineHeight, int viewportW, int viewportH,
                      int stateBits) {
        if (sheet == null) return null;
        for (Frame c : frames) {
            if (c != null && c.matches(node, sheet, inherited, env, parentFontSize, rootFontSize, rootLineHeight,
                    viewportW, viewportH, stateBits)) {
                if (c.blocked) return null;
                Entry e = c.mask == 0 ? c.single : (Entry) c.table.attachment(stateBits);
                if (e != null) hits++;
                return e;
            }
        }
        return null;
    }

    public Entry resolve(HtmlNode node, StyleSheet sheet, Map<String, String> inherited, Map<String, String> env,
                         float parentFontSize, float rootFontSize, float rootLineHeight, int viewportW, int viewportH,
                         int stateBits, boolean eligible, IntFunction<Entry> compute) {
        if (!eligible || sheet == null) {
            invalidate();
            return compute.apply(stateBits);
        }
        Frame f = null;
        for (Frame c : frames) {
            if (c != null && c.matches(node, sheet, inherited, env, parentFontSize, rootFontSize, rootLineHeight,
                    viewportW, viewportH, stateBits)) {
                f = c;
                break;
            }
        }
        if (f == null) {
            f = new Frame();
            int m = StyleStateAutomaton.interactiveMask(node, sheet);
            f.sheet = sheet;
            f.sheetVersion = sheet.rules.version();
            f.inherited = inherited;
            f.env = env;
            f.parentFontSize = parentFontSize;
            f.rootFontSize = rootFontSize;
            f.rootLineHeight = rootLineHeight;
            f.viewportW = viewportW;
            f.viewportH = viewportH;
            f.attrVersion = node.attrVersion();
            f.childCount = node.children.size();
            f.mask = m;
            f.baseBits = stateBits & ~ElementState.INTERACTIVE_MASK; // 対話ビットは mask の内側だけが結果に効く
            f.blocked = !StyleStateAutomaton.stateOnlyOnSubject(node, sheet);
            if (!f.blocked && m != 0) f.table = new StyleStateAutomaton.StateTable(m, resolver);
            frames[next] = f;
            next = (next + 1) % FRAMES;
            builds++;
        } else {
            hits++;
        }
        if (f.blocked) return compute.apply(stateBits);
        if (f.mask == 0) {
            // 対話状態を読む規則が当たらない: 入力が同じ間は結果も同じ
            Entry e = f.single;
            if (e == null) f.single = e = compute.apply(stateBits);
            return e;
        }
        current = f;
        pending = compute;
        try {
            // 状態ビットはマスクの内側だけが表の添字になる。外側（checked など）は baseBits と一致している
            f.table.style(stateBits);
            return (Entry) f.table.attachment(stateBits);
        } finally {
            pending = null;
            current = null;
        }
    }

    /** 覚えている結果をすべて捨てる（表を使えない経路で再スタイルされたとき、あとで古い結果を返さないため）。 */
    public void invalidate() {
        java.util.Arrays.fill(frames, null);
        next = 0;
    }

    /** いずれかの枠が状態の表を持っているか。 */
    public boolean hasTable() {
        for (Frame f : frames) if (f != null && f.table != null) return true;
        return false;
    }

    /** 状態に依存しない要素の結果を使い回しているか。 */
    public boolean isStateless() {
        for (Frame f : frames) if (f != null && !f.blocked && f.mask == 0) return true;
        return false;
    }
}
