package com.nishiyu.enderframelib.core.model;

import java.util.List;

/**
 * CSS {@code animation} と {@code @keyframes} から解決済みのアニメーション定義。
 *
 * <p>以前は {@code "1000_1_0:0,0,1,1,0,1|100:..."} のような文字列に
 * 組み立てて {@link UIElement} に載せ、描画のたびに解析し直していた。
 * タイポや桁の取り違えが無言で効かないアニメーションになるため、型で持つ。
 */
public record AnimationSpec(
        int durationMs,
        boolean infinite,
        List<Keyframe> frames
) {
    /** アニメーション無し。 */
    public static final AnimationSpec NONE = new AnimationSpec(0, false, List.of());

    public AnimationSpec {
        durationMs = Math.max(1, durationMs);
        frames = frames != null ? List.copyOf(frames) : List.of();
    }

    public boolean isEmpty() {
        return frames.isEmpty();
    }

    /**
     * 1 つのキーフレーム。
     *
     * @param pct 進行度（0〜100）
     * @param tx  X 平行移動
     * @param ty  Y 平行移動
     * @param sx  X 拡大率
     * @param sy  Y 拡大率
     * @param rot 回転角（度）
     * @param op  不透明度
     */
    public record Keyframe(float pct, float tx, float ty, float sx, float sy, float rot, float op) {
    }
}
