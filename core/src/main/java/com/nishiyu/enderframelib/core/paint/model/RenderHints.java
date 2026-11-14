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
 * @param parentId            変形・不透明度を引き継ぐ親の錨の id。なければ null
 * @param parentKey           変形・不透明度を引き継ぐ親の錨の 64-bit 整数鍵。未割り当てなら 0L（parentId から導出）
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
 * @param skew                錨の静的な skewX（度）。{@code M = T · R · skewX · S}。アニメーション中は描画側が値を置き換える
 */
public record RenderHints(
        String parentId,
        long parentKey,
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
        LayerSpec layer,
        float skew
) {
    public RenderHints {
        if (parentKey == 0L && parentId != null && !parentId.isEmpty()) {
            parentKey = DisplayItem.keyOf(parentId);
        }
    }

    /** 14 引数互換コンストラクタ（parentKey 自動解決）。 */
    public RenderHints(String parentId, String title, boolean scrollable, float scrollWidth, float scrollHeight, float shadowBlur,
                       ShadowShape shadowShape, ShaderGradient shaderGradient, boolean pointerEventsNone, int transitionDelayMs,
                       String transitionEasing, String transitionProperty, LayerSpec layer, float skew) {
        this(parentId, 0L, title, scrollable, scrollWidth, scrollHeight, shadowBlur, shadowShape, shaderGradient, pointerEventsNone,
                transitionDelayMs, transitionEasing, transitionProperty, layer, skew);
    }

    /** せん断なし（既存の呼び出し用）。 */
    public RenderHints(String parentId, String title, boolean scrollable, float scrollWidth, float scrollHeight, float shadowBlur,
                       ShadowShape shadowShape, ShaderGradient shaderGradient, boolean pointerEventsNone, int transitionDelayMs,
                       String transitionEasing, String transitionProperty, LayerSpec layer) {
        this(parentId, 0L, title, scrollable, scrollWidth, scrollHeight, shadowBlur, shadowShape, shaderGradient, pointerEventsNone,
                transitionDelayMs, transitionEasing, transitionProperty, layer, 0f);
    }

    public static final RenderHints NONE = new RenderHints(null, 0L, null, false, 0f, 0f, 0f, null, null, false, 0, null, null, null, 0f);

    public RenderHints withParentId(String v) {
        if (java.util.Objects.equals(parentId, v) && (v == null || v.isEmpty() || parentKey == DisplayItem.keyOf(v))) return this;
        return new RenderHints(v, 0L, title, scrollable, scrollWidth, scrollHeight, shadowBlur, shadowShape, shaderGradient, pointerEventsNone, transitionDelayMs, transitionEasing, transitionProperty, layer, skew);
    }

    public RenderHints withParent(String id, long key) {
        if (java.util.Objects.equals(parentId, id) && (key == 0L ? (id == null || id.isEmpty() ? parentKey == 0L : parentKey == DisplayItem.keyOf(id)) : parentKey == key)) {
            return this;
        }
        return new RenderHints(id, key, title, scrollable, scrollWidth, scrollHeight, shadowBlur, shadowShape, shaderGradient, pointerEventsNone, transitionDelayMs, transitionEasing, transitionProperty, layer, skew);
    }

    public RenderHints withParentKey(long key) {
        if (parentKey == key) return this;
        return new RenderHints(parentId, key, title, scrollable, scrollWidth, scrollHeight, shadowBlur, shadowShape, shaderGradient, pointerEventsNone, transitionDelayMs, transitionEasing, transitionProperty, layer, skew);
    }

    public RenderHints withTitle(String v) {
        if (java.util.Objects.equals(title, v)) return this;
        return new RenderHints(parentId, parentKey, v, scrollable, scrollWidth, scrollHeight, shadowBlur, shadowShape, shaderGradient, pointerEventsNone, transitionDelayMs, transitionEasing, transitionProperty, layer, skew);
    }

    public RenderHints withScroll(float contentWidth, float contentHeight) {
        if (scrollable && scrollWidth == contentWidth && scrollHeight == contentHeight) return this;
        return new RenderHints(parentId, parentKey, title, true, contentWidth, contentHeight, shadowBlur, shadowShape, shaderGradient, pointerEventsNone, transitionDelayMs, transitionEasing, transitionProperty, layer, skew);
    }

    public RenderHints withShadowBlur(float v) {
        if (shadowBlur == v) return this;
        return new RenderHints(parentId, parentKey, title, scrollable, scrollWidth, scrollHeight, v, shadowShape, shaderGradient, pointerEventsNone, transitionDelayMs, transitionEasing, transitionProperty, layer, skew);
    }

    public RenderHints withShaderGradient(ShaderGradient v) {
        if (java.util.Objects.equals(shaderGradient, v)) return this;
        return new RenderHints(parentId, parentKey, title, scrollable, scrollWidth, scrollHeight, shadowBlur, shadowShape, v, pointerEventsNone, transitionDelayMs, transitionEasing, transitionProperty, layer, skew);
    }

    public RenderHints withPointerEventsNone(boolean v) {
        if (pointerEventsNone == v) return this;
        return new RenderHints(parentId, parentKey, title, scrollable, scrollWidth, scrollHeight, shadowBlur, shadowShape, shaderGradient, v, transitionDelayMs, transitionEasing, transitionProperty, layer, skew);
    }

    public RenderHints withTransition(int delayMs, String easing, String property) {
        if (transitionDelayMs == delayMs && java.util.Objects.equals(transitionEasing, easing)
                && java.util.Objects.equals(transitionProperty, property)) {
            return this;
        }
        return new RenderHints(parentId, parentKey, title, scrollable, scrollWidth, scrollHeight, shadowBlur, shadowShape, shaderGradient, pointerEventsNone, delayMs, easing, property, layer, skew);
    }

    public RenderHints withLayer(LayerSpec v) {
        if (java.util.Objects.equals(layer, v)) return this;
        return new RenderHints(parentId, parentKey, title, scrollable, scrollWidth, scrollHeight, shadowBlur, shadowShape, shaderGradient, pointerEventsNone, transitionDelayMs, transitionEasing, transitionProperty, v, skew);
    }

    public RenderHints withSkew(float v) {
        if (skew == v) return this;
        return new RenderHints(parentId, parentKey, title, scrollable, scrollWidth, scrollHeight, shadowBlur, shadowShape, shaderGradient, pointerEventsNone, transitionDelayMs, transitionEasing, transitionProperty, layer, v);
    }

    public RenderHints withShadowShape(ShadowShape v) {
        if (java.util.Objects.equals(shadowShape, v)) return this;
        return new RenderHints(parentId, parentKey, title, scrollable, scrollWidth, scrollHeight, shadowBlur, v, shaderGradient, pointerEventsNone, transitionDelayMs, transitionEasing, transitionProperty, layer, skew);
    }

}
