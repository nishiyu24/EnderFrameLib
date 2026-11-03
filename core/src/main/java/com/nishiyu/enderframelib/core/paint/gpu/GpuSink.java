package com.nishiyu.enderframelib.core.paint.gpu;

import com.nishiyu.enderframelib.core.paint.ClipShape;
import com.nishiyu.enderframelib.core.paint.FilterOp;
import com.nishiyu.enderframelib.core.paint.model.ShaderGradient;

import java.util.List;
import com.nishiyu.enderframelib.core.raster.Matrix2D;
import com.nishiyu.enderframelib.core.raster.Pixmap;
import com.nishiyu.enderframelib.core.text.font.GlyphBitmap;

/**
 * {@link com.nishiyu.enderframelib.core.paint.software.SoftwareRenderBackend} が「単色の角丸矩形」と「角丸の枠線」を自分で塗る代わりに渡す先。
 * GPU 版がこれを実装し、図形だけを GPU のシェーダーで描く（文字・画像・グラデーション・影は従来どおり CPU が描き、
 * 描く順は呼び出しの順で保たれる）。
 *
 * <p>どちらも、受け取れなければ {@code false} を返す。その場合は CPU が塗る。座標は項目の局所の空間
 * （{@code pose} でデバイス画素へ写る）。角の順は左上・右上・右下・左下。
 */
public interface GpuSink {

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
                               com.nishiyu.enderframelib.core.paint.BlendMode blend) {
        if (blend != com.nishiyu.enderframelib.core.paint.BlendMode.NORMAL) return false;
        return beginGroup(opacity, filters, map, masked, maskLuminance);
    }

    /**
     * {@code clip-path} と {@code backdrop-filter} の指定。{@code pose} は錨の姿勢（箱の座標 → デバイス画素）。
     * {@code clip} は層の絵を切る図形、{@code backdrop} と {@code backdropShape}（角丸の箱）は、層の後ろ（親の絵）を加工して貼る指定。
     */
    record GroupExtras(Matrix2D pose, ClipShape clip, List<FilterOp> backdrop, ClipShape backdropShape) {}

    /** {@link GroupExtras} 付きの層を開く。非対応なら {@code false}（CPU が層を持つ）。{@code extras} が null なら従来の版。 */
    default boolean beginGroup(float opacity, List<FilterOp> filters, double[] map, boolean masked, boolean maskLuminance,
                               com.nishiyu.enderframelib.core.paint.BlendMode blend, GroupExtras extras) {
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

    /** 同時に渡せるクリップの数。これを超える項目は CPU が塗る。 */
    int MAX_CLIPS = 4;

    /**
     * クリップの 1 つ。{@code inv} はデバイス画素を持ち主の空間へ戻す変換、{@code scale} は持ち主の空間の 1 単位が何画素か。
     */
    record Clip(Matrix2D inv, float scale, float x0, float y0, float x1, float y1, float[] rx, float[] ry) {}

    /** 角丸矩形を単色（非乗算の ARGB）で塗る。{@code opacity} は 0〜1。 */
    boolean fillRoundRect(Matrix2D pose, float x0, float y0, float x1, float y1, float[] rx, float[] ry,
                          int argb, Clip[] clips, float opacity);

    /**
     * 角丸の枠線（外側の角丸矩形から内側を抜いた輪）を塗る。{@code colors} と {@code widths} は上・右・下・左の順。
     * 辺の色は、外側の角から内側の角を結ぶ線で分ける。{@code innerEmpty} なら内側が無い（外側全部が枠）。
     */
    boolean strokeRoundBorder(Matrix2D pose, float x0, float y0, float x1, float y1, float[] rx, float[] ry,
                              float ix0, float iy0, float ix1, float iy1, float[] irx, float[] iry, boolean innerEmpty,
                              int[] colors, float[] widths, Clip[] clips, float opacity);

    /** 角丸矩形をシェーダーのグラデーション（{@link ShaderGradient} の 1 区間）で塗る。 */
    default boolean fillGradient(Matrix2D pose, float x0, float y0, float x1, float y1, float[] rx, float[] ry,
                                 ShaderGradient gradient, Clip[] clips, float opacity) {
        return false;
    }

    /**
     * 外側のぼかし影。{@code (qx0,qy0)-(qx1,qy1)} が塗る範囲、{@code (sx0,sy0)-(sx1,sy1)} と {@code srx/sry} が影の形、
     * {@code sigma} がガウスの標準偏差。{@code hole}（{@code {x0,y0,x1,y1}}・角丸 {@code hrx/hry}）があればそこを抜く（なければ {@code null}）。
     */
    default boolean outerShadow(Matrix2D pose, float qx0, float qy0, float qx1, float qy1,
                                float sx0, float sy0, float sx1, float sy1, float[] srx, float[] sry, float sigma,
                                float[] hole, float[] hrx, float[] hry, int argb, Clip[] clips, float opacity) {
        return false;
    }

    /**
     * 内側のぼかし影。箱 {@code (qx0,qy0)-(qx1,qy1)}（角丸 {@code rx/ry}）の中で、{@code hole} の外側を {@code sigma} でぼかして塗る。
     */
    default boolean insetShadow(Matrix2D pose, float qx0, float qy0, float qx1, float qy1, float[] rx, float[] ry,
                                float sigma, float[] hole, float[] hrx, float[] hry, int argb, Clip[] clips, float opacity) {
        return false;
    }

    /**
     * 字形 1 つを、デバイス画素の格子に揃えて置く（回転なしの文字）。{@code (bx, by)} は字形の左上の画素、{@code argb} は非乗算の文字色。
     * GPU 側は字形アトラスに載せて被覆率を引く。クリップは {@code clips}、不透明度は {@code opacity}。
     */
    default boolean drawGlyph(GlyphBitmap glyph, int bx, int by, int argb, Clip[] clips, float opacity) {
        return false;
    }

    /**
     * 画像を貼る。{@code (x0,y0)-(x1,y1)} と {@code rx/ry} が角丸の箱、{@code (bx0,by0)-(bx1,by1)} が描く範囲、
     * {@code (px0,py0)} が画像の左上、{@code sx/sy} が局所の 1 単位あたりの画像の画素数。双一次補間の 1 回標本（縮小で複数点が要るときは {@code false}）。
     */
    default boolean drawImage(Matrix2D pose, Pixmap image, float bx0, float by0, float bx1, float by1,
                              float px0, float py0, float sx, float sy,
                              float x0, float y0, float x1, float y1, float[] rx, float[] ry, Clip[] clips, float opacity) {
        return drawImage(pose, image, bx0, by0, bx1, by1, px0, py0, sx, sy, x0, y0, x1, y1, rx, ry, 1, 0f, 0f, clips, opacity);
    }

    /**
     * 縮小で複数点標本が要る版。各画素の中を {@code taps x taps} 点（間隔は画像の画素で {@code stepX/stepY}）で標本化して平均する。
     */
    default boolean drawImage(Matrix2D pose, Pixmap image, float bx0, float by0, float bx1, float by1,
                              float px0, float py0, float sx, float sy,
                              float x0, float y0, float x1, float y1, float[] rx, float[] ry,
                              int taps, float stepX, float stepY, Clip[] clips, float opacity) {
        return false;
    }

    /**
     * 字形の縁取りを、デバイス画素の格子に揃えて置く（回転なしの文字）。{@code sdf} は輪郭からの符号付き距離の字形
     * （128 = 輪郭、内側が大きい、広がり {@code LoadedFace.SDF_SPREAD} 画素で 128 段階）、{@code half} は縁取りの幅の半分（画素）。
     * 非対応なら {@code false}（CPU が描く）。
     */
    default boolean drawGlyphStroke(GlyphBitmap sdf, int bx, int by, float half, int argb, Clip[] clips, float opacity) {
        return false;
    }

    /**
     * 外部（ホストが持つ）テクスチャをそのまま貼る（画素の複製・読み戻しなし）。引数の意味は {@link #drawImage} と同じで、
     * {@code (px0,py0)} と {@code sx/sy} は <b>テクスチャ全体の左上・画素</b>を基準にした写像（範囲指定・上下反転は呼び出し側が織り込み済み。
     * {@code sy} が負なら上下反転）。{@code (imgW,imgH)} はテクスチャの画素数。非対応なら {@code false}（画像の項目として従来の解決へ退く）。
     */
    default boolean drawTexture(Matrix2D pose, long handle, int imgW, int imgH, float bx0, float by0, float bx1, float by1,
                                float px0, float py0, float sx, float sy,
                                float x0, float y0, float x1, float y1, float[] rx, float[] ry, Clip[] clips, float opacity) {
        return false;
    }

    /**
     * 回転・せん断などの姿勢の下で、字形を双一次補間で貼る。{@code (ox,oy)} は字形の原点（局所）、{@code s} は局所 1 単位あたりの字形の画素数、
     * {@code (bx0,by0)-(bx1,by1)} が描く範囲（局所）。
     */
    default boolean drawGlyphTransformed(Matrix2D pose, GlyphBitmap glyph, float ox, float oy, float s,
                                         float bx0, float by0, float bx1, float by1, int argb, Clip[] clips, float opacity) {
        return false;
    }

    /**
     * 破線・点線の角丸枠線（4 辺が同じ太さ）。引数は {@link #strokeRoundBorder} に加え、
     * 中心線の半サイズ {@code (bx,by)}・箱の中心 {@code (cxm,cym)}・中心線の角の半径 {@code pcx/pcy}（左上・右上・右下・左下）・
     * 周期 {@code period}・線の長さ {@code dash}・丸い点か {@code round}・太さ {@code thickness}。
     */
    default boolean strokeDashedBorder(Matrix2D pose, float x0, float y0, float x1, float y1, float[] rx, float[] ry,
                                       float ix0, float iy0, float ix1, float iy1, float[] irx, float[] iry, boolean innerEmpty,
                                       int[] colors, float[] widths,
                                       float bx, float by, float cxm, float cym, float[] pcx, float[] pcy,
                                       float period, float dash, boolean round, float thickness,
                                       Clip[] clips, float opacity) {
        return false;
    }

    /**
     * 円の枠線。中心 {@code (cx,cy)}・外半径 {@code outerR}・内半径 {@code innerR}（0 以下なら塗りつぶし）。
     * {@code colors} は上・右・下・左の非乗算 ARGB。{@code (bx0,by0)-(bx1,by1)} が描く範囲。
     */
    default boolean strokeCircleBorder(Matrix2D pose, float cx, float cy, float outerR, float innerR,
                                       float bx0, float by0, float bx1, float by1, int[] colors, Clip[] clips, float opacity) {
        return false;
    }
}
