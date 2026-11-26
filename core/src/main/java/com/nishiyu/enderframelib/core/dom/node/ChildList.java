package com.nishiyu.enderframelib.core.dom.node;

import com.nishiyu.enderframelib.core.kernel.CompactList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * 子の並び。直接 {@code add} / {@code remove} する呼び出し（パーサ・バインディング）も、
 * DOM のツリー操作と同じく id 索引・番号・セレクタの索引へ反映するため、変更の入口をここに集める。
 *
 * <p>子の並びが木の形の唯一の正本で、各子の位置（{@link HtmlNode#siblingIndex}）も変更のたびにここで保つ
 * （M5-1。前後の兄弟・{@link #indexOf} が O(1) になる）。変更は {@link CompactList} の基本の操作へ直接降ろし、
 * 自分の上書きを二重に通らないようにする（以前は {@code remove(Object)}・{@code removeIf} が節を 2 回外していた）。</p>
 */
final class ChildList extends CompactList<HtmlNode> {

    /** 子の並びの持ち主（A8 で {@code HtmlNode} の内部クラスから分けた。以前の暗黙の外側参照と同じ 1 欄）。 */
    private final HtmlNode owner;

    ChildList(HtmlNode owner) {
        this.owner = owner;
    }

    /** {@code from} 以降の子の位置を付け直す。 */
    private void reindexFrom(int from) {
        for (int i = Math.max(0, from), n = size(); i < n; i++) get(i).siblingIndex = i;
    }

    @Override
    public int indexOf(Object o) {
        if (o instanceof HtmlNode n) {
            int i = n.siblingIndex;
            if (i >= 0 && i < size() && get(i) == n) return i;
        }
        return super.indexOf(o);
    }

    @Override
    public boolean contains(Object o) {
        return indexOf(o) >= 0;
    }

    @Override
    public boolean add(HtmlNode node) {
        super.add(node);
        node.siblingIndex = size() - 1;
        owner.childAttached(node);
        return true;
    }

    @Override
    public void add(int index, HtmlNode node) {
        super.add(index, node);
        reindexFrom(index);
        owner.childAttached(node);
    }

    @Override
    public boolean addAll(Collection<? extends HtmlNode> nodes) {
        return addAll(size(), nodes);
    }

    @Override
    public boolean addAll(int index, Collection<? extends HtmlNode> nodes) {
        List<HtmlNode> added = new ArrayList<>(nodes);
        if (added.isEmpty()) return false;
        // 1 個の追加は CompactList が add(int, E) へ回す（この上書きを通って 2 回付く）ので、基本の操作を直接呼ぶ
        if (added.size() == 1) super.add(index, added.get(0));
        else super.addAll(index, added);
        reindexFrom(index);
        for (HtmlNode n : added) owner.childAttached(n);
        return true;
    }

    @Override
    public HtmlNode set(int index, HtmlNode node) {
        HtmlNode replaced = super.set(index, node);
        if (replaced == node) return replaced;
        // 並べ替え（listIterator の set）で先に別の位置へ置かれた子の位置は消さない
        if (replaced.siblingIndex == index) replaced.siblingIndex = -1;
        node.siblingIndex = index;
        owner.childDetached(replaced);
        owner.childAttached(node);
        return replaced;
    }

    @Override
    public HtmlNode remove(int index) {
        HtmlNode removed = super.remove(index);
        removed.siblingIndex = -1;
        reindexFrom(index);
        owner.childDetached(removed);
        return removed;
    }

    @Override
    public boolean remove(Object node) {
        int i = indexOf(node);
        if (i < 0) return false;
        remove(i);
        return true;
    }

    @Override
    public boolean removeIf(Predicate<? super HtmlNode> filter) {
        List<HtmlNode> removed = new ArrayList<>();
        int first = -1;
        for (int i = size() - 1; i >= 0; i--) {
            HtmlNode n = get(i);
            if (!filter.test(n)) continue;
            super.remove(i);
            n.siblingIndex = -1;
            removed.add(n);
            first = i;
        }
        if (removed.isEmpty()) return false;
        reindexFrom(first);
        for (int i = removed.size() - 1; i >= 0; i--) owner.childDetached(removed.get(i)); // 文書順に外す
        return true;
    }

    @Override
    public boolean removeAll(Collection<?> nodes) {
        return removeIf(nodes::contains);
    }

    @Override
    public boolean retainAll(Collection<?> nodes) {
        return removeIf(node -> !nodes.contains(node));
    }

    @Override
    public void clear() {
        List<HtmlNode> removed = new ArrayList<>(this);
        super.clear();
        for (HtmlNode n : removed) n.siblingIndex = -1;
        removed.forEach(owner::childDetached);
    }

    @Override
    protected void removeRange(int fromIndex, int toIndex) {
        List<HtmlNode> removed = new ArrayList<>(subList(fromIndex, toIndex));
        super.removeRange(fromIndex, toIndex);
        for (HtmlNode n : removed) n.siblingIndex = -1;
        reindexFrom(fromIndex);
        removed.forEach(owner::childDetached);
    }

    @Override
    public void replaceAll(UnaryOperator<HtmlNode> operator) {
        List<HtmlNode> before = new ArrayList<>(this);
        for (int i = 0; i < size(); i++) super.set(i, operator.apply(get(i)));
        for (HtmlNode n : before) n.siblingIndex = -1;
        reindexFrom(0);
        before.forEach(owner::childDetached);
        forEach(owner::childAttached);
    }
}
