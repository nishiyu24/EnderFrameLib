package com.nishiyu.enderframelib.core.dom.node;

import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * 変更台帳（K5b。設計書 {@code core/DESIGN_INCREMENTAL_KERNEL.md} §3.6）。DOM・対話状態の変更を<b>追記するだけ</b>の列で、
 * 箱にも節にも欄を足さない。フレームの始めに {@link #freeze} で凍結し（世代が進む）、各段はその世代の変更を読む:
 * 種別ごとの変更の節は {@link #frozenAt}、「この節の部分木の下に変更があるか」は {@link #touches}〔凍結した台帳の節から祖先を
 * 辿った集合を、世代ごとに 1 回だけ作る〕。持ち越すフラグ〔{@code DIRTY_BELOW} のような祖先への伝播〕は持たない。
 *
 * <p>要素は（節, 種別, 詳細）。{@code detail} は {@link Kind#ATTR} の属性名。節が null の要素〔{@link Kind#ENV}・{@link Kind#SCROLL}・
 * 絞れない {@link Kind#STYLE}〕は全体に効く。追記は同期した O(1)。個々を持ちきれない〔{@link #MAX_ENTRIES}〕ときは
 * 個々を捨てて「あふれ」にする〔読み手は個々の変更に頼らず、全体を作り直す側へ退く〕。</p>
 */
public final class ChangeLedger {
    /** 変更の種別。 */
    public enum Kind { TEXT, CHILDREN, ATTR, STYLE, ENV, SCROLL }

    /** これを超えたら個々の記録をやめて「あふれ」にする。 */
    public static final int MAX_ENTRIES = 4096;

    private HtmlNode[] nodes = new HtmlNode[16];
    private byte[] kinds = new byte[16];
    private String[] details = new String[16];
    private int count;
    private boolean overflow;
    private long epoch;
    private HtmlNode[] fNodes = new HtmlNode[16];
    private byte[] fKinds = new byte[16];
    private String[] fDetails = new String[16];
    private int fCount;
    private boolean fOverflow, fNodeless;
    private Set<HtmlNode> touched;

    /** 追記する。{@code node} が null の変更は全体に効く。{@code detail} は属性名など〔無ければ null〕。 */
    public void append(HtmlNode node, Kind kind) { append(node, kind, null); }

    public synchronized void append(HtmlNode node, Kind kind, String detail) {
        if (overflow) return;
        if (count > 0 && nodes[count - 1] == node && kinds[count - 1] == kind.ordinal() && java.util.Objects.equals(details[count - 1], detail)) return;
        if (count == MAX_ENTRIES) { overflow = true; Arrays.fill(nodes, 0, count, null); Arrays.fill(details, 0, count, null); count = 0; return; }
        if (count == nodes.length) {
            nodes = Arrays.copyOf(nodes, count * 2);
            kinds = Arrays.copyOf(kinds, count * 2);
            details = Arrays.copyOf(details, count * 2);
        }
        nodes[count] = node;
        kinds[count] = (byte) kind.ordinal();
        details[count++] = detail;
    }

    /** 節を絞れない変更〔全ての節の再スタイルなど〕。 */
    public void appendWhole() { append(null, Kind.STYLE, null); }

    /** 凍結して世代を進める。何も無い静止フレームは O(1) で何もしない。配列は入れ替えるだけで確保しない。 */
    public synchronized long freeze() {
        if (count == 0 && !overflow && fCount == 0 && !fOverflow) return epoch;
        Arrays.fill(fNodes, 0, fCount, null); // 前の世代の参照を手放す
        Arrays.fill(fDetails, 0, fCount, null);
        HtmlNode[] n = fNodes; byte[] k = fKinds; String[] d = fDetails;
        fNodes = nodes; fKinds = kinds; fDetails = details;
        nodes = n; kinds = k; details = d;
        fCount = count;
        fOverflow = overflow;
        fNodeless = false;
        for (int i = 0; i < fCount && !fNodeless; i++) fNodeless = fNodes[i] == null;
        touched = null;
        count = 0;
        overflow = false;
        return ++epoch;
    }

    /** 今の世代（変更のあったフレームの数）。 */
    public synchronized long epoch() { return epoch; }

    /** 凍結した世代が、個々を持ちきれず「あふれ」たか。読み手は個々の変更に頼らず、全体を作り直す側へ退く。 */
    public synchronized boolean frozenOverflow() { return fOverflow; }

    /** 凍結した世代の要素の数（あふれたときは 0）。 */
    public synchronized int frozenSize() { return fCount; }

    /** 凍結した世代の {@code i} 番目の節（節を絞れない要素は null）。 */
    public synchronized HtmlNode frozenNode(int i) { return fNodes[i]; }

    /** 凍結した世代の {@code i} 番目が、この種別か。 */
    public synchronized boolean frozenIs(int i, Kind kind) { return fKinds[i] == kind.ordinal(); }

    /** 凍結した世代の {@code i} 番目の詳細〔{@link Kind#ATTR} の属性名。無ければ null〕。 */
    public synchronized String frozenDetail(int i) { return fDetails[i]; }

    /**
     * 凍結した世代で、{@code parent} の直接の子のうち、自分か子孫に変更がある子（同一性）。小さい集合を毎回作る
     * 〔変更数 × 深さ〕。全体に効く世代〔あふれ・節を絞れない要素〕は null。
     */
    public synchronized Set<HtmlNode> touchedChildren(HtmlNode parent) {
        if (fOverflow || fNodeless) return null;
        Set<HtmlNode> out = Collections.newSetFromMap(new IdentityHashMap<>(8));
        for (int i = 0; i < fCount; i++) {
            for (HtmlNode cur = fNodes[i]; cur != null; cur = cur.parent) {
                if (cur.parent == parent) { out.add(cur); break; }
            }
        }
        return out;
    }

    /** 凍結した世代に、節を絞れない要素〔{@link Kind#ENV}・{@link Kind#SCROLL}・絞れない {@link Kind#STYLE}〕があるか。 */
    public synchronized boolean frozenNodeless() { return fNodeless; }

    /** 凍結した世代に、この種別の要素が 1 つでもあるか。 */
    public synchronized boolean frozenHas(Kind kind) {
        for (int i = 0; i < fCount; i++) if (fKinds[i] == kind.ordinal()) return true;
        return false;
    }

    /**
     * 凍結した世代で、この節自身か子孫に変更があるか。あふれた世代・節を絞れない変更がある世代は常に true。
     * 集合は世代ごとに最初の問い合わせで 1 回だけ作る〔変更数 × 深さ〕。
     */
    public synchronized boolean touches(HtmlNode node) {
        if (fOverflow || fNodeless) return true;
        if (fCount == 0) return false;
        Set<HtmlNode> set = touched;
        if (set == null) {
            set = Collections.newSetFromMap(new IdentityHashMap<>(fCount * 4));
            for (int i = 0; i < fCount; i++) {
                for (HtmlNode cur = fNodes[i]; cur != null && set.add(cur); cur = cur.parent) { /* 祖先は既出なら以降も既出 */ }
            }
            touched = set;
        }
        return set.contains(node);
    }

    /** 凍結前の追記の数（観測・テスト用。あふれたときは {@link Integer#MAX_VALUE}）。 */
    public synchronized int pendingCount() { return overflow ? Integer.MAX_VALUE : count; }

    /** 凍結前の追記に、この節のこの種別があるか（観測・テスト用。あふれたときは常に true）。 */
    public synchronized boolean pendingHas(HtmlNode node, Kind kind) {
        if (overflow) return true;
        for (int i = 0; i < count; i++) if (nodes[i] == node && kinds[i] == kind.ordinal()) return true;
        return false;
    }

    /** 凍結前の追記に、この節の変更が種別を問わずあるか（観測・テスト用）。節を絞れない変更があれば常に true。 */
    public synchronized boolean pendingTouches(HtmlNode node) {
        if (overflow) return true;
        for (int i = 0; i < count; i++) if (nodes[i] == node || nodes[i] == null) return true;
        return false;
    }

    /** 凍結前の追記の種別の並び（観測・テスト用）。 */
    public synchronized Kind[] pendingKinds() {
        Kind[] all = Kind.values(), out = new Kind[count];
        for (int i = 0; i < count; i++) out[i] = all[kinds[i]];
        return out;
    }
}
