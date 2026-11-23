package com.nishiyu.enderframelib.core.dom.node;

import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * 変更台帳（K5b L0。設計書 {@code core/DESIGN_INCREMENTAL_KERNEL.md} §3.6）。DOM・対話状態の変更を<b>追記するだけ</b>の列で、
 * 箱にも節にも欄を足さない。フレームの始めに {@link #freeze} で凍結し（世代が進む）、各段はその世代の変更を
 * {@link #touches}〔「この節の部分木の下に変更があるか」。凍結した台帳の節から祖先を辿った集合を、フレームごとに 1 回だけ作る〕で読む。
 * 持ち越すフラグ〔{@code DIRTY_BELOW} のような祖先への伝播〕は持たない。
 *
 * <p>L0 は観測だけ: 既存の入口〔{@code pendingTextNodes} ほか・汚れ印〕の変更が全て台帳に載ることを確かめる。挙動は変えない。
 * 追記は同期した O(1)。変更が多すぎる〔{@link #MAX_ENTRIES}〕ときは個々を捨てて「全体が触れられた」にする。</p>
 */
public final class ChangeLedger {
    /** 変更の種別。節を持たない {@link #ENV}・{@link #SCROLL} は全体に効く。 */
    public enum Kind { TEXT, CHILDREN, ATTR, STYLE, ENV, SCROLL }

    /** これを超えたら個々の記録をやめて全体が触れられたものとする。 */
    public static final int MAX_ENTRIES = 4096;

    private HtmlNode[] nodes = new HtmlNode[16];
    private byte[] kinds = new byte[16];
    private int count;
    private boolean whole;
    private long epoch;
    private HtmlNode[] frozenNodes = new HtmlNode[16];
    private int frozenCount;
    private boolean frozenWhole;
    private Set<HtmlNode> touched;

    /** 追記する。{@code node} が null の種別〔{@link Kind#ENV}・{@link Kind#SCROLL}〕と {@link #appendWhole} は全体に効く。 */
    public synchronized void append(HtmlNode node, Kind kind) {
        if (whole) return;
        if (node == null) { whole = true; return; }
        if (count > 0 && nodes[count - 1] == node && kinds[count - 1] == kind.ordinal()) return; // 同じ節の同じ変更が続くだけ
        if (count == MAX_ENTRIES) { whole = true; count = 0; Arrays.fill(nodes, null); return; }
        if (count == nodes.length) { nodes = Arrays.copyOf(nodes, count * 2); kinds = Arrays.copyOf(kinds, count * 2); }
        nodes[count] = node;
        kinds[count++] = (byte) kind.ordinal();
    }

    /** 全体が触れられた（節を絞れない変更）。 */
    public synchronized void appendWhole() { whole = true; }

    /** 凍結して世代を進める。何も無い静止フレームは O(1) で何もしない。配列は入れ替えるだけで確保しない。 */
    public synchronized long freeze() {
        if (count == 0 && !whole && frozenCount == 0 && !frozenWhole) return epoch;
        Arrays.fill(frozenNodes, 0, frozenCount, null); // 前の世代の参照を手放す
        HtmlNode[] swap = frozenNodes;
        frozenNodes = nodes;
        nodes = swap.length >= 16 ? swap : new HtmlNode[16];
        if (kinds.length < nodes.length) kinds = Arrays.copyOf(kinds, nodes.length);
        frozenCount = count;
        frozenWhole = whole;
        touched = null;
        count = 0;
        whole = false;
        return ++epoch;
    }

    /** 今の世代（凍結した回数ではなく、変更のあったフレームの数）。 */
    public synchronized long epoch() { return epoch; }

    /** 凍結していない（これから凍結する）追記の数。全体が触れられたときは {@link Integer#MAX_VALUE}。 */
    public synchronized int pendingCount() { return whole ? Integer.MAX_VALUE : count; }

    /** 凍結前の追記に、この節のこの種別があるか（観測・テスト用。全体が触れられたときは常に true）。 */
    public synchronized boolean pendingHas(HtmlNode node, Kind kind) {
        if (whole) return true;
        for (int i = 0; i < count; i++) if (nodes[i] == node && kinds[i] == kind.ordinal()) return true;
        return false;
    }

    /** 凍結前の追記に、この節の変更が種別を問わずあるか（観測・テスト用）。 */
    public synchronized boolean pendingTouches(HtmlNode node) {
        if (whole) return true;
        for (int i = 0; i < count; i++) if (nodes[i] == node) return true;
        return false;
    }

    /**
     * 凍結した世代で、この節自身か子孫に変更があるか。全体が触れられた世代は常に true。
     * 集合は世代ごとに最初の問い合わせで 1 回だけ作る〔変更数 × 深さ〕。
     */
    public synchronized boolean touches(HtmlNode node) {
        if (frozenWhole) return true;
        if (frozenCount == 0) return false;
        Set<HtmlNode> set = touched;
        if (set == null) {
            set = Collections.newSetFromMap(new IdentityHashMap<>(frozenCount * 4));
            for (int i = 0; i < frozenCount; i++) {
                for (HtmlNode cur = frozenNodes[i]; cur != null && set.add(cur); cur = cur.parent) { /* 祖先は既出なら以降も既出 */ }
            }
            touched = set;
        }
        return set.contains(node);
    }

    /** 凍結した世代の変更の数（全体が触れられたときは {@link Integer#MAX_VALUE}）。 */
    public synchronized int frozenCount() { return frozenWhole ? Integer.MAX_VALUE : frozenCount; }

    /** 凍結前の追記の種別の並び（観測・テスト用）。 */
    public synchronized Kind[] pendingKinds() {
        Kind[] all = Kind.values(), out = new Kind[count];
        for (int i = 0; i < count; i++) out[i] = all[kinds[i]];
        return out;
    }
}
