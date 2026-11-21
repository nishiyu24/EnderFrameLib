package com.nishiyu.enderframelib.core.paint.port;

import com.nishiyu.enderframelib.core.paint.model.ClipShape;
import com.nishiyu.enderframelib.core.paint.model.FilterOp;
import com.nishiyu.enderframelib.core.raster.Matrix2D;
import com.nishiyu.enderframelib.core.raster.Pixmap;
import java.util.List;

/**
 * {@link GpuSink} のうち、グループ（不透明度・フィルター・マスク・混合）の層と、スクロールの保持層を扱う口。既定はすべて「受け取らない」。
 */
public interface GpuLayerSink {

    /**
     * グループ（{@code opacity} を持つ要素）の層を開く。以降の描画はこの層に入り、{@link #endGroup} で {@code opacity} を掛けて親へ重ねる。
     * 層を作れなければ {@code false}（その場合 CPU が層を持つ）。入れ子は呼び出しの順に積む。
     */
    default boolean beginGroup(float opacity) {
        return beginGroup(opacity, List.of(), new double[]{1, 0, 0, 1}, false, false);
    }

    /**
     * フィルター・マスク付きの層を開く。{@code filters} は閉じるときに（色の行列 → ぼかし → 影の順に）層へ掛ける。
     * {@code map} は錨の姿勢の線形部 {@code {a, b, c, d}}（ぼかしの標準偏差と影のずれを、箱の座標から画素にする）。
     * {@code masked} なら、続く {@link #beginMask} / {@link #endMask} の間に描いた画像で層を透かす
     * （{@code maskLuminance} なら輝度、そうでなければアルファ）。
     */
    default boolean beginGroup(float opacity, List<FilterOp> filters, double[] map, boolean masked, boolean maskLuminance) {
        return false;
    }

    /**
     * {@code mix-blend-mode} 付きの層を開く。閉じるときに、層の絵を親の絵（背景）と {@code blend} で混ぜる。
     * 非対応なら {@code false}（{@code NORMAL} は従来の版へ）。
     */
    default boolean beginGroup(float opacity, List<FilterOp> filters, double[] map, boolean masked, boolean maskLuminance,
                               com.nishiyu.enderframelib.core.paint.model.BlendMode blend) {
        if (blend != com.nishiyu.enderframelib.core.paint.model.BlendMode.NORMAL) return false;
        return beginGroup(opacity, filters, map, masked, maskLuminance);
    }

    /**
     * {@code clip-path} と {@code backdrop-filter} の指定。{@code pose} は錨の姿勢（箱の座標 → デバイス画素）。
     * {@code clip} は層の絵を切る図形、{@code backdrop} と {@code backdropShape}（角丸の箱）は、層の後ろ（親の絵）を加工して貼る指定。
     */
    record GroupExtras(Matrix2D pose, ClipShape clip, List<FilterOp> backdrop, ClipShape backdropShape) {}

    /** {@link GroupExtras} 付きの層を開く。非対応なら {@code false}（CPU が層を持つ）。{@code extras} が null なら従来の版。 */
    default boolean beginGroup(float opacity, List<FilterOp> filters, double[] map, boolean masked, boolean maskLuminance,
                               com.nishiyu.enderframelib.core.paint.model.BlendMode blend, GroupExtras extras) {
        if (extras != null) return false;
        return beginGroup(opacity, filters, map, masked, maskLuminance, blend);
    }

    /** 直前に開いた層のマスク画像を描く層を開く（{@link #endMask} までの描画はマスクに入る）。 */
    default boolean beginMask() {
        return false;
    }

    /** マスクの層を閉じ、直前の層に預ける。 */
    default void endMask() {}

    /**
     * 現在の描画先（GPU の絵）を、溜めた分を吐いたうえで新しい {@link Pixmap}（事前乗算・左上原点）へ読み戻す。
     * GPU の層が足りず CPU の層に落ちた {@code mix-blend-mode} / {@code backdrop-filter} が、GPU の絵を背景に混ぜるために使う。非対応なら null。
     */
    default Pixmap snapshotTarget() {
        return null;
    }

    /**
     * {@link #snapshotTarget()} の範囲限定版。{@code region}（{@code {x0,y0,x1,y1}}）の外の画素は<b>不定</b>（呼び出し側は読まない）。
     * {@code mix-blend-mode} は層の描いた範囲だけを混ぜるので、全面を読み戻さずに済む。既定は全面版。
     */
    default Pixmap snapshotTarget(int[] region) {
        return snapshotTarget();
    }

    /** {@link #snapshotTarget} で読んだ絵の {@code region}（{@code {x0,y0,x1,y1}}）を、ブレンドなしで現在の描画先へ書き戻す。 */
    default void overwriteTarget(Pixmap pixmap, int[] region) {}

    /** 一番内側の層を閉じて親へ重ねる。 */
    default void endGroup() {}

    /**
     * スクロールコンテナの中身を保持した層として扱うかを決める（の受け皿（描画側の保持するスクロール層））。
     * 既定は NONE。{@code SKIP} は保持済みの層を {@link #drawScrollLayer} で重ねる、{@code RECORD} は {@link #beginScrollLayer} 〜
     * {@link #endScrollLayer} の間に描いた内容を層に保持する。
     */
    default com.nishiyu.enderframelib.core.paint.RetainPlan planScrollLayer(GpuScrollLayer layer) {
        return com.nishiyu.enderframelib.core.paint.RetainPlan.NONE;
    }

    /**
     * 境界矩形だけでスクロール保持層の方針を決める軽量版。{@code null} を返すと従来通り {@link GpuScrollLayer} を組んで
     * {@link #planScrollLayer(GpuScrollLayer)} へ渡す。境界のみで {@code NONE} 等を返せる実装では {@code GpuClip} 構築を省ける。
     */
    default com.nishiyu.enderframelib.core.paint.RetainPlan planScrollLayerBounds(
            String key, Object token, Matrix2D pose, int x0, int y0, int x1, int y1) {
        return null;
    }

    /** 保持済みの層を、今の節の位置に合わせて（整数画素にずらして）重ねる。 */
    default void drawScrollLayer(GpuScrollLayer layer) {}

    /** 以降の描画を、保持する層へ入れる。 */
    default void beginScrollLayer(GpuScrollLayer layer) {}

    /** 保持する層への描画を終え、層を保存して重ねる。保持できなければ層を捨てて false。 */
    default boolean endScrollLayer(GpuScrollLayer layer) {
        return true;
    }
}
