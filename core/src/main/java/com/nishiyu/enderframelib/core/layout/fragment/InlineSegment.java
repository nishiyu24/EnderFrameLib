package com.nishiyu.enderframelib.core.layout.fragment;

import com.nishiyu.enderframelib.core.box.BoxKind;
import com.nishiyu.enderframelib.core.box.BoxNode;
import com.nishiyu.enderframelib.core.layout.fragment.InlineSpans.Open;

import java.util.ArrayList;
import java.util.List;

/**
 * インラインの中のブロック（CSS 2.1 §9.2.1.1。G1）で割ったインラインの内容の 1 区間（{@link InlineLineBuilder} の作業域）。
 *
 * <p>インライン要素の中にブロック水準の箱（流れの中にあるもの。float と流れの外の箱は除く）があると、インラインの内容はその箱の前後で
 * 割れ、ブロックは持ち主のブロックコンテナの流れに兄弟として並ぶ（{@link BlockFlow} が置く）。割れた前後は匿名のブロックの行になる。
 * 区間 {@code k} は {@code k - 1} 番目と {@code k} 番目のブロックの間の内容で、行を組む側は同じ並びを先頭から辿り、
 * 区間の外の内容を飛ばす。区間の頭で開いているインライン要素は開始端の無い続きとして開き、区間の終わりで開いている要素は
 * 終了端の無い分として閉じる（行の折り返しで割れた箱と同じ）。</p>
 */
final class InlineSegment {
    /** 飛ばしている間に入ったインライン要素と、区間の頭で続きとして開いた箱（開くまでは null）。 */
    static final class Entry {
        final BoxNode box;
        Open open;

        Entry(BoxNode box) {
            this.box = box;
        }
    }

    private final int segment;
    private final List<Entry> skipped = new ArrayList<>();
    private int seen;
    private boolean stopped;

    InlineSegment(int segment) {
        this.segment = segment;
    }

    /** 組む区間の番号（0 は最初のブロックの前）。 */
    int index() {
        return segment;
    }

    /** 今の位置が組む区間の中か。 */
    boolean active() {
        return seen == segment && !stopped;
    }

    /** 区間の終わり（次のブロック）に達したか（以後の内容は辿らない）。 */
    boolean stopped() {
        return stopped;
    }

    /**
     * ブロックに達した。区間がここから始まるなら true（呼ぶ側が {@link #skipped()} の箱を続きとして開く）。区間がここで終わるなら
     * {@link #stopped()} になる。
     */
    boolean boundary() {
        int j = seen++;
        if (j == segment) {
            seen = j;
            stopped = true;
            return false;
        }
        return j == segment - 1;
    }

    /** 飛ばしている間に入ったインライン要素（外側が先）。 */
    List<Entry> skipped() {
        return skipped;
    }

    Entry push(BoxNode box) {
        Entry e = new Entry(box);
        skipped.add(e);
        return e;
    }

    void pop() {
        skipped.removeLast();
    }

    /** 行を割るブロック（インラインの内容の中にある、流れの中のブロック水準の箱）か。 */
    static boolean splits(BoxNode n) {
        return n.kind.blockLevel() && !n.style.facts.floating && !OutOfFlow.is(n);
    }

    /**
     * 行を組む側がインライン要素として中へ降りる箱か（文字・原子・行を割るブロック・流れの外の箱でないもの。インラインの中の float も
     * 今は中へ降りる）。{@link #collect} と {@link InlineLineBuilder} が同じ規則で辿るので、区間の数が食い違わない。
     */
    static boolean descends(BoxNode k) {
        return k.kind != BoxKind.NONE && k.kind != BoxKind.TEXT && !k.kind.atomicInline() && !OutOfFlow.is(k) && !splits(k);
    }

    /** インライン要素 {@code n} の内容にある行を割るブロックを、文書の順に {@code out} へ足す。 */
    static void collect(BoxNode n, List<BoxNode> out) {
        for (int i = 0, c = n.flow.size(); i < c; i++) {
            BoxNode k = n.flow.get(i);
            if (k.kind == BoxKind.NONE || OutOfFlow.is(k)) continue;
            if (splits(k)) out.add(k);
            else if (descends(k)) collect(k, out);
        }
    }
}
