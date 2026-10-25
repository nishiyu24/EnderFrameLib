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
 * @param shaderGradient      シェーダーで描くグラデーションの 1 区間。なければ null
 * @param pointerEventsNone   算出値の {@code pointer-events: none}。ヒットテストはこの部品を透過する
 * @param transitionDelayMs   {@code transition-delay}
 * @param transitionEasing    {@code transition-timing-function}。既定なら null
 * @param transitionProperty  {@code transition-property}。{@code all} なら null
 */
public record RenderHints(
        String parentId,
        String title,
        boolean scrollable,
        float scrollWidth,
        float scrollHeight,
        float shadowBlur,
        ShaderGradient shaderGradient,
        boolean pointerEventsNone,
        int transitionDelayMs,
        String transitionEasing,
        String transitionProperty
) {
    public static final RenderHints NONE = new RenderHints(null, null, false, 0f, 0f, 0f, null, false, 0, null, null);

    public RenderHints withParentId(String v) {
        return new RenderHints(v, title, scrollable, scrollWidth, scrollHeight, shadowBlur, shaderGradient, pointerEventsNone, transitionDelayMs, transitionEasing, transitionProperty);
    }

    public RenderHints withTitle(String v) {
        return new RenderHints(parentId, v, scrollable, scrollWidth, scrollHeight, shadowBlur, shaderGradient, pointerEventsNone, transitionDelayMs, transitionEasing, transitionProperty);
    }

    public RenderHints withScroll(float contentWidth, float contentHeight) {
        return new RenderHints(parentId, title, true, contentWidth, contentHeight, shadowBlur, shaderGradient, pointerEventsNone, transitionDelayMs, transitionEasing, transitionProperty);
    }

    public RenderHints withShadowBlur(float v) {
        return new RenderHints(parentId, title, scrollable, scrollWidth, scrollHeight, v, shaderGradient, pointerEventsNone, transitionDelayMs, transitionEasing, transitionProperty);
    }

    public RenderHints withShaderGradient(ShaderGradient v) {
        return new RenderHints(parentId, title, scrollable, scrollWidth, scrollHeight, shadowBlur, v, pointerEventsNone, transitionDelayMs, transitionEasing, transitionProperty);
    }

    public RenderHints withPointerEventsNone(boolean v) {
        return new RenderHints(parentId, title, scrollable, scrollWidth, scrollHeight, shadowBlur, shaderGradient, v, transitionDelayMs, transitionEasing, transitionProperty);
    }

    public RenderHints withTransition(int delayMs, String easing, String property) {
        return new RenderHints(parentId, title, scrollable, scrollWidth, scrollHeight, shadowBlur, shaderGradient, pointerEventsNone, delayMs, easing, property);
    }
}
