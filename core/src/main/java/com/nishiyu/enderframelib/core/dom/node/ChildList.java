package com.nishiyu.enderframelib.core.dom.node;

import com.nishiyu.enderframelib.core.dom.*;
import com.nishiyu.enderframelib.core.dom.node.*;
import com.nishiyu.enderframelib.core.dom.parser.*;
import com.nishiyu.enderframelib.core.dom.parser.*;
import com.nishiyu.enderframelib.core.kernel.CompactList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * 子の並び。直接 {@code add} / {@code remove} する呼び出し（パーサ・バインディング）も、
 * DOM のツリー操作と同じく id 索引へ反映するため、変更の入口をここに集める。
 */
final class ChildList extends CompactList<HtmlNode> {

    /** 子の並びの持ち主（A8 で {@code HtmlNode} の内部クラスから分けた。以前の暗黙の外側参照と同じ 1 欄）。 */
    private final HtmlNode owner;

    ChildList(HtmlNode owner) {
        this.owner = owner;
    }

    @Override
    public boolean add(HtmlNode node) {
        super.add(node);
        owner.childAttached(node);
        return true;
    }

    @Override
    public void add(int index, HtmlNode node) {
        super.add(index, node);
        owner.childAttached(node);
    }

    @Override
    public boolean addAll(Collection<? extends HtmlNode> nodes) {
        List<HtmlNode> added = new ArrayList<>(nodes);
        boolean changed = super.addAll(added);
        for (HtmlNode n : added) owner.attachChild(n, false);
        owner.relinkIfNumbered();
        return changed;
    }

    @Override
    public boolean addAll(int index, Collection<? extends HtmlNode> nodes) {
        List<HtmlNode> added = new ArrayList<>(nodes);
        boolean changed = super.addAll(index, added);
        for (HtmlNode n : added) owner.attachChild(n, false);
        owner.relinkIfNumbered();
        return changed;
    }

    @Override
    public HtmlNode set(int index, HtmlNode node) {
        HtmlNode replaced = super.set(index, node);
        owner.childDetached(replaced);
        owner.childAttached(node);
        return replaced;
    }

    @Override
    public HtmlNode remove(int index) {
        HtmlNode removed = super.remove(index);
        owner.childDetached(removed);
        return removed;
    }

    @Override
    public boolean remove(Object node) {
        boolean removed = super.remove(node);
        if (removed) owner.childDetached((HtmlNode) node);
        return removed;
    }

    @Override
    public boolean removeIf(Predicate<? super HtmlNode> filter) {
        List<HtmlNode> removed = new ArrayList<>();
        for (HtmlNode node : this) {
            if (filter.test(node)) removed.add(node);
        }
        boolean changed = super.removeIf(removed::contains);
        removed.forEach(owner::childDetached);
        return changed;
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
        removed.forEach(owner::childDetached);
    }

    @Override
    protected void removeRange(int fromIndex, int toIndex) {
        List<HtmlNode> removed = new ArrayList<>(subList(fromIndex, toIndex));
        super.removeRange(fromIndex, toIndex);
        removed.forEach(owner::childDetached);
    }

    @Override
    public void replaceAll(UnaryOperator<HtmlNode> operator) {
        List<HtmlNode> before = new ArrayList<>(this);
        for (int i = 0; i < size(); i++) {
            super.set(i, operator.apply(get(i)));
        }
        before.forEach(owner::childDetached);
        forEach(owner::childAttached);
    }
}
