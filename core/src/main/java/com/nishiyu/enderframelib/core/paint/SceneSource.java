package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ScrollContentInfo;
import com.nishiyu.enderframelib.core.paint.scene.DisplayScene;
import com.nishiyu.enderframelib.core.paint.scene.DynamicSceneState;

import java.util.List;

/**
 * 表示項目の列を組んだシーンと、その時の動的な値（M3b-4b-4a）。描画器はこれから描き直す範囲（{@link SceneDamage}）と、保持層にできる
 * スクロールコンテナの中身（{@link ScrollContentRanges}）を求める。
 *
 * @param scene    列を組んだシーン
 * @param dynamic  今のスクロール量とビューポート
 * @param contents 中身を 1 つの節にまとめたスクロールコンテナ（{@link SceneDisplayListAdapter#scrollContents}）
 * @param items    シーンから組んだ列（描く列はこれを先頭に持ち、後ろに重ね描き〔キャレット・選択の一覧・インスペクタ〕を足しうる）
 * @param userOffsetsVersion 利用者の描画上の平行移動（{@code UiView#setPaintOffset}）の版。変わったフレームは全面を描き直す
 */
public record SceneSource(DisplayScene scene, DynamicSceneState dynamic, List<ScrollContentInfo> contents, List<DisplayItem> items,
                          int userOffsetsVersion) {
    public SceneSource {
        java.util.Objects.requireNonNull(scene, "scene");
        java.util.Objects.requireNonNull(dynamic, "dynamic");
        contents = contents == null ? List.of() : contents;
        items = items == null ? List.of() : items;
    }

    /** 描く列 {@code drawn} のうち、シーンから組んだ部分の長さ（{@code drawn} がシーンの列で始まらなければ 0）。 */
    public int baseSizeIn(List<DisplayItem> drawn) {
        if (drawn == items) return items.size();
        int n = items.size();
        if (drawn == null || drawn.size() < n) return 0;
        return n == 0 || (drawn.get(0) == items.get(0) && drawn.get(n - 1) == items.get(n - 1)) ? n : 0;
    }
}
