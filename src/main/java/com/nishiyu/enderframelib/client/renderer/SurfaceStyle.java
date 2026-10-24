package com.nishiyu.enderframelib.client.renderer;

import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * 面を「どう描くか」の全部。置き場所（ブロック・アイテム・GUI など）の違いは姿勢と大きさだけで、
 * 描き方の違いはここに集まる。{@link SurfaceRenderer#draw} が受け取る唯一の設定。
 *
 * <p>場所ごとの既定は {@code gui} / {@code hud} / {@code billboard} / {@code world} / {@code item} / {@code overlay}。
 * そこから {@code with*} で 1 項目ずつ変える。</p>
 *
 * @param light           パックされたライト
 * @param overlay         パックされたオーバーレイ
 * @param detail          描く部品の種類（距離で間引く）
 * @param depth           重なりの奥行きの積み方
 * @param audio           メディアの音
 * @param flatLayers      重なる要素だけを奥行きで積む（斜めから見る面を平らに保つ）
 * @param hovered         ホバー中の要素 ID。{@code null} ならセッションの現在値、空文字なら無し
 * @param screenSpaceText 文字を画面空間（GUI・HUD）の描き方にするか
 * @param lodColor        遠距離で全体を塗る色（ARGB）。0 なら塗らない
 * @param lodActive       遠距離の描き方にするか
 */
public record SurfaceStyle(int light, int overlay, Detail detail, Depth depth, Audio audio,
                           boolean flatLayers, @Nullable String hovered, boolean screenSpaceText,
                           int lodColor, boolean lodActive) {

    private static final int FULL_BRIGHT = 0xF000F0;

    /** 描く部品の種類。遠いほど減らす。 */
    public record Detail(boolean items, boolean text, boolean rounded) {
        public static final Detail FULL = new Detail(true, true, true);

        /** 世界内の面の間引き。32 ブロックを超えるとアイテム、16 ブロックを超えると文字と角丸を省く。 */
        public static Detail atDistance(double distSqr) {
            return new Detail(distSqr < 1024.0, distSqr < 256.0, distSqr < 256.0);
        }
    }

    /** 重なりの奥行き。{@code Geometry} は面の大きさに比例した厚み、{@code Fixed} は画面空間の固定値。 */
    public sealed interface Depth {
        /** 面の {@code zStep} の {@code scale} 倍。遠いほど大きくして z ファイティングを避ける。 */
        record Geometry(float scale) implements Depth {}
        record Fixed(float step, float sub) implements Depth {}

        static Depth geometry() { return new Geometry(1f); }

        /** 世界内の面の距離による奥行き。遠いほど厚くする。 */
        static Depth atDistance(double distSqr) {
            return new Geometry(distSqr > 1024.0 ? 12.5f : distSqr > 256.0 ? 5f : 1f);
        }
    }

    /** メディアの音。{@code twoD} は画面空間の定位、{@code pos} は 3D のときの発音位置。 */
    public record Audio(boolean play, boolean twoD, @Nullable BlockPos pos) {
        public static final Audio NONE = new Audio(false, true, null);
        public static final Audio SCREEN = new Audio(true, true, null);

        public static Audio at(BlockPos pos) { return new Audio(true, false, pos); }
    }

    // ------------------------------------------------------------------ 場所ごとの既定

    /** 画面（コンテナ画面を含む）。ホバーは呼び出し側がマウスから求めて {@link #withHovered} で渡す。 */
    public static SurfaceStyle gui(boolean playAudio) {
        return new SurfaceStyle(FULL_BRIGHT, OverlayTexture.NO_OVERLAY, Detail.FULL, new Depth.Fixed(0.05f, 0.02f),
                new Audio(playAudio, true, null), false, null, true, 0, false);
    }

    /** 画面に常駐する HUD。要素は重ねても奥行きを積まない。 */
    public static SurfaceStyle hud() {
        return new SurfaceStyle(FULL_BRIGHT, OverlayTexture.NO_OVERLAY, Detail.FULL, new Depth.Fixed(0f, 0f),
                Audio.NONE, false, null, true, 0, false);
    }

    /** エンティティの頭上などに浮かぶビルボード。 */
    public static SurfaceStyle billboard() {
        return new SurfaceStyle(FULL_BRIGHT, OverlayTexture.NO_OVERLAY, Detail.FULL, new Depth.Fixed(-0.01f, -0.005f),
                Audio.NONE, false, null, true, 0, false);
    }

    /** 世界内の面（ブロック・ホログラム）。距離で部品と奥行きを間引く。発光・音・遠距離の塗りは {@code with*} で足す。 */
    public static SurfaceStyle world(int light, int overlay, double distSqr) {
        return new SurfaceStyle(light, overlay, Detail.atDistance(distSqr), Depth.atDistance(distSqr),
                Audio.NONE, true, null, false, 0, false);
    }

    /** 手持ちアイテムの面。音は 2D、文字は 3D。 */
    public static SurfaceStyle item(int light, int overlay) {
        return new SurfaceStyle(light, overlay, Detail.FULL, Depth.geometry(), Audio.NONE, true, null, false, 0, false);
    }

    /** アイテムや地図に重ねる面など、入力もホバーも受けない表示専用の面。 */
    public static SurfaceStyle overlay(int light) {
        return item(light, OverlayTexture.NO_OVERLAY).withHovered("").withAudio(new Audio(false, true, null));
    }

    // ------------------------------------------------------------------ 1 項目ずつ変える

    public SurfaceStyle withLight(int light, int overlay) {
        return new SurfaceStyle(light, overlay, detail, depth, audio, flatLayers, hovered, screenSpaceText, lodColor, lodActive);
    }

    /** 発光（フルブライト）にする。 */
    public SurfaceStyle withFullBright() {
        return withLight(FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
    }

    public SurfaceStyle withDetail(Detail detail) {
        return new SurfaceStyle(light, overlay, detail, depth, audio, flatLayers, hovered, screenSpaceText, lodColor, lodActive);
    }

    public SurfaceStyle withDepth(Depth depth) {
        return new SurfaceStyle(light, overlay, detail, depth, audio, flatLayers, hovered, screenSpaceText, lodColor, lodActive);
    }

    public SurfaceStyle withAudio(Audio audio) {
        return new SurfaceStyle(light, overlay, detail, depth, audio, flatLayers, hovered, screenSpaceText, lodColor, lodActive);
    }

    /** ホバー中の要素 ID を固定する。空文字ならホバー無し。 */
    public SurfaceStyle withHovered(String hovered) {
        return new SurfaceStyle(light, overlay, detail, depth, audio, flatLayers, hovered == null ? "" : hovered,
                screenSpaceText, lodColor, lodActive);
    }

    /** 遠距離（64 ブロック超）では {@code color} で全体を塗る。 */
    public SurfaceStyle withLod(int color, double distSqr) {
        return new SurfaceStyle(light, overlay, detail, depth, audio, flatLayers, hovered, screenSpaceText,
                color, distSqr > 4096.0);
    }
}
