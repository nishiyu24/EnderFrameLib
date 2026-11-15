package com.nishiyu.enderframelib.core.layout;

/**
 * B2 の着手条件の計測（ENGINE_REARCHITECTURE_PLAN）: 測定・配置の時間のうち、「互いに独立な部分木」の中で使われた割合。
 * 独立な部分木 = 兄弟が {@link #MIN_SIBLINGS} 以上いるブロックフローの親の、流れの中のブロックレベルの子で、float・絶対配置・表を含まないもの
 * （親が幅を決めたあとは、兄弟の結果に依らずに測れる・置ける。兄弟が少ないと配り先が無い）。最も外側のそういう部分木に入った時刻から出るまでを数える。
 * 既定は無効（フラグ 1 つの読み取りだけ）。{@code -Denderframe.layout.probe=true} か {@link #enable} で有効にする。
 */
public final class LayoutParallelProbe {
    private LayoutParallelProbe() {}

    public static final int MEASURE = 0, PLACE = 1;
    /** 兄弟がこの数以上いる親の子だけを「配れる単位」とみなす。 */
    static final int MIN_SIBLINGS = 8;
    private static volatile boolean enabled = Boolean.getBoolean("enderframe.layout.probe");
    /** スレッドごとの {深さ, 独立な部分木の中か(0/1), 独立の開始時刻, 全体の開始時刻}（種類ごと）。 */
    private static final ThreadLocal<long[][]> STATE = ThreadLocal.withInitial(() -> new long[2][4]);
    private static final long[] TOTAL_NS = new long[2], INDEPENDENT_NS = new long[2], INDEPENDENT_BOXES = new long[2];

    public static void enable(boolean on) {
        enabled = on;
    }

    public static boolean enabled() {
        return enabled;
    }

    public static synchronized void reset() {
        for (int k = 0; k < 2; k++) {
            TOTAL_NS[k] = 0;
            INDEPENDENT_NS[k] = 0;
            INDEPENDENT_BOXES[k] = 0;
        }
    }

    /** 独立な部分木の中で使った割合（0..1）。何も数えていなければ 0。 */
    public static synchronized double independentShare(int kind) {
        return TOTAL_NS[kind] == 0 ? 0 : INDEPENDENT_NS[kind] / (double) TOTAL_NS[kind];
    }

    public static synchronized long totalNs(int kind) {
        return TOTAL_NS[kind];
    }

    public static synchronized long independentBoxes(int kind) {
        return INDEPENDENT_BOXES[kind];
    }

    static boolean independent(LayoutBox b) {
        LayoutBox p = b.parent;
        if (p == null || p.display == null || b.display == null) return false;
        if (p.children.size() < MIN_SIBLINGS) return false; // 並列に配れるのは、兄弟が十分に多い所だけ（body → 唯一の子 の鎖は分けられない）
        var inner = p.display.inner();
        if (inner != LayoutBox.Display.Inner.FLOW
                && inner != LayoutBox.Display.Inner.FLOW_ROOT) return false;
        if (b.display.isInlineLevel() || b.display.isNone() || b.isAbsolute || b.isFixed || b.isOutsideMarker
                || BlockLayoutEngine.isFloating(b) || LayoutBox.isTableBox(b) || LayoutBox.isTableBox(p)) return false;
        return !BlockLayoutEngine.containsFloats(b);
    }

    /** 入るとき。戻り値を {@link #exit} へ渡す（0 は無効のとき）。 */
    static long enter(LayoutBox box, int kind) {
        if (!enabled) return 0;
        long[] s = STATE.get()[kind];
        long now = System.nanoTime();
        long token = 1;
        if (s[0]++ == 0) {
            s[3] = now;
            token |= 2; // 最も外側（全体の時間を数える）
        }
        if (s[1] == 0 && independent(box)) {
            s[1] = 1;
            s[2] = now;
            token |= 4; // 最も外側の独立な部分木
        }
        return token;
    }

    static void exit(int kind, long token) {
        if (token == 0) return;
        long[] s = STATE.get()[kind];
        long now = System.nanoTime();
        s[0]--;
        synchronized (LayoutParallelProbe.class) {
            if ((token & 4) != 0) {
                s[1] = 0;
                INDEPENDENT_NS[kind] += now - s[2];
                INDEPENDENT_BOXES[kind]++;
            }
            if ((token & 2) != 0) TOTAL_NS[kind] += now - s[3];
        }
    }
}
