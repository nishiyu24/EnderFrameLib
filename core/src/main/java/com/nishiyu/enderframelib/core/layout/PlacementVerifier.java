package com.nishiyu.enderframelib.core.layout;

import java.util.Arrays;

/**
 * 配置の記憶の検証（ENGINE_REARCHITECTURE_PLAN C 系。{@code Shortcut.VERIFY_PLACEMENT}）。
 *
 * <p>記憶が当たって配置を飛ばした（{@code PlacementMemo.tryReuseOrShift} が省略または平行移動で受理した）部分木を、記憶を使わずに
 * 最初から置き直して、箱の位置・大きさ・静的位置を比べる。食い違えば {@link AssertionError}（保持してはいけない部分木を
 * 保持した、または保持した後に外の状態が変わったのに気づかなかった、ことの検出）。測定の記憶の {@link MeasureVerifier} の配置版。</p>
 *
 * <p><b>比べない欄</b>: スクロール範囲・はみ出し・行の断片は比べない。部分木だけを単独で置き直すと、行の断片の管理（開始・終了・遅延消去）を
 * 祖先のエンジンが受け持つため、通常に置いた直後の箱でも断片の個数とスクロール範囲が食い違う〔実験: 通常に置いた直後の全ブロック箱を同じ手順で検証して、
 * 位置・大きさ・静的位置は食い違い 0、スクロール範囲と断片は食い違いが出た〕。人工物なので比べると偽陽性になる。</p>
 *
 * <p>置き直した結果は正しい値なので、そのまま残す（退避して戻さない）。描画の印は {@code PaintStamps} が出力の変化を見て決めるので、
 * 値が同じなら進まない。テストでだけ立てる（{@code costGate}・計測では無効）。</p>
 */
final class PlacementVerifier {
    private PlacementVerifier() {}

    private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);
    private static final java.util.concurrent.atomic.LongAdder VERIFIED = new java.util.concurrent.atomic.LongAdder(), VERIFIED_BOXES = new java.util.concurrent.atomic.LongAdder(), VERIFIED_TABLES = new java.util.concurrent.atomic.LongAdder();

    /** 診断・テスト用: 検証した部分木の数と、その箱の延べ数（検証が実際に走ったかを確かめる）。 */
    static long verified() { return VERIFIED.sum(); }
    static long verifiedBoxes() { return VERIFIED_BOXES.sum(); }
    /** 検証した部分木に含まれていた表の延べ数（表を保持して検証できているかを確かめる）。 */
    static long verifiedTables() { return VERIFIED_TABLES.sum(); }

    /** 検証の置き直しの最中か（この間は入れ子の検証をしない）。 */
    static boolean active() { return DEPTH.get()[0] > 0; }

    private static final String[] FIELDS = {"x", "y", "w", "h", "staticX", "staticY", "staticAreaW", "staticAreaH"};
    private static final float TOLERANCE = 0.01f;

    /** 部分木の配置の出力（位置・大きさ・静的位置）を平らに並べた値（箱・欄の添字つき）。 */
    private static final class Snapshot {
        double[] v = new double[256];
        LayoutBox[] owner = new LayoutBox[256];
        byte[] field = new byte[256];
        int n;

        void add(LayoutBox b, int f, double value) {
            if (n == v.length) {
                v = Arrays.copyOf(v, n * 2);
                owner = Arrays.copyOf(owner, n * 2);
                field = Arrays.copyOf(field, n * 2);
            }
            v[n] = value;
            owner[n] = b;
            field[n] = (byte) f;
            n++;
        }

        void capture(LayoutBox b) {
            add(b, 0, b.x); add(b, 1, b.y); add(b, 2, b.w); add(b, 3, b.h);
            add(b, 4, b.staticX); add(b, 5, b.staticY); add(b, 6, b.staticAreaW); add(b, 7, b.staticAreaH);
            for (int i = 0, k = b.children.size(); i < k; i++) capture(b.children.get(i));
        }
    }

    private static boolean near(double a, double b) {
        return a == b || (Double.isNaN(a) && Double.isNaN(b)) || Math.abs(a - b) <= TOLERANCE;
    }

    /** {@code box} の配置を省略または平行移動で済ませた直後に呼ぶ。 */
    static void verify(LayoutBox box, float parentX, float parentY, float parentOuterW, float parentOuterH, LayoutContext ctx) {
        if (active()) return;
        Snapshot kept = new Snapshot();
        kept.capture(box);
        VERIFIED.increment();
        VERIFIED_BOXES.add(kept.n);
        for (int i = 0; i < kept.n; i += 8) if (kept.owner[i].display.inner() == LayoutBox.Display.Inner.TABLE) VERIFIED_TABLES.increment();
        LayoutContext fresh = new LayoutContext(ctx.viewport(), ctx.intrinsicResolver(), ctx.scrollState(), ctx.fonts(),
                false, ctx.layoutRun(), ctx.firstPass(), ctx.autoVisibility());
        DEPTH.get()[0]++;
        try {
            PlacementMemo.resetPlacements(box, -1);
            box.layoutImpl(parentX, parentY, parentOuterW, parentOuterH, fresh);
        } finally {
            DEPTH.get()[0]--;
        }
        Snapshot now = new Snapshot();
        now.capture(box);
        if (kept.n != now.n) {
            throw new AssertionError("配置の記憶が古い（部分木の形が違う）: 箱 " + box.node.key() + " 記憶=" + kept.n + " 置き直し=" + now.n + " " + PlacementMemo.debugInfo(box));
        }
        for (int i = 0; i < kept.n; i++) {
            if (!near(kept.v[i], now.v[i])) {
                LayoutBox at = kept.owner[i];
                throw new AssertionError("配置の記憶が古い: 箱 " + at.node.tag + "#" + at.node.key() + " の " + FIELDS[kept.field[i]]
                        + " 記憶=" + kept.v[i] + " 置き直し=" + now.v[i] + "（検証の根 " + PlacementMemo.debugInfo(box) + "）");
            }
        }
    }
}
