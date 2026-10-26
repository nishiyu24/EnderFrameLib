package com.nishiyu.enderframelib.client.renderer;

import com.nishiyu.enderframelib.core.paint.BoxDecorations;
import com.nishiyu.enderframelib.core.paint.model.ClipRect;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 項目に掛かるクリップの連鎖（{@link ClipRect#chain()}）を、項目を描く空間で当てられる形へ直す。
 *
 * <p>連鎖の 1 つ 1 つは、持ち主の要素の空間（その要素の変形を含む）の矩形。項目の空間との関係で 3 通りに分ける。
 * <ul>
 *   <li><b>平行移動だけ</b>の違い（動く祖先の中の overflow、同じ向きで動く持ち主）: 矩形をずらすだけで正確。
 *       全部を 1 つの矩形（角丸つき）に交わらせて、頂点のクリップとして渡す（文字・画像・SDF の全部に効く）。</li>
 *   <li><b>回転・拡縮</b>の違い（回る・伸び縮みする項目の外側の、動かない角丸の祖先など）: 矩形のままでは表せない。
 *       1 つだけ、持ち主の空間の角丸矩形と、項目の空間からそこへの変換をシェーダーへ渡す（{@link Transformed}。
 *       角丸も正確）。</li>
 *   <li>持ち主の空間が回っていない（軸平行）なら、画面の矩形としても出す（{@link ScreenRect}）。シェーダーを通らない
 *       文字・画像は、ハードウェアのシザーでこの外接矩形に切る（角丸は落ちるが、はみ出しは出ない）。</li>
 * </ul>
 *
 * <p>LIMITATION(制約): 回転・拡縮の違いのクリップは、シェーダーが正確に当てるのは 1 つだけ（角丸のあるものを優先し、内側）。
 * 残りは、持ち主が軸平行ならシザーの矩形（角丸は落ちる）、回っていれば当てない。シェーダーを通らない文字・画像は、
 * シザーの矩形だけで切る。世界の中の面はシザーを使えず、平行移動だけの違いの部分だけが当たる。
 * README の "Not supported"（Clipping under rotation or scaling）と FIDELITY.md に対応。
 */
final class ClipResolver {
    private ClipResolver() {}

    /** 持ち主の空間の矩形 {@code rect}（矩形 + 半径）と、項目の空間から持ち主の空間への変換。 */
    record Transformed(float[] rect, float m00, float m10, float m01, float m11, float tx, float ty) {
        /** 持ち主の空間の距離を項目の空間の距離にする倍率（持ち主の長さ 1 が項目の何か）。 */
        float distanceScale() {
            float det = Math.abs(m00 * m11 - m10 * m01);
            return det < 1e-12f ? 1f : (float) (1.0 / Math.sqrt(det));
        }
    }

    /** 持ち主の姿勢 {@code pose} で画面へ写る、軸平行の矩形（持ち主の空間）。 */
    record ScreenRect(Matrix4f pose, float x0, float y0, float x1, float y1) {}

    /**
     * @param local       平行移動だけの違いの矩形を交わらせたもの（項目の空間。矩形 + 半径）。無ければ null
     * @param transformed 回転・拡縮の違いの矩形のうち、シェーダーが当てる 1 つ。無ければ null
     * @param screen      シザーで切る画面の矩形（持ち主の空間が軸平行のもの）
     */
    record Result(float[] local, Transformed transformed, List<ScreenRect> screen) {
        boolean isEmpty() {
            return local == null && transformed == null && screen.isEmpty();
        }
    }

    /**
     * @param chain     合成する前のクリップ（外側から内側へ）
     * @param base      フレームの基準の姿勢（持ち主が無い・分からない矩形はここに留まる）
     * @param eff       項目を描く姿勢（祖先と自分の変形の後）
     * @param ownerPose 持ち主の id から、その要素の姿勢を引く（分からなければ null）
     */
    static Result resolve(List<ClipRect.Part> chain, Matrix4f base, Matrix4f eff, Function<String, Matrix4f> ownerPose) {
        Matrix4f invEff = new Matrix4f(eff).invert();
        float[] local = null;
        List<ScreenRect> screen = new ArrayList<>();
        ClipRect.Part shaderPart = null;
        Matrix4f shaderPose = null;
        List<ClipRect.Part> others = new ArrayList<>();
        List<Matrix4f> otherPoses = new ArrayList<>();
        for (ClipRect.Part part : chain) {
            Matrix4f pose = part.ownerId() == null ? null : ownerPose.apply(part.ownerId());
            if (pose == null) pose = base;
            Matrix4f toLocal = new Matrix4f(invEff).mul(pose);
            float[] rect = part.rect();
            if (translationOnly(toLocal)) {
                float tx = toLocal.m30(), ty = toLocal.m31();
                float[] shifted = rect.clone();
                shifted[0] += tx;
                shifted[1] += ty;
                shifted[2] += tx;
                shifted[3] += ty;
                local = local == null ? shifted : BoxDecorations.intersectClip(local, shifted);
            } else {
                others.add(part);
                otherPoses.add(pose);
            }
        }
        // 回転・拡縮の違いのうち、シェーダーが正確に当てるのは 1 つ（角丸があるものを優先し、内側を選ぶ）
        for (int i = others.size() - 1; i >= 0; i--) {
            if (hasRadius(others.get(i).rect())) {
                shaderPart = others.get(i);
                shaderPose = otherPoses.get(i);
                break;
            }
        }
        if (shaderPart == null && !others.isEmpty()) {
            shaderPart = others.get(others.size() - 1);
            shaderPose = otherPoses.get(others.size() - 1);
        }
        for (int i = 0; i < others.size(); i++) {
            float[] r = others.get(i).rect();
            Matrix4f pose = otherPoses.get(i);
            // 持ち主の空間が軸平行なら、画面の矩形としてシザーでも切る（シェーダーを通らない文字・画像のため）
            if (Math.abs(pose.m10()) < 1e-4f && Math.abs(pose.m01()) < 1e-4f) {
                screen.add(new ScreenRect(new Matrix4f(pose), r[0], r[1], r[2], r[3]));
            }
        }
        Transformed transformed = null;
        if (shaderPart != null) {
            Matrix4f toOwner = new Matrix4f(shaderPose).invert().mul(eff);
            if (Float.isFinite(toOwner.m00()) && Float.isFinite(toOwner.m31())) {
                transformed = new Transformed(shaderPart.rect(), toOwner.m00(), toOwner.m10(), toOwner.m01(), toOwner.m11(),
                        toOwner.m30(), toOwner.m31());
            }
        }
        return new Result(local, transformed, screen);
    }

    /** 項目の空間から持ち主の空間への変換が、平行移動だけか（向き・大きさが同じ）。 */
    static boolean translationOnly(Matrix4f m) {
        return Float.isFinite(m.m30()) && Float.isFinite(m.m31())
                && Math.abs(m.m00() - 1f) < 1e-4f && Math.abs(m.m11() - 1f) < 1e-4f
                && Math.abs(m.m10()) < 1e-4f && Math.abs(m.m01()) < 1e-4f;
    }

    private static boolean hasRadius(float[] rect) {
        for (int i = 4; i < rect.length; i++) if (rect[i] > 0f) return true;
        return false;
    }
}
