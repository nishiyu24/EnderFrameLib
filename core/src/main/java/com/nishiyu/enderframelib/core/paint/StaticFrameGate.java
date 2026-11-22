package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.PaintOffsets;

import java.util.List;

/**
 * 面の再描画を飛ばせるかの判定（Phase B-2）。バックエンドが直前に描いた表示リストと、いま渡された表示リストが
 * 「同じ項目が同じ順に並んでおり（{@link ItemDiff#unchanged}）、描画上の平行移動表（{@link PaintOffsets}）も
 * 同じ版のまま」であるうえで、時刻や外の事情で絵が変わりえない（アニメーションが止まっている・
 * 画像／動画／キャンバス／3D シーンを含まない・フォント世代が同じ）なら、前回の出力テクスチャをそのまま使える。
 *
 * <p>Minecraft に依存しない純粋な判定で、ホスト側（{@code CoreGpuSurface}）が面ごとに 1 つ持つ。
 * 前回の項目の控えは使い回す配列に書き込むので、描画のたびの確保は起きない（成長するときだけ）。</p>
 */
public final class StaticFrameGate {
    private final boolean enabled;
    private Object[] items = new Object[0];
    private int count = -1; // -1 = まだ何も描いていない
    private int fontGeneration;
    private PaintOffsets paintedOffsets = PaintOffsets.EMPTY;
    private int paintedOffsetsVersion = -1;
    private boolean paintedOffsetsEmpty = true;
    private boolean settled;

    /** @param enabled false なら常に {@link #canSkip} が false（切り分け用の無効化）。 */
    public StaticFrameGate(boolean enabled) {
        this.enabled = enabled;
    }

    /** 前回の描画結果をそのまま使えるか（平行移動なし版）。 */
    public boolean canSkip(List<DisplayItem> current, int fontGen) {
        return canSkip(current, PaintOffsets.EMPTY, fontGen);
    }

    /** 前回の描画結果をそのまま使えるか。 */
    public boolean canSkip(List<DisplayItem> current, PaintOffsets offsets, int fontGen) {
        if (!enabled || !settled || count < 0 || fontGeneration != fontGen || count != current.size()) return false;
        PaintOffsets eff = offsets != null ? offsets : PaintOffsets.EMPTY;
        if (!(eff.isEmpty() && paintedOffsetsEmpty)
                && (eff != paintedOffsets || eff.version() != paintedOffsetsVersion)) {
            return false;
        }
        int i = 0;
        for (DisplayItem it : current) {
            if (ItemDiff.changed((DisplayItem) items[i++], it)) return false;
        }
        return true;
    }

    /** 描いた直後に呼ぶ（平行移動なし版）。 */
    public void painted(List<DisplayItem> current, int fontGen, boolean backendStillAnimating) {
        painted(current, PaintOffsets.EMPTY, fontGen, backendStillAnimating);
    }

    /**
     * 描いた直後に呼ぶ。{@code backendStillAnimating} は {@code hasActiveAnimation(items, now)} の答え。
     * 次に同じ項目と {@code PaintOffsets} の版が来たとき、{@link #canSkip} が true になるのは「静止」と判断できたときだけ。
     */
    public void painted(List<DisplayItem> current, PaintOffsets offsets, int fontGen, boolean backendStillAnimating) {
        int n = current.size();
        if (items.length < n) items = new Object[Math.max(n, items.length * 2)];
        int i = 0;
        for (DisplayItem it : current) items[i++] = it;
        for (int k = n; k < count; k++) items[k] = null; // 前回より短くなったぶんの参照を手放す
        count = n;
        PaintOffsets eff = offsets != null ? offsets : PaintOffsets.EMPTY;
        paintedOffsets = eff;
        paintedOffsetsVersion = eff.version();
        paintedOffsetsEmpty = eff.isEmpty();
        fontGeneration = fontGen;
        settled = enabled && !timeDependent(current) && !backendStillAnimating;
    }

    /** 控えを捨てる（次は必ず描く）。 */
    public void invalidate() {
        for (int k = 0; k < count; k++) items[k] = null;
        count = -1;
        paintedOffsets = PaintOffsets.EMPTY;
        paintedOffsetsVersion = -1;
        paintedOffsetsEmpty = true;
        settled = false;
    }

    /** 時刻や外の事情（読み込み中の画像・動画・キャンバス・3D シーン）で絵が変わりうる項目を含むか。 */
    public static boolean timeDependent(List<DisplayItem> list) {
        for (DisplayItem it : list) {
            if (it.type().isExternallyDriven()) return true;
        }
        return false;
    }
}
