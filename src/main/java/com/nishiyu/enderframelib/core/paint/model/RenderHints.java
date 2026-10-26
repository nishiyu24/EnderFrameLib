package com.nishiyu.enderframelib.core.paint.model;

/**
 * 描画項目が描画・ヒットテスト・アニメーションのために持つ型付きの付帯情報。
 * 以前は {@code DisplayItem.events} に文字列キー（{@code mc-parent} など）と CSV で詰めていて、
 * 描画側が毎フレーム {@code split} と {@code parseFloat} で読み戻していた。
 *
 * <p>{@code events} は DOM 由来の属性（{@code onclick}・{@code data-*}・書体の指定）だけを持つ。
 *
 * @param parentId            変形・不透明度を引き継ぐ親の錨の id。なければ null
 * @param title               ツールチップの文（{@code title} / {@code data-tooltip}）。なければ null
 * @param scrollable          ホイールでスクロールする箱か
 * @param scrollWidth         スクロールできる中身の幅（{@code scrollable} のとき）
 * @param scrollHeight        同じく高さ
 * @param shadowBlur          ぼかした外側の影の部品のぼかし半径（px）。0 なら影の部品ではない。
 *                            部品の矩形はぼかしが消える所までの広さで、影の形はそこから内側
 * @param shadowShape         影の部品の形の付帯情報（{@link ShadowShape}）。影の部品でなければ null
 * @param shaderGradient      シェーダーで描くグラデーションの 1 区間。なければ null
 * @param pointerEventsNone   算出値の {@code pointer-events: none}。ヒットテストはこの部品を透過する
 * @param transitionDelayMs   {@code transition-delay}
 * @param transitionEasing    {@code transition-timing-function}。既定なら null
 * @param transitionProperty  {@code transition-property}。{@code all} なら null
 * @param layer               この錨の箱をレイヤーに描いて合成する指定。なければ null
 */
public record RenderHints(
        String parentId,
        String title,
        boolean scrollable,
        float scrollWidth,
        float scrollHeight,
        float shadowBlur,
        ShadowShape shadowShape,
        ShaderGradient shaderGradient,
        boolean pointerEventsNone,
        int transitionDelayMs,
        String transitionEasing,
        String transitionProperty,
        LayerSpec layer
) {
    public static final RenderHints NONE = new RenderHints(null, null, false, 0f, 0f, 0f, null, null, false, 0, null, null, null);

    public RenderHints withParentId(String v) {
        return new RenderHints(v, title, scrollable, scrollWidth, scrollHeight, shadowBlur, shadowShape, shaderGradient, pointerEventsNone, transitionDelayMs, transitionEasing, transitionProperty, layer);
    }

    public RenderHints withTitle(String v) {
        return new RenderHints(parentId, v, scrollable, scrollWidth, scrollHeight, shadowBlur, shadowShape, shaderGradient, pointerEventsNone, transitionDelayMs, transitionEasing, transitionProperty, layer);
    }

    public RenderHints withScroll(float contentWidth, float contentHeight) {
        return new RenderHints(parentId, title, true, contentWidth, contentHeight, shadowBlur, shadowShape, shaderGradient, pointerEventsNone, transitionDelayMs, transitionEasing, transitionProperty, layer);
    }

    public RenderHints withShadowBlur(float v) {
        return new RenderHints(parentId, title, scrollable, scrollWidth, scrollHeight, v, shadowShape, shaderGradient, pointerEventsNone, transitionDelayMs, transitionEasing, transitionProperty, layer);
    }

    public RenderHints withShaderGradient(ShaderGradient v) {
        return new RenderHints(parentId, title, scrollable, scrollWidth, scrollHeight, shadowBlur, shadowShape, v, pointerEventsNone, transitionDelayMs, transitionEasing, transitionProperty, layer);
    }

    public RenderHints withPointerEventsNone(boolean v) {
        return new RenderHints(parentId, title, scrollable, scrollWidth, scrollHeight, shadowBlur, shadowShape, shaderGradient, v, transitionDelayMs, transitionEasing, transitionProperty, layer);
    }

    public RenderHints withTransition(int delayMs, String easing, String property) {
        return new RenderHints(parentId, title, scrollable, scrollWidth, scrollHeight, shadowBlur, shadowShape, shaderGradient, pointerEventsNone, delayMs, easing, property, layer);
    }

    public RenderHints withLayer(LayerSpec v) {
        return new RenderHints(parentId, title, scrollable, scrollWidth, scrollHeight, shadowBlur, shadowShape, shaderGradient, pointerEventsNone, transitionDelayMs, transitionEasing, transitionProperty, v);
    }

    public RenderHints withShadowShape(ShadowShape v) {
        return new RenderHints(parentId, title, scrollable, scrollWidth, scrollHeight, shadowBlur, v, shaderGradient, pointerEventsNone, transitionDelayMs, transitionEasing, transitionProperty, layer);
    }

    /**
     * ぼかした影の部品の形。外側の影は部品の矩形（ぼかしが消える所まで）から内側へ {@code SHADOW_REACH} だけ縮めた所が影の形で、
     * 箱そのもの（{@code x0..y1}・角丸）は影を抜く「穴」。内側の影は {@code x0..y1}・角丸が影の無い「穴」で、部品の矩形（パディングボックス）のうち
     * その外が影になる。
     *
     * @param inset 内側の影か
     */
    public record ShadowShape(boolean inset, float x0, float y0, float x1, float y1,
                              float rTL, float rTR, float rBR, float rBL,
                              float rTLv, float rTRv, float rBRv, float rBLv) {
        /** 円の角（縦の半径は横と同じ）。 */
        public ShadowShape(boolean inset, float x0, float y0, float x1, float y1,
                           float rTL, float rTR, float rBR, float rBL) {
            this(inset, x0, y0, x1, y1, rTL, rTR, rBR, rBL, rTL, rTR, rBR, rBL);
        }
    }
}
