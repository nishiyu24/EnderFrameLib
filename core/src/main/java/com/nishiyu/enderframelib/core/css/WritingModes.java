package com.nishiyu.enderframelib.core.css;

/**
 * 書字方向（{@code writing-mode}）と字形の向き（{@code text-orientation}）の読み取り（CSS Writing Modes 4）。
 *
 * <p>縦書きは「論理（横書き）の軸で組んだ内容を回して置く」方式で実現する（{@code BoxTreeBuilder} が縦書きの根の内容を包む箱を作り、
 * {@code VerticalWritingEngine} が置く）。{@code vertical-rl} と {@code sideways-rl} は 90° 回転（行は右から左へ積む）、
 * {@code sideways-lr} は -90° 回転（行は左から右へ・文字は下から上）。
 * LIMITATION: {@code vertical-lr}（行が左から右・文字は上から下）は回転だけでは表せない（鏡映になる）ので
 * {@code vertical-rl} と同じ並びで描く。
 */
public final class WritingModes {
    private WritingModes() {}

    public static final int HORIZONTAL = 0;
    public static final int VERTICAL_RL = 1;
    public static final int VERTICAL_LR = 2;
    public static final int SIDEWAYS_RL = 3;
    public static final int SIDEWAYS_LR = 4;

    /** {@code writing-mode} の値を上の定数へ。未知・{@code horizontal-tb} は {@link #HORIZONTAL}。 */
    public static int flowOf(String value) {
        if (value == null) return HORIZONTAL;
        String v = value.trim();
        if (v.isEmpty() || v.startsWith("horizontal")) return HORIZONTAL;
        return switch (v.toLowerCase(java.util.Locale.ROOT)) {
            case "vertical-rl" -> VERTICAL_RL;
            case "vertical-lr" -> VERTICAL_LR;
            case "sideways-rl" -> SIDEWAYS_RL;
            case "sideways-lr" -> SIDEWAYS_LR;
            default -> HORIZONTAL;
        };
    }

    public static int flowOf(ComputedStyle style) {
        return style == null ? HORIZONTAL : flowOf(style.get(CssPropertyIndex.WRITING_MODE));
    }

    public static boolean isVertical(ComputedStyle style) {
        return flowOf(style) != HORIZONTAL;
    }

    /** 内容を回す向きが反時計回り（-90°）か。 */
    public static boolean isCounterClockwise(int flow) {
        return flow == SIDEWAYS_LR;
    }

    /** 字形の向きの種別。 */
    public enum Orientation { MIXED, UPRIGHT, SIDEWAYS }

    public static Orientation orientationOf(ComputedStyle style) {
        String v = style == null ? null : style.get(CssPropertyIndex.TEXT_ORIENTATION);
        if (v == null) return Orientation.MIXED;
        return switch (v.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "upright" -> Orientation.UPRIGHT;
            case "sideways", "sideways-right" -> Orientation.SIDEWAYS;
            default -> Orientation.MIXED;
        };
    }

    /**
     * この箱の文字を、字ごとに正立させる（回転を打ち消す）ことがあるか。{@code sideways-*} の書字方向と
     * {@code text-orientation: sideways} は全て寝かせるので無い。
     */
    public static boolean usesUpright(ComputedStyle style) {
        int flow = flowOf(style);
        if (flow != VERTICAL_RL && flow != VERTICAL_LR) return false;
        return orientationOf(style) != Orientation.SIDEWAYS;
    }

    /** {@code mixed} で正立させる文字（全角・かな・漢字・ハングル・絵文字など）か。 */
    public static boolean isUprightByDefault(int cp) {
        return (cp >= 0x2E80 && cp <= 0xA4CF)          // CJK 部首〜イ族・かな・CJK 記号・漢字
                || (cp >= 0xAC00 && cp <= 0xD7AF)      // ハングル音節
                || (cp >= 0xF900 && cp <= 0xFAFF)      // CJK 互換漢字
                || (cp >= 0xFE30 && cp <= 0xFE4F)      // CJK 互換形
                || (cp >= 0xFF00 && cp <= 0xFF60)      // 全角形
                || (cp >= 0xFFE0 && cp <= 0xFFE6)
                || (cp >= 0x1F000 && cp <= 0x1FAFF)    // 絵文字
                || (cp >= 0x20000 && cp <= 0x3FFFF);   // 拡張漢字
    }

    /** 文字 {@code cp} をこのスタイルで正立させるか。 */
    public static boolean isUpright(Orientation o, int cp) {
        return switch (o) {
            case UPRIGHT -> true;
            case SIDEWAYS -> false;
            case MIXED -> isUprightByDefault(cp);
        };
    }
}
