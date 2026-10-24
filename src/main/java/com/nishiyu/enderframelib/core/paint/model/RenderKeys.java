package com.nishiyu.enderframelib.core.paint.model;

import com.nishiyu.enderframelib.core.paint.*;
import com.nishiyu.enderframelib.core.paint.model.*;

/**
 * DisplayItem の events マップで使用される内部プロトコルキー定数。
 */
public final class RenderKeys {
    private RenderKeys() {}

    public static final String MC_PARENT = "mc-parent";
    /** ぼかした外側の影の部品。値はぼかし半径（px）。部品の矩形はぼかしが消える所までの広さで、影の形はそこから内側。 */
    public static final String SHADOW_BLUR = "mc-shadow-blur";
    /**
     * グラデーションの矩形（帯・格子の 1 枚）のうち、外縁にあたる辺のビット（1=左 2=上 4=右 8=下）。付かなければ 4 辺とも外縁。
     * 内側の辺（隣の矩形と接する辺）はアンチエイリアスをかけない。かけると継ぎ目に線が出る。
     */
    public static final String EDGE_MASK = "mc-edge-mask";
    /**
     * 2 色だけのグラデーションを、シェーダーが画素ごとに計算して 1 枚で描くための記述（{@code Gradients#shaderGradient}）。
     * 帯や格子に刻まないので、継ぎ目も段もない。
     */
    public static final String SHADER_GRADIENT = "mc-shader-gradient";
    public static final String MC_CLIP = "mc-clip";
    public static final String MC_RADIUS = "mc-radius";
    public static final String MC_GRADIENT = "mc-gradient";
    public static final String MC_FONT_H = "mc-font-h";
    public static final String MC_TITLE = "mc-title";
    public static final String MC_ORIGIN = "mc-origin";
    public static final String MC_DELAY = "mc-delay";
    public static final String MC_EASING = "mc-easing";
    public static final String MC_TRANSITION_PROP = "mc-transition-prop";
    public static final String MC_SCROLLABLE = "mc-scrollable";
    public static final String MC_SCROLL_CURRENT = "mc-scroll-current";
    /** 算出値の {@code pointer-events}（{@code none} のときだけ載せる）。ヒットテストはこの部品を透過する。 */
    public static final String POINTER_EVENTS = "pointer-events";
}
