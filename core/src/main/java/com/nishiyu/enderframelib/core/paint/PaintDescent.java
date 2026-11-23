package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.dom.node.AttributeMap;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.LayoutOrigin;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * 表示リストの組み立てが木を下りる間の持ち回しの状態（K4f）。いま下りている箱の鎖（{@link #at}）と、各深さの
 * 子へ渡す原点（{@link LayoutOrigin}）、各深さの節の木の根〔{@link AttributeMap#treeTopBelow}〕、いま組み立てている箱の
 * 絶対位置（{@link #ax}・{@link #ay}）を持つ。根の属性版は親から子へ受け渡し、箱ごとに根まで辿り直さない。
 */
final class PaintDescent {
    private final ArrayList<LayoutBox> boxes = new ArrayList<>();
    private float[] cx = new float[32], cy = new float[32];
    private HtmlNode[] tops = new HtmlNode[32];
    /** いま {@code buildFresh} している箱の絶対位置。 */
    float ax, ay;

    int depth() { return boxes.size(); }
    LayoutBox at(int i) { return boxes.get(i); }
    void clear() { boxes.clear(); Arrays.fill(tops, null); }

    /** 親が深さ {@code depth()-1} の箱のときは親から受け継いだ原点、そうでなければ箱から解く。 */
    float originX(LayoutBox box) {
        int d = boxes.size();
        return d > 0 && boxes.get(d - 1) == box.parent ? cx[d - 1] : LayoutOrigin.originX(box);
    }

    float originY(LayoutBox box) {
        int d = boxes.size();
        return d > 0 && boxes.get(d - 1) == box.parent ? cy[d - 1] : LayoutOrigin.originY(box);
    }

    /** 根の箱を深さ 0 に積む（親なし）。 */
    void pushRoot(LayoutBox root) {
        push(root, root.x(), root.y(), 0f, 0f);
    }

    /** 箱を 1 段下りる。{@code ox}・{@code oy} は親から受け継いだ原点（{@link #originX}）。 */
    void push(LayoutBox box, float ax, float ay, float ox, float oy) {
        int d = boxes.size();
        if (cx.length <= d) {
            cx = Arrays.copyOf(cx, d * 2); cy = Arrays.copyOf(cy, d * 2); tops = Arrays.copyOf(tops, d * 2);
        }
        cx[d] = ox + LayoutOrigin.shiftX(box); cy[d] = oy + LayoutOrigin.shiftY(box);
        HtmlNode n = box.node;
        tops[d] = d > 0 ? AttributeMap.treeTopBelow(n, boxes.get(d - 1).node, tops[d - 1]) : AttributeMap.treeTopBelow(n, null, null);
        boxes.add(box);
        this.ax = ax; this.ay = ay;
    }

    void pop() {
        int d = boxes.size() - 1;
        tops[d] = null;
        boxes.remove(d);
    }

    /** 箱の木の非スタイル属性版。いま下りている箱ならば受け渡された根から O(1)、それ以外は根まで辿る。 */
    int treeAttrVer(LayoutBox box) {
        int d = boxes.size();
        return d > 0 && boxes.get(d - 1) == box ? AttributeMap.treeNonStyleAttrVersion(tops[d - 1]) : AttributeMap.treeNonStyleAttrVersion(box.node);
    }
}
