package com.nishiyu.enderframelib.core.kernel.gpu;

import com.nishiyu.enderframelib.core.paint.model.DisplayItem;

import java.util.List;

/**
 * 表示項目の並びを {@link PropertyTrees} へ流し込む。{@code DisplayItem.key()} → プリミティブのスロットを覚え、
 * 前のフレームと同じ項目は同じスロットの同じバイトに書くだけ（変化がなければ汚れも立たない）。
 * 消えた項目のスロットは次の {@link #sync} で解放される。定常状態の呼び出しは割り当て 0。
 *
 * <p>項目ごとに、変換（平行移動・拡縮・回転のどれかが恒等でない）と効果（不透明度が 1 でない）が必要なときだけ
 * 変換／効果スロットを持つ。描画順はプリミティブの並び順＝項目の並び順を、{@link #orderSlot} が保つ。</p>
 */
public final class GpuSceneSync {
    private final PropertyTrees trees;
    private final LongIntMap slotOf;
    private final int[] xformOf;
    private final int[] effectOf;
    private final int[] stamp;
    private final long[] keyOf;
    private final int[] order;
    private int orderCount;
    private int frame;

    public GpuSceneSync(PropertyTrees trees) {
        this.trees = trees;
        int cap = trees.primitives.capacity();
        this.slotOf = new LongIntMap(cap);
        this.xformOf = new int[cap];
        this.effectOf = new int[cap];
        this.stamp = new int[cap];
        this.keyOf = new long[cap];
        this.order = new int[cap];
    }

    /** 反映した項目数（容量を超えた分は捨てる）。 */
    public int sync(List<DisplayItem> items) {
        frame++;
        orderCount = 0;
        for (int i = 0, n = items.size(); i < n; i++) {
            DisplayItem it = items.get(i);
            if (orderCount >= order.length) break;
            long key = it.key();
            int slot = slotOf.get(key);
            if (slot < 0) {
                slot = trees.addPrimitive();
                if (slot < 0) break;
                slotOf.put(key, slot);
                keyOf[slot] = key;
                xformOf[slot] = PropertyTrees.NONE;
                effectOf[slot] = PropertyTrees.NONE;
            }
            stamp[slot] = frame;
            write(slot, it);
            trees.order.putInt(orderCount, 0, slot);
            order[orderCount++] = slot;
        }
        sweep();
        return orderCount;
    }

    private void write(int slot, DisplayItem it) {
        boolean xf = it.translateX() != 0f || it.translateY() != 0f || it.scaleX() != 1f || it.scaleY() != 1f || it.rotate() != 0f;
        int t = xformOf[slot];
        float rx = it.x(), ry = it.y();
        if (xf) {
            // 拡縮・回転は要素の中心が軸。矩形は中心原点で持ち、変換の平行移動に中心の位置を畳み込む
            float hw = it.width() * 0.5f, hh = it.height() * 0.5f;
            if (t < 0) { t = trees.addTransform(PropertyTrees.NONE, 0, 0, 1, 1, 0); xformOf[slot] = t; }
            if (t >= 0) trees.setTransformTarget(t, it.x() + hw + it.translateX(), it.y() + hh + it.translateY(),
                    it.scaleX(), it.scaleY(), it.rotate());
            rx = -hw; ry = -hh;
        } else if (t >= 0) {
            trees.transforms.free(t);
            xformOf[slot] = PropertyTrees.NONE;
            t = PropertyTrees.NONE;
        }
        int e = effectOf[slot];
        if (it.opacity() != 1f) {
            if (e < 0) { e = trees.addEffect(PropertyTrees.NONE, it.opacity(), 0, 0, 0f); effectOf[slot] = e; }
            if (e >= 0) trees.setEffect(e, PropertyTrees.NONE, it.opacity(), 0, 0, 0f);
        } else if (e >= 0) {
            trees.effects.free(e);
            effectOf[slot] = PropertyTrees.NONE;
            e = PropertyTrees.NONE;
        }
        trees.setPrimitive(slot, PropertyTrees.OP_FILL, t, PropertyTrees.NONE, e,
                rx, ry, it.width(), it.height(), it.bgColor(), it.color());
        float r = it.radius();
        trees.setPrimitiveRadii(slot, r, r, r, r);
    }

    /** このフレームに現れなかったプリミティブを解放する。 */
    private void sweep() {
        PersistentVramBuffer p = trees.primitives;
        for (int s = 0, hw = p.highWater(); s < hw; s++) {
            if (stamp[s] == frame || keyOf[s] == 0L) continue;
            long k = keyOf[s];
            if (slotOf.get(k) != s) continue;
            slotOf.remove(k);
            if (xformOf[s] >= 0) { trees.transforms.free(xformOf[s]); xformOf[s] = PropertyTrees.NONE; }
            if (effectOf[s] >= 0) { trees.effects.free(effectOf[s]); effectOf[s] = PropertyTrees.NONE; }
            keyOf[s] = 0L;
            p.free(s);
        }
    }

    public int primitiveSlot(long key) { return slotOf.get(key); }
    public int transformSlot(int primitiveSlot) { return xformOf[primitiveSlot]; }
    public int effectSlot(int primitiveSlot) { return effectOf[primitiveSlot]; }
    public int drawCount() { return orderCount; }
    /** 描画順（項目の並び順）の {@code i} 番目のプリミティブスロット。 */
    public int orderSlot(int i) { return order[i]; }

    /** {@code long} → {@code int}（≥0）の開地址法ハッシュ表（削除は後ろ詰め）。固定容量・割り当て 0。 */
    static final class LongIntMap {
        private final long[] keys;
        private final int[] vals;
        private final int mask;

        LongIntMap(int expected) {
            int cap = Integer.highestOneBit(Math.max(4, expected * 2 - 1)) << 1;
            keys = new long[cap];
            vals = new int[cap];
            java.util.Arrays.fill(vals, -1);
            mask = cap - 1;
        }

        private int hash(long k) {
            k *= 0x9E3779B97F4A7C15L;
            return (int) (k ^ (k >>> 32)) & mask;
        }

        int get(long k) {
            for (int i = hash(k); vals[i] >= 0; i = (i + 1) & mask) if (keys[i] == k) return vals[i];
            return -1;
        }

        void put(long k, int v) {
            int i = hash(k);
            while (vals[i] >= 0 && keys[i] != k) i = (i + 1) & mask;
            keys[i] = k;
            vals[i] = v;
        }

        void remove(long k) {
            int i = hash(k);
            while (vals[i] >= 0 && keys[i] != k) i = (i + 1) & mask;
            if (vals[i] < 0) return;
            vals[i] = -1;
            int j = (i + 1) & mask;
            while (vals[j] >= 0) { // 後続のクラスターを詰め直す
                long kk = keys[j];
                int vv = vals[j];
                vals[j] = -1;
                put(kk, vv);
                j = (j + 1) & mask;
            }
        }
    }
}
