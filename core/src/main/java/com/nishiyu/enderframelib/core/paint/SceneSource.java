package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.css.model.Keyframes;
import com.nishiyu.enderframelib.core.paint.scene.DisplayScene;
import com.nishiyu.enderframelib.core.paint.scene.DynamicSceneState;

import java.util.Map;

/**
 * フレームのシーンと、その時の動的な値・組み方の入力（M3b-4b-4a。M3b-4c-2a からフレームの値 {@link RenderFrame} の一部）。描画器はこれから
 * 表示項目の列を組み（{@link FrameComposer}。M3b-4c-2b-2b からビューは列を持たない）、描き直す範囲（{@link SceneDamage}）と保持層にできる
 * スクロールコンテナの中身（列の階層 {@link FrameStructure}）を求める。
 *
 * @param scene              シーン
 * @param dynamic            今のスクロール量とビューポート
 * @param userOffsetsVersion 利用者の描画上の平行移動（{@code UiView#setPaintOffset}）の版。変わったフレームは全面を描き直す
 * @param keyframes          シーンを組んだ文書のキーフレーム（列を組むときにアニメーションを引く。同じ実体なら組み方は同じ）
 * @param devicePixelRatio   シーンを組んだ装置の画素比
 */
public record SceneSource(DisplayScene scene, DynamicSceneState dynamic, int userOffsetsVersion,
                          Map<String, Keyframes> keyframes, float devicePixelRatio) {
    public SceneSource {
        java.util.Objects.requireNonNull(scene, "scene");
        java.util.Objects.requireNonNull(dynamic, "dynamic");
        keyframes = keyframes != null ? keyframes : Map.of();
    }
}
