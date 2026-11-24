package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.layout.LayoutBox.Display.Inner;
import java.util.ArrayList;
import java.util.List;

/**
 * 親の子の差分〔部分構築。{@code DESIGN_INCREMENTAL_KERNEL.md} L2b〕を箱の木の組み立てが読む入口。{@code RenderTreeBuilder} が導いた
 * {@code {前の件数, 後ろの件数}}〔触れられていない前後の子〕を、前回の組み立て済みの並び〔{@link LayoutBox#children}〕と同一性で確かめ、
 * 間の子だけを組み直して前後は前回の箱のまま並べる。前後の箱が組み立て済みの並びと一致しない〔匿名ボックスで包まれた・空白が落ちた〕所で
 * 前後の範囲を縮める〔縮めた分は間として組み直す〕。間・境界が行内の箱・番号つきのリスト項目などで、並べ直しが前後に響き得るときは null
 * 〔親ごと組み直す〕。
 */
final class ChildWindow {
    private ChildWindow() {}

    /** 確かめた {@code {前の件数, 後ろの件数}}。窓を使えない形なら null。 */
    static int[] of(LayoutBox box, int[] hint) {
        if (hint == null || box.display.listItem() || box.display.inner() != Inner.FLOW || !box.display.isBlockContainer()
                || box.node == null || com.nishiyu.enderframelib.core.dom.node.ElementTraits.isFieldset(box.node.tag)) return null;
        List<LayoutBox> ec = box.elementChildren, ch = box.children;
        int m = ec.size(), k = ch.size(), pre = 0, suf = 0;
        while (pre < hint[0] && pre < k && pre < m && ch.get(pre) == ec.get(pre)) pre++;
        while (suf < hint[1] && suf < k - pre && suf < m - pre && ch.get(k - 1 - suf) == ec.get(m - 1 - suf)) suf++;
        // 先頭・末尾の子は、隣に増えた箱で空白の扱いが変わり得る〔間の途中の子は匿名ボックスで包まれるか落ちるので、前後の一致で確かめ済み〕
        if (m > 0 && (!plain(ec.get(0)) || !plain(ec.get(m - 1)))) return null;
        for (int i = Math.max(0, pre - 1), end = Math.min(m, m - suf + 1); i < end; i++) {
            if (!plain(ec.get(i))) return null;
        }
        return new int[] {pre, suf};
    }

    /** 前後の箱と、間で組み直した箱を 1 つの並びにする。 */
    static List<LayoutBox> assemble(LayoutBox box, int[] w, List<LayoutBox> middle) {
        List<LayoutBox> ch = box.children;
        List<LayoutBox> all = new ArrayList<>(w[0] + middle.size() + w[1]);
        all.addAll(ch.subList(0, w[0]));
        all.addAll(middle);
        all.addAll(ch.subList(ch.size() - w[1], ch.size()));
        return all;
    }

    /** 並べ直しが隣へ響かない箱〔ブロックレベル・匿名でない・番号を使わない〕。 */
    private static boolean plain(LayoutBox c) {
        LayoutBox.Display d = c.display;
        if (c.isAnonymous || c.isOutsideMarker || c.node == null || c.node.isPseudoNode || c.node.nodeType != com.nishiyu.enderframelib.core.dom.node.NodeType.ELEMENT || d.isInlineLevel() || d.isContents() || d.isTableInternal()) return false;
        if (!d.listItem()) return true;
        String type = com.nishiyu.enderframelib.core.css.Style.or(c.style.typed().inherited.listStyleType, "disc");
        return "none".equalsIgnoreCase(type) || "disc".equalsIgnoreCase(type) || "circle".equalsIgnoreCase(type) || "square".equalsIgnoreCase(type);
    }
}
