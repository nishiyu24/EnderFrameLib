package com.nishiyu.enderframelib.client.renderer;

/**
 * クライアントUIおよび描画パイプライン全体で共通利用されるZ深度（レイヤーオフセット）の定数集。
 */
public final class ZLayers {

    private ZLayers() {}

    /** 要素階層ごとの基本Zステップ（重なり順制御用） */
    public static final float ELEMENT_Z_STEP = 0.002f;

    /** 要素内の微小サブZオフセット（装飾・下線・キャレットなど） */
    public static final float SUB_Z_OFFSET = 0.0005f;

    /** 3DエンティティプレビューのZオフセット */
    public static final float ENTITY_PREVIEW_Z = 20.0f;

    /** 3DシーンプレビューのZオフセット */
    public static final float SCENE_PREVIEW_Z = 35.0f;

    /** フォーカスインジケータ枠線のオーバーレイZ層 */
    public static final float FOCUS_INDICATOR_OVERLAY_Z = 280.0f;

    /** セレクトドロップダウンのポップアップオーバーレイZ層 */
    public static final float DROPDOWN_POPUP_OVERLAY_Z = 300.0f;

    /** ドラッグ＆ドロッププレビューのオーバーレイZ層 */
    public static final float DRAG_DROP_OVERLAY_Z = 320.0f;

    /** DevTools（インスペクタ）のオーバーレイZ層 */
    public static final float DEVTOOLS_OVERLAY_Z = 350.0f;
}
