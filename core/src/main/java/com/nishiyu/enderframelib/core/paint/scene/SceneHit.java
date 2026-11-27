package com.nishiyu.enderframelib.core.paint.scene;

import com.nishiyu.enderframelib.core.dom.node.HtmlNode;

/**
 * {@link SceneHitTest} が返す当たり判定の結果（M3b-3。旧 {@code UIHitResult} を置き換える）。
 *
 * @param layer         当たった {@link SceneLayer}（スクロールバーのときは所有者コンテナの節）
 * @param localX        要素（または断片）の左上からの局所 X 座標（CSS px）
 * @param localY        要素（または断片）の左上からの局所 Y 座標（CSS px）
 * @param scrollbarPart スクロールバー部品に当たったときの接尾辞（{@code _sb_vt}/{@code _sb_v}/{@code _sb_ht}/{@code _sb_h}）。通常要素は {@code null}
 * @param absX          当たった要素（または断片）の配置済み X 座標（祖先変形の前。CSS px）
 * @param absY          当たった要素（または断片）の配置済み Y 座標（祖先変形の前。CSS px）
 * @param run           文字または分割インライン断片に当たったときの {@link SceneRun}（通常要素・スクロールバーは {@code null}）
 */
public record SceneHit(
        SceneLayer layer,
        double localX,
        double localY,
        String scrollbarPart,
        float absX,
        float absY,
        SceneRun run
) {
    /** 当たった要素の元の DOM ノード（無ければ null）。 */
    public HtmlNode node() {
        return layer != null ? layer.node : null;
    }

    /** 文書内の DOM 要素の数値 ID（生成節・疑似要素は 0）。 */
    public int nodeId() {
        return layer != null ? layer.nodeId : 0;
    }

    /** 持ち主のノードキー（無ければ空文字列）。 */
    public String baseId() {
        return layer != null && layer.node != null ? layer.node.key() : "";
    }

    /** 当たった対象の幅（断片に当たっていれば断片の幅）。 */
    public float width() {
        if (run != null) return run.w();
        return layer != null ? layer.w : 0f;
    }

    /** 当たった対象の高さ（断片に当たっていれば断片の高さ）。 */
    public float height() {
        if (run != null) return run.h();
        return layer != null ? layer.h : 0f;
    }

    public boolean isScrollbar() {
        return scrollbarPart != null;
    }

    /** 文字断片ならその語、そうでなければ null。 */
    public String text() {
        return run != null ? run.text() : null;
    }
}
