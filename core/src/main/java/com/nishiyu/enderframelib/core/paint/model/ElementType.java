package com.nishiyu.enderframelib.core.paint.model;


import java.util.Locale;

/**
 * DisplayItem の型分類。
 */
public enum ElementType {
    NODE,
    RECT,
    TEXT,
    IMAGE,
    VIDEO,
    CANVAS,
    SCENE,
    TEXTAREA,
    SELECT,
    INPUT,
    RANGE,
    PROGRESS,
    TOGGLE,
    CHECKBOX,
    RADIO,
    CIRCLE_BORDER,
    /** 角丸の枠線（枠の領域だけを塗る。{@link RenderMeta#roundedBorder()}）。 */
    ROUNDED_BORDER,
    BUTTON,
    A;

    /**
     * ソフトウェア描画の振り分け先（{@link #rasterRoute}）。種類ごとの描き方はここへ 1 回だけ書き、描画側は経路で分ける。
     * 種類を足したら {@link #rasterRoute} の switch（default なし）がコンパイルで直し漏れを知らせる。
     */
    public enum RasterRoute {
        /** 3D シーン。 */ SCENE,
        /** 複数行の入力欄。 */ TEXTAREA,
        /** スライダー。背景も描かない。 */ RANGE,
        /** ゲージ。 */ PROGRESS,
        /** チェックボックス・ラジオ・トグル。 */ TOGGLE,
        /** 画像・動画・キャンバス。 */ IMAGE,
        /** 文字を常に描く（入力欄・select。値が空でも枠内に入れる）。 */ TEXT_ALWAYS,
        /** 文字があれば描く。 */ TEXT_IF_ANY,
        /** 背景・枠だけ（角丸の枠。専用の経路が先に描いて戻る）。 */ BORDER_ONLY
    }

    /** ソフトウェア描画の振り分け先。{@code default} を持たない網羅形。 */
    public RasterRoute rasterRoute() {
        return switch (this) {
            case SCENE -> RasterRoute.SCENE;
            case TEXTAREA -> RasterRoute.TEXTAREA;
            case RANGE -> RasterRoute.RANGE;
            case PROGRESS -> RasterRoute.PROGRESS;
            case TOGGLE, CHECKBOX, RADIO -> RasterRoute.TOGGLE;
            case IMAGE, VIDEO, CANVAS -> RasterRoute.IMAGE;
            case INPUT, SELECT -> RasterRoute.TEXT_ALWAYS;
            case NODE, RECT, TEXT, BUTTON, A, CIRCLE_BORDER -> RasterRoute.TEXT_IF_ANY; // 円の枠は描けなかったときだけ文字へ落ちる
            case ROUNDED_BORDER -> RasterRoute.BORDER_ONLY;
        };
    }

    /** 時刻や外の事情（画像の読み込み・動画・キャンバス・3D シーン）で、表示リストが変わらなくても絵が変わりうるか。 */
    public boolean isExternallyDriven() {
        return switch (this) {
            case IMAGE, VIDEO, CANVAS, SCENE -> true;
            case NODE, RECT, TEXT, TEXTAREA, SELECT, INPUT, RANGE, PROGRESS, TOGGLE, CHECKBOX, RADIO,
                 CIRCLE_BORDER, ROUNDED_BORDER, BUTTON, A -> false;
        };
    }

    /** 入力欄のカーソル・ゲージの値など、部品の状態で絵が変わるか（{@link #isExternallyDriven} とは別）。 */
    public boolean hasLiveWidgetState() {
        return switch (this) {
            case INPUT, TEXTAREA, SELECT, PROGRESS -> true;
            case NODE, RECT, TEXT, IMAGE, VIDEO, CANVAS, SCENE, RANGE, TOGGLE, CHECKBOX, RADIO,
                 CIRCLE_BORDER, ROUNDED_BORDER, BUTTON, A -> false;
        };
    }

    /** 文字・画像・3D など、四角形の描画ではなくバッファ（部品固有の描き方）へ出す種類。 */
    public boolean drawsToBuffer() {
        return switch (this) {
            case CANVAS, SCENE, TEXTAREA, IMAGE, VIDEO, INPUT, SELECT, RANGE, PROGRESS, TOGGLE, CHECKBOX, RADIO -> true;
            case NODE, RECT, TEXT, CIRCLE_BORDER, ROUNDED_BORDER, BUTTON, A -> false;
        };
    }

    /** 押下を自分で受け取る種類（アクションの有無に依らず、押下を消費する）。 */
    public boolean capturesPress() {
        return switch (this) {
            case SCENE, TEXTAREA, INPUT, SELECT, RANGE -> true;
            case NODE, RECT, TEXT, IMAGE, VIDEO, CANVAS, PROGRESS, TOGGLE, CHECKBOX, RADIO, CIRCLE_BORDER, ROUNDED_BORDER, BUTTON, A -> false;
        };
    }

    public boolean isInput() {
        return switch (this) {
            case INPUT, TEXTAREA, SELECT, RANGE, TOGGLE, CHECKBOX, RADIO, BUTTON, A, SCENE -> true;
            case NODE, RECT, TEXT, IMAGE, VIDEO, CANVAS, PROGRESS, CIRCLE_BORDER, ROUNDED_BORDER -> false;
        };
    }

    public static ElementType fromString(String str) {
        if (str == null) return NODE;
        if (str.startsWith("img")) return IMAGE;
        return switch (str.toLowerCase(Locale.ROOT)) {
            case "node" -> NODE;
            case "rect" -> RECT;
            case "text" -> TEXT;
            case "image", "img" -> IMAGE;
            case "video" -> VIDEO;
            case "canvas" -> CANVAS;
            case "scene" -> SCENE;
            case "textarea" -> TEXTAREA;
            case "select" -> SELECT;
            case "input" -> INPUT;
            case "range" -> RANGE;
            case "progress", "meter" -> PROGRESS;
            case "toggle" -> TOGGLE;
            case "checkbox" -> CHECKBOX;
            case "radio" -> RADIO;
            case "circle_border" -> CIRCLE_BORDER;
            case "rounded_border" -> ROUNDED_BORDER;
            case "button" -> BUTTON;
            case "a" -> A;
            default -> NODE;
        };
    }
}
