package com.nishiyu.enderframelib.client.renderer;

import com.nishiyu.enderframelib.core.paint.ExcludedNodes;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * 面を「どう描くか」の全部。置き場所（ブロック・アイテム・GUI など）の違いは姿勢と大きさだけで、
 * 描き方の違いはここに集まる。{@link SurfaceRenderer#draw} が受け取る唯一の設定。
 *
 * <p>場所ごとの既定は {@code gui} / {@code hud} / {@code billboard} / {@code world} / {@code item} / {@code overlay}。
 * そこから {@code with*} で 1 項目ずつ変える。中身はすべてコアの描画（{@link MinecraftHostSurface}）が描くので、
 * 旧レンダラの奥行きの積み方・距離での部品の間引き・層の平坦化の指定は M3a で無くなった。</p>
 *
 * @param light           パックされたライト（面の上に重ねるツールチップの文字に使う）
 * @param audio           メディアの音
 * @param hovered         ホバー中の要素 ID。{@code null} ならセッションの現在値、空文字なら無し
 * @param screenSpaceText 画面に平行に描く面か（GUI・HUD。文字の解像度を画面の倍率に合わせ、GUI の照明に戻す）
 * @param lodColor        遠距離の塗りの色（ARGB。アルファ 0 なら遠距離では何も描かない）
 * @param lodActive       遠距離の塗りだけで描くか
 * @param exclude         描かない節（無ければ null。HUD は {@code data-mount-entity} の要素をエンティティの上に描くので HUD に描かない。
 *                        列を絞らずにフレームの値で省くので、静止したフレームは描画器が前の画素を使い回せる。M3b-4c-2b-1）
 */
public record SurfaceStyle(int light, Audio audio, @Nullable String hovered, boolean screenSpaceText,
                           int lodColor, boolean lodActive, @Nullable ExcludedNodes exclude) {

    /** エンティティの上に描く要素（{@code data-mount-entity}）。HUD には描かない。 */
    public static final ExcludedNodes MOUNTED_TO_ENTITY = new ExcludedNodes("data-mount-entity");

    private static final int FULL_BRIGHT = 0xF000F0;

    /** メディアの音を鳴らすか・2D（画面）か・鳴らす位置。 */
    public record Audio(boolean play, boolean twoD, @Nullable BlockPos pos) {
        public static final Audio NONE = new Audio(false, true, null);
        public static final Audio SCREEN = new Audio(true, true, null);

        public static Audio at(BlockPos pos) { return new Audio(true, false, pos); }
    }

    // ------------------------------------------------------------------ 場所ごとの既定

    public static SurfaceStyle gui(boolean playAudio) {
        return new SurfaceStyle(FULL_BRIGHT, new Audio(playAudio, true, null), null, true, 0, false, null);
    }

    public static SurfaceStyle hud() {
        return new SurfaceStyle(FULL_BRIGHT, Audio.NONE, null, true, 0, false, MOUNTED_TO_ENTITY);
    }

    public static SurfaceStyle billboard() {
        return new SurfaceStyle(FULL_BRIGHT, Audio.NONE, null, true, 0, false, null);
    }

    /** ワールドに置いた面（ブロック・ホログラム）。 */
    public static SurfaceStyle world(int light) {
        return new SurfaceStyle(light, Audio.NONE, null, false, 0, false, null);
    }

    public static SurfaceStyle item(int light) {
        return new SurfaceStyle(light, Audio.NONE, null, false, 0, false, null);
    }

    public static SurfaceStyle overlay(int light) {
        return item(light).withHovered("").withAudio(new Audio(false, true, null));
    }

    // ------------------------------------------------------------------ 1 項目ずつ変える

    public SurfaceStyle withAudio(Audio audio) {
        return new SurfaceStyle(light, audio, hovered, screenSpaceText, lodColor, lodActive, exclude);
    }

    /** ホバー中の要素 ID を固定する。空文字ならホバー無し。 */
    public SurfaceStyle withHovered(String hovered) {
        return new SurfaceStyle(light, audio, hovered == null ? "" : hovered, screenSpaceText, lodColor, lodActive, exclude);
    }

    /** 遠距離（64 ブロック超）では {@code color} で全体を塗る。 */
    public SurfaceStyle withLod(int color, double distSqr) {
        return new SurfaceStyle(light, audio, hovered, screenSpaceText, color, distSqr > 4096.0, exclude);
    }
}
