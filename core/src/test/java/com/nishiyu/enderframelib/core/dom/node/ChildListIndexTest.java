package com.nishiyu.enderframelib.core.dom.node;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * 子の並びが各子の位置（{@link HtmlNode#siblingIndex}）を<b>正確に</b>保つこと（M5-1）。
 *
 * <p>{@link HtmlNode#indexInParent} は位置が古くても並びを走査して正しい答えを返すので、照合の結果だけを見る試験では
 * 位置の付け直し忘れが見えない（O(1) が黙って O(n) に戻る）。ここでは欄そのものを、全ての変更の経路の後で確かめる。</p>
 */
class ChildListIndexTest {

    private static HtmlElement child(HtmlNode parent, String tag) {
        HtmlElement e = new HtmlElement(tag);
        e.parent = parent; // 子の並びを直接触る経路では、親は呼び出し側が結ぶ
        return e;
    }

    private static void assertExact(HtmlNode parent, List<HtmlNode> removed) {
        for (int i = 0; i < parent.children.size(); i++) {
            assertEquals(i, parent.children.get(i).siblingIndex, "子 " + i + " の位置");
        }
        for (HtmlNode r : removed) {
            if (!parent.children.contains(r)) assertEquals(-1, r.siblingIndex, "外れた子の位置は -1");
        }
    }

    @Test
    void everyMutationPathKeepsTheIndexExact() {
        HtmlElement p = new HtmlElement("div");
        List<HtmlNode> gone = new ArrayList<>();
        for (int i = 0; i < 5; i++) p.appendChild(new HtmlElement("i"));
        assertExact(p, gone);

        p.insertBefore(new HtmlElement("b"), p.children.get(0));                 // 先頭へ挿入
        assertExact(p, gone);
        p.children.add(3, child(p, "u"));                                         // 途中へ直接
        assertExact(p, gone);
        p.children.addAll(2, List.of(child(p, "s"), child(p, "s")));              // 途中へまとめて
        assertExact(p, gone);
        p.children.addAll(List.of(child(p, "q")));                                // 末尾へ 1 個
        assertExact(p, gone);
        gone.add(p.children.get(4));
        p.removeChild(p.children.get(4));                                         // 途中を外す
        assertExact(p, gone);
        gone.add(p.children.get(0));
        p.children.remove(p.children.get(0));                                     // 節で外す
        assertExact(p, gone);
        HtmlNode replaced = p.children.set(2, child(p, "em"));                    // 置き換え
        gone.add(replaced);
        assertExact(p, gone);
        p.children.removeIf(n -> n.tag.equals("s"));                              // 条件で外す
        assertExact(p, gone);
        List<HtmlNode> before = new ArrayList<>(p.children);
        p.children.subList(1, 3).clear();                                         // 範囲で外す
        gone.addAll(before.subList(1, 3));
        assertExact(p, gone);
        HtmlNode moved = p.children.get(0);
        p.appendChild(moved);                                                     // 同じ親の中で末尾へ移す
        assertExact(p, gone);
        gone.addAll(p.children);
        p.children.clear();
        assertExact(p, gone);
    }

    @Test
    void randomMutationsKeepTheIndexExact() {
        HtmlElement p = new HtmlElement("ul");
        List<HtmlNode> gone = new ArrayList<>();
        Random random = new Random(3);
        for (int step = 0; step < 2000; step++) {
            gone.clear(); // この手で外れた子だけを確かめる
            int n = p.children.size();
            switch (random.nextInt(6)) {
                case 0 -> p.insertBefore(new HtmlElement("li"), n == 0 ? null : p.children.get(random.nextInt(n)));
                case 1 -> p.appendChild(new HtmlElement("li"));
                case 2 -> { if (n > 0) { HtmlNode r = p.children.get(random.nextInt(n)); gone.add(r); p.removeChild(r); } }
                case 3 -> { if (n > 1) p.insertBefore(p.children.get(random.nextInt(n)), p.children.get(random.nextInt(n))); }
                case 4 -> p.children.add(random.nextInt(n + 1), child(p, "li"));
                default -> { if (n > 0) gone.add(p.children.set(random.nextInt(n), child(p, "li"))); }
            }
            assertExact(p, gone);
        }
    }
}
