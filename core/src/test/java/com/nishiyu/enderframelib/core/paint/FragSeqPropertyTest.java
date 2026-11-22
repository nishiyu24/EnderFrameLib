package com.nishiyu.enderframelib.core.paint;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** F0 試作: 永続ベクタ {@link FragSeq.Vec} を {@link ArrayList} の参照実装とランダム操作で比べる（DESIGN §0.5 の検証 1）。 */
class FragSeqPropertyTest {

    static DisplayItem item(String id) {
        return new DisplayItem(id, "n", ElementType.RECT, 0, 0, 1, 1, "", 0, 0, java.util.Map.of(), 0, 1f, 0, 0, 1f, 1f, 0, 0,
                com.nishiyu.enderframelib.core.css.model.AnimationSpec.NONE,
                com.nishiyu.enderframelib.core.paint.model.ImageFitMode.values()[0],
                com.nishiyu.enderframelib.core.paint.model.RenderMeta.EMPTY, 0L);
    }

    static FragSeq leaf(Random r, int[] counter) {
        int n = r.nextInt(4); // 0〜3 項目（空もある）
        DisplayItem[] a = new DisplayItem[n];
        for (int i = 0; i < n; i++) a[i] = item("i" + counter[0]++);
        return n == 0 ? FragSeq.EMPTY : new FragSeq.Leaf(a);
    }

    static DisplayItem[] flat(List<FragSeq> model) {
        List<DisplayItem> out = new ArrayList<>();
        for (FragSeq s : model) {
            DisplayItem[] a = new DisplayItem[s.items()];
            s.copyTo(a, 0);
            out.addAll(List.of(a));
        }
        return out.toArray(new DisplayItem[0]);
    }

    @Test
    void randomBuildSetGetMatchesArrayList() {
        for (int seed = 0; seed < 300; seed++) {
            Random r = new Random(seed);
            int[] counter = {0};
            int n = seed < 40 ? seed : r.nextInt(3500); // 0 件・1 節・2 段・3 段をまたぐ
            List<FragSeq> model = new ArrayList<>();
            for (int i = 0; i < n; i++) model.add(leaf(r, counter));
            FragSeq.Vec v = FragSeq.Vec.of(model);
            assertEquals(n, v.len(), "seed=" + seed);
            for (int step = 0; step < 40 && n > 0; step++) {
                int i = r.nextInt(n);
                FragSeq e = leaf(r, counter);
                FragSeq.Vec nv = v.set(i, e);
                // 元は不変
                assertSame(model.get(i), v.get(i), "元の並びが書き換わった seed=" + seed);
                model.set(i, e);
                v = nv;
                assertSame(e, v.get(i), "seed=" + seed + " i=" + i);
            }
            int total = 0;
            for (FragSeq s : model) total += s.items();
            assertEquals(total, v.items(), "項目数 seed=" + seed);
            DisplayItem[] got = new DisplayItem[v.items()];
            assertEquals(total, v.copyTo(got, 0));
            assertArrayEquals(flat(model), got, "順序 seed=" + seed);
            for (int i = 0; i < n; i += Math.max(1, n / 25)) assertSame(model.get(i), v.get(i));
        }
    }

    @Test
    void setLeavesOriginalAndSharesUntouchedNodes() {
        Random r = new Random(7);
        int[] counter = {0};
        List<FragSeq> model = new ArrayList<>();
        for (int i = 0; i < 2000; i++) model.add(leaf(r, counter));
        FragSeq.Vec v = FragSeq.Vec.of(model);
        FragSeq.Vec nv = v.set(1500, new FragSeq.Leaf(new DisplayItem[]{item("x")}));
        // 変えた位置以外の要素は同じ実体のまま
        for (int i = 0; i < 2000; i += 37) if (i != 1500) assertSame(v.get(i), nv.get(i));
        // 根から葉までの節だけが新しい（他の節は共有）
        int shared = 0;
        for (int g = 0; g < v.slots.length; g++) if (v.slots[g] == nv.slots[g]) shared++;
        assertEquals(v.slots.length - 1, shared);
    }

    @Test
    void setOutOfRangeFails() {
        FragSeq.Vec v = FragSeq.Vec.of(List.of(FragSeq.EMPTY, FragSeq.EMPTY));
        assertThrows(IndexOutOfBoundsException.class, () -> v.set(2, FragSeq.EMPTY));
        assertThrows(IndexOutOfBoundsException.class, () -> v.set(-1, FragSeq.EMPTY));
        assertThrows(IndexOutOfBoundsException.class, () -> v.insert(3, FragSeq.EMPTY));
        assertThrows(IndexOutOfBoundsException.class, () -> v.insert(-1, FragSeq.EMPTY));
        assertThrows(IndexOutOfBoundsException.class, () -> v.remove(2));
        assertThrows(IndexOutOfBoundsException.class, () -> v.remove(-1));
    }

    @Test
    void randomInsertRemoveSetMatchesArrayList() {
        for (int seed = 0; seed < 300; seed++) {
            Random r = new Random(seed + 1000L);
            int[] counter = {0};
            int initN = seed < 35 ? seed : r.nextInt(3500);
            List<FragSeq> model = new ArrayList<>();
            for (int i = 0; i < initN; i++) model.add(leaf(r, counter));
            FragSeq.Vec v = FragSeq.Vec.of(model);

            for (int step = 0; step < 50; step++) {
                int op = model.isEmpty() ? 1 : r.nextInt(4); // 0=set, 1=insert, 2=remove, 3=insert/remove near edges
                FragSeq.Vec prev = v;
                int checkIdx = model.isEmpty() ? -1 : r.nextInt(model.size());
                FragSeq snapAt = checkIdx >= 0 ? model.get(checkIdx) : null;
                if (op == 0) {
                    int idx = r.nextInt(model.size());
                    FragSeq e = leaf(r, counter);
                    v = v.set(idx, e);
                    model.set(idx, e);
                } else if (op == 1 || model.size() < 2) {
                    int idx = r.nextInt(model.size() + 1);
                    FragSeq e = leaf(r, counter);
                    v = v.insert(idx, e);
                    model.add(idx, e);
                } else if (op == 2) {
                    int idx = r.nextInt(model.size());
                    v = v.remove(idx);
                    model.remove(idx);
                } else {
                    int idx = r.nextBoolean() ? 0 : model.size() - 1;
                    if (r.nextBoolean()) {
                        FragSeq e = leaf(r, counter);
                        v = v.insert(idx, e);
                        model.add(idx, e);
                    } else {
                        v = v.remove(idx);
                        model.remove(idx);
                    }
                }
                // Immutability check on previous version
                if (checkIdx >= 0) assertSame(snapAt, prev.get(checkIdx), "prev mutated seed=" + seed + " step=" + step);
                assertEquals(model.size(), v.len(), "len seed=" + seed + " step=" + step);
            }

            int total = 0;
            boolean anyNonEmpty = false;
            for (FragSeq s : model) {
                total += s.items();
                if (!s.empty()) anyNonEmpty = true;
            }
            assertEquals(total, v.items(), "items seed=" + seed);
            assertEquals(!anyNonEmpty, v.empty(), "empty seed=" + seed);
            DisplayItem[] got = new DisplayItem[v.items()];
            assertEquals(total, v.copyTo(got, 0));
            assertArrayEquals(flat(model), got, "order seed=" + seed);
            for (int i = 0; i < model.size(); i += Math.max(1, model.size() / 20)) {
                assertSame(model.get(i), v.get(i));
            }
        }
    }

    @Test
    void insertRemoveSharesUntouchedBranchesAndEmptyOfLen() {
        Random r = new Random(99);
        int[] counter = {0};
        List<FragSeq> model = new ArrayList<>();
        for (int i = 0; i < 2000; i++) model.add(leaf(r, counter));
        FragSeq.Vec v = FragSeq.Vec.of(model);
        FragSeq extra = new FragSeq.Leaf(new DisplayItem[]{item("ins")});
        FragSeq.Vec ins = v.insert(10, extra);
        assertEquals(2001, ins.len());
        assertSame(extra, ins.get(10));
        assertSame(v.get(1500), ins.get(1501));
        assertSame(v.slots[v.slots.length - 1], ins.slots[ins.slots.length - 1]);

        FragSeq.Vec rem = ins.remove(10);
        assertEquals(2000, rem.len());
        assertSame(v.get(1500), rem.get(1500));

        assertEquals(0, FragSeq.Vec.emptyOfLen(0).len());
        FragSeq.Vec empty2000 = FragSeq.Vec.emptyOfLen(2000);
        assertEquals(2000, empty2000.len());
        assertEquals(0, empty2000.items());
        assertEquals(true, empty2000.empty());
        assertEquals(false, empty2000.set(1999, extra).empty());
        assertSame(extra, empty2000.set(1999, extra).get(1999));
    }
}
