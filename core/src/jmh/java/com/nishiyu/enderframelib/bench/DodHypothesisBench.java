package com.nishiyu.enderframelib.bench;

import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * D0（ENGINE_REARCHITECTURE_PLAN.md §8）: 「幾何を表示項目に焼き込む現行表現」と「Box 表（SoA）に一本化する表現」の
 * 作業量の仮説検証。2,000 行 child（250 行目に挿入）相当: 3,500 ボックス・17,500 DisplayItem・後続 1,750 行がずれる。
 * 実装を変える前に、SoA 側が現行の 100 倍以上速いかを確かめる。
 *
 * <pre>./gradlew :core:jmh -Pjmh.args="DodHypothesisBench -f 1 -wi 3 -i 5 -prof gc"</pre>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 4, time = 1)
@Measurement(iterations = 6, time = 1)
@Fork(1)
@State(Scope.Thread)
public class DodHypothesisBench {
    static final int ITEMS = 17_500;      // 後続のずれる表示項目
    static final int BOXES = 3_500;       // 後続のずれるボックス
    static final int ALL_ITEMS = 20_000;  // 全表示項目（走査ベンチ）
    static final int VISIBLE = 200;       // 可視項目

    // ---- 現行表現（AoS・不変レコード）
    List<DisplayItem> items = new ArrayList<>();
    IdentityHashMap<Object, float[]> memo = new IdentityHashMap<>();
    Object[] boxKeys = new Object[BOXES];
    int sign = 1;

    // ---- SoA 表現
    float[] boxY = new float[BOXES];
    float[] itemY = new float[ITEMS];      // 比較用: 項目ごとに y 列を持つ場合
    int[] paintStart = new int[BOXES];
    int[] paintEnd = new int[BOXES];

    // ---- 走査用
    int[] boxIdx = new int[ALL_ITEMS];
    byte[] kind = new byte[ALL_ITEMS];
    int[] payloadIdx = new int[ALL_ITEMS];
    float[] allBoxY = new float[BOXES];
    float[] allBoxH = new float[BOXES];
    int[] payloadColor = new int[64];
    int[] out = new int[ALL_ITEMS * 4];

    @Setup(Level.Trial)
    public void setUp() {
        for (int i = 0; i < ITEMS; i++) {
            items.add(new DisplayItem("n" + i, "n" + (i / 5), i % 5 == 0 ? ElementType.TEXT : ElementType.RECT,
                    10, i * 4, 300, 20, "row " + i, 0xFF202020, 0xFFFFFFFF, null, 4, 1f,
                    0, 0, 1, 1, 0, 0, null, null, null));
        }
        for (int i = 0; i < BOXES; i++) { boxKeys[i] = new Object(); memo.put(boxKeys[i], new float[]{10, i * 20}); }
        for (int i = 0; i < BOXES; i++) { paintStart[i] = i * 5; paintEnd[i] = i * 5 + 5; allBoxY[i] = i * 20; allBoxH[i] = 20; }
        for (int i = 0; i < ALL_ITEMS; i++) { boxIdx[i] = (i * BOXES) / ALL_ITEMS; kind[i] = (byte) (i % 5); payloadIdx[i] = i & 63; }
        for (int i = 0; i < 64; i++) payloadColor[i] = 0xFF000000 | i * 0x040404;
        // 走査用の AoS（全 20,000 項目）
        for (int i = ITEMS; i < ALL_ITEMS; i++) {
            items.add(new DisplayItem("n" + i, "n" + (i / 5), ElementType.RECT, 10, i * 4, 300, 20, null,
                    0xFF202020, 0xFFFFFFFF, null, 4, 1f, 0, 0, 1, 1, 0, 0, null, null, null));
        }
    }

    /** (a1) 現行: 後続項目を new DisplayItem で作り直す（DisplayListBuilder L1683 と同じ中身）。 */
    @Benchmark
    public void a1_currentRecordShift(Blackhole bh) {
        int dy = (sign = -sign) * 20;
        for (int i = 0; i < ITEMS; i++) {
            DisplayItem it = items.get(i);
            items.set(i, new DisplayItem(it.id(), it.baseNodeId(), it.type(), it.x(), it.y() + dy, it.width(), it.height(),
                    it.text(), it.color(), it.bgColor(), it.events(), it.radius(), it.opacity(), it.translateX(), it.translateY(),
                    it.scaleX(), it.scaleY(), it.rotate(), it.transitionDuration(), it.animDef(), it.fitMode(), it.meta(), it.key()));
        }
        bh.consume(items);
    }

    /** (a2) 現行 + ボックス単位の Map 引き（BoxMemo 参照相当）。3,500 回。 */
    @Benchmark
    public void a2_currentMemoLookup(Blackhole bh) {
        float s = 0;
        for (int i = 0; i < BOXES; i++) s += memo.get(boxKeys[i])[1];
        bh.consume(s);
    }

    /** (b1) 提案: Box 表の y 列を範囲加算（3,500 要素）。 */
    @Benchmark
    public void b1_soaBoxYShift() {
        float dy = (sign = -sign) * 20f;
        for (int i = 0; i < BOXES; i++) boxY[i] += dy;
    }

    /** (b2) 比較用: 項目ごとの y 列（17,500 要素）を範囲加算。 */
    @Benchmark
    public void b2_soaItemYShift() {
        float dy = (sign = -sign) * 20f;
        for (int i = 0; i < ITEMS; i++) itemY[i] += dy;
    }

    /** (b3) 提案: Box ごとの Paint 範囲 [start,end) の ±len 補正（2 本の int 列）。 */
    @Benchmark
    public void b3_soaPaintRangeShift() {
        int d = (sign = -sign) * 5;
        for (int i = 0; i < BOXES; i++) { paintStart[i] += d; paintEnd[i] += d; }
    }

    /** (b4) 提案: Box 表の変更を 64-bit dirty ビットで記録（3,500 ビット = 55 ワード）。 */
    @Benchmark
    public void b4_dirtyBits(Blackhole bh) {
        long[] bits = new long[(BOXES + 63) >>> 6];
        for (int i = 0; i < bits.length; i++) bits[i] = -1L;
        bh.consume(bits);
    }

    /** (c1) 現行: 全 20,000 項目（AoS）から可視 y 範囲を走査して 16B 相当を出力。 */
    @Benchmark
    public int c1_scanAoS() {
        int n = 0, lo = 4000, hi = 4800;
        for (int i = 0; i < ALL_ITEMS; i++) {
            DisplayItem it = items.get(i);
            int y = it.y();
            if (y + it.height() < lo || y > hi) continue;
            out[n++] = it.x(); out[n++] = y; out[n++] = it.width(); out[n++] = it.color();
        }
        return n;
    }

    /** (c2) 提案: Paint 表を全走査（可視判定は Box 表の y を引く）。D3 前の最悪ケース。 */
    @Benchmark
    public int c2_scanSoaFull() {
        int n = 0; float lo = 4000, hi = 4800;
        for (int i = 0; i < ALL_ITEMS; i++) {
            int b = boxIdx[i];
            float y = allBoxY[b];
            if (y + allBoxH[b] < lo || y > hi) continue;
            out[n++] = b; out[n++] = kind[i]; out[n++] = payloadIdx[i]; out[n++] = payloadColor[payloadIdx[i]];
        }
        return n;
    }

    /** (c3) 提案: D3 以降。可視範囲を y の二分探索で決め、可視 Paint 行だけ走査。 */
    @Benchmark
    public int c3_scanSoaVisibleOnly() {
        int lo = java.util.Arrays.binarySearch(allBoxY, 4000f);
        int hi = java.util.Arrays.binarySearch(allBoxY, 4800f);
        if (lo < 0) lo = -lo - 2; if (hi < 0) hi = -hi - 1;
        lo = Math.max(lo, 0); hi = Math.min(hi, BOXES - 1);
        int n = 0;
        for (int i = lo * 5; i < Math.min(ALL_ITEMS, (hi + 1) * 5); i++) {
            int b = boxIdx[i];
            out[n++] = b; out[n++] = kind[i]; out[n++] = payloadIdx[i]; out[n++] = payloadColor[payloadIdx[i]];
        }
        return n;
    }
}
