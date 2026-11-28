package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.paint.model.ScrollContentInfo;
import com.nishiyu.enderframelib.core.paint.scene.DisplayScene;
import com.nishiyu.enderframelib.core.paint.scene.DynamicSceneState;

import java.util.List;

/**
 * フレームの表示項目の列を組んだシーンと、その時の動的な値（M3b-4b-4a。M3b-4c-2a からフレームの値 {@link RenderFrame} の一部）。描画器はこれから
 * 描き直す範囲（{@link SceneDamage}）と、保持層にできるスクロールコンテナの中身（{@link ScrollContentRanges}）を求める。
 *
 * @param scene    列を組んだシーン
 * @param dynamic  今のスクロール量とビューポート
 * @param contents 中身を 1 つの節にまとめたスクロールコンテナ（{@link SceneDisplayListAdapter#scrollContents}）
 * @param userOffsetsVersion 利用者の描画上の平行移動（{@code UiView#setPaintOffset}）の版。変わったフレームは全面を描き直す
 */
public record SceneSource(DisplayScene scene, DynamicSceneState dynamic, List<ScrollContentInfo> contents, int userOffsetsVersion) {
    public SceneSource {
        java.util.Objects.requireNonNull(scene, "scene");
        java.util.Objects.requireNonNull(dynamic, "dynamic");
        contents = contents == null ? List.of() : contents;
    }
}
