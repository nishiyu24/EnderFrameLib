package com.nishiyu.enderframelib.core.paint.software;

import com.nishiyu.enderframelib.core.paint.ItemDiff;
import com.nishiyu.enderframelib.core.paint.PaintSceneDriver.Resolved;
import com.nishiyu.enderframelib.core.paint.emit.Clip;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import java.util.Arrays;

/**
 * 項目ごとの「再生の鍵」（C2）。止まっている項目が、前フレームに描いたときと同じ入力なら、GPU 側の出力を計算せずに前進させる（再生）。
 * その入力の組を、ここに 1 か所に集める。鍵に入るもの:
 * <ul>
 *   <li>項目の実体（項目は完全に不変なので {@link ItemDiff#unchanged} による同一性だけを見る）</li>
 *   <li>解決結果（姿勢・補間。{@code Resolved} の実体。外部の {@code PaintOffsets} が変われば {@code Resolved} が作り直される）</li>
 *   <li>クリップ（実体）・不透明度（平らにした値）</li>
 * </ul>
 * 「描いた」記録と「画面外で省いた」記録を区別する。記録は項目の添字で引き、添字より前のものは増やしながら使い回す。
 */
final class ReplayKeys {
    private DisplayItem[] el = new DisplayItem[0];
    private Resolved[] res = new Resolved[0];
    private Clip[] clip = new Clip[0];
    private float[] flat = new float[0];
    private boolean[] culled = new boolean[0];

    private void ensure(int i) {
        if (i < el.length) return;
        int n = Math.max(i + 1, el.length * 2 + 64);
        el = Arrays.copyOf(el, n);
        res = Arrays.copyOf(res, n);
        clip = Arrays.copyOf(clip, n);
        flat = Arrays.copyOf(flat, n);
        culled = Arrays.copyOf(culled, n);
    }

    /** 同じ項目・同じ解決結果で覚えたものがあるか。静止の高速経路の鍵（クリップ・不透明度は別に見る）。 */
    boolean sameItemAndPose(int i, DisplayItem e, Resolved r) {
        return i < el.length && ItemDiff.unchanged(el[i], e) && res[i] == r;
    }

    /** 覚えたものが「画面外で省いた」記録か（{@link #sameItemAndPose} が真の項目に対して）。 */
    boolean culled(int i) {
        return culled[i];
    }

    Clip clip(int i) {
        return clip[i];
    }

    float flat(int i) {
        return flat[i];
    }

    /** 描いたと覚えたもの（省いた記録・無記録でない）があるか。 */
    boolean drawn(int i) {
        return i < el.length && el[i] != null && !culled[i];
    }

    /** 描いたと覚えたもので、不透明度（平らにした値）が 1 でないものがあるか（差分の更新はこれを扱えない）。 */
    boolean drawnWithFlatNotOne(int i) {
        return drawn(i) && flat[i] != 1f;
    }

    /** 再生してよいか: 描いたと覚えていて、項目・解決結果・クリップ・不透明度の全部が同じ。 */
    boolean replayable(int i, DisplayItem e, Resolved r, Clip c, float f) {
        ensure(i);
        return !culled[i] && ItemDiff.unchanged(el[i], e) && res[i] == r && clip[i] == c && flat[i] == f;
    }

    /** 今回の入力を覚える。{@code wasCulled} は画面外で省いた記録。 */
    void remember(int i, DisplayItem e, Resolved r, Clip c, float f, boolean wasCulled) {
        ensure(i);
        el[i] = e;
        res[i] = r;
        clip[i] = c;
        flat[i] = f;
        culled[i] = wasCulled;
    }

    /** 覚えたものを捨てる（描けなかった・再生できない描き方になった）。 */
    void forget(int i) {
        if (i < 0 || i >= el.length) return;
        el[i] = null;
        culled[i] = false;
    }
}
