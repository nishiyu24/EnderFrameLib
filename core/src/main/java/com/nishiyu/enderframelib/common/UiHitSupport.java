package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.dom.node.HtmlDocument;
import com.nishiyu.enderframelib.core.paint.AlphaMask;
import com.nishiyu.enderframelib.core.paint.AlphaMaskProvider;
import com.nishiyu.enderframelib.core.paint.AnimatedValues;
import com.nishiyu.enderframelib.core.paint.CssAnimator;
import com.nishiyu.enderframelib.core.paint.model.PaintOffsets;
import com.nishiyu.enderframelib.core.paint.scene.DynamicSceneState;
import com.nishiyu.enderframelib.core.paint.scene.SceneHitTest;
import com.nishiyu.enderframelib.core.paint.scene.SceneLayer;
import com.nishiyu.enderframelib.core.paint.scene.Transform2D;

import java.util.function.Predicate;

/**
 * {@link UiView} の当たり判定用コンテキスト生成・姿勢上書き解決の補助（M3b-3）。
 * 毎スクロール時のラムダ・{@link SceneHitTest.Context} の再確保を避けるため不変要素をキャッシュする。
 */
final class UiHitSupport {
    private final String sessionId;
    private final InteractionState interactionState;
    private final PaintOffsets userPaintOffsets;
    private final SceneHitTest.TransformOverride transformOverride = this::resolveTransform;
    private final Predicate<SceneLayer> inertAccept;

    private AlphaMaskProvider lastProvider;
    private SceneHitTest.AlphaHitTest cachedAlpha;
    private DynamicSceneState lastDyn;
    private boolean lastHasInert;
    private boolean lastAllowCull;
    private boolean lastHasOverride;
    private SceneHitTest.Context cachedCtx;

    UiHitSupport(String sessionId, InteractionState interactionState, PaintOffsets userPaintOffsets) {
        this.sessionId = sessionId;
        this.interactionState = interactionState;
        this.userPaintOffsets = userPaintOffsets;
        this.inertAccept = layer -> layer.node == null || !interactionState.isInert(layer.node);
    }

    SceneHitTest.Context context(DynamicSceneState dyn, AlphaMaskProvider provider, Document document) {
        if (provider != lastProvider) {
            lastProvider = provider;
            cachedAlpha = provider == null ? null
                    : (layer, src, threshold, lx, ly, bw, bh, fit, objPos, fontPx) -> {
                        AlphaMask.Source s = provider.maskFor(src, Math.round(bw), Math.round(bh), threshold);
                        return s == null || s.mask().hitsRect(lx, ly, bw, bh, s.cssW(), s.cssH(), fit, objPos, fontPx);
                    };
            cachedCtx = null;
        }
        boolean hasInert = interactionState.hasActiveModal()
                || (document != null && document.root instanceof HtmlDocument doc && doc.hasInertAttributes());
        boolean allowCull = userPaintOffsets.isEmpty();
        boolean hasOverride = !allowCull || CssAnimator.hasSessionState(sessionId);
        if (cachedCtx != null && lastDyn == dyn && lastHasInert == hasInert
                && lastAllowCull == allowCull && lastHasOverride == hasOverride) {
            return cachedCtx;
        }
        lastDyn = dyn;
        lastHasInert = hasInert;
        lastAllowCull = allowCull;
        lastHasOverride = hasOverride;
        cachedCtx = SceneHitTest.Context.of(dyn, hasOverride ? transformOverride : null,
                cachedAlpha, hasInert ? inertAccept : null, allowCull);
        return cachedCtx;
    }

    private boolean resolveTransform(SceneLayer layer, float[] out6) {
        String key = layer.node != null ? layer.node.key() : null;
        if (key == null || key.isEmpty()) return false;
        CssAnimator.AnimState st = CssAnimator.peekState(sessionId, key);
        float udx = userPaintOffsets.dx(key);
        float udy = userPaintOffsets.dy(key);
        if (st == null && udx == 0f && udy == 0f) return false;
        if (st != null) {
            synchronized (st) {
                AnimatedValues av = st.getInterpolatedValues(interactionState.animationTimeMs());
                out6[0] = av.tx() + udx; out6[1] = av.ty() + udy;
                out6[2] = av.sx(); out6[3] = av.sy();
                out6[4] = av.rot(); out6[5] = av.skew();
            }
            return true;
        }
        Transform2D tr = layer.transform;
        out6[0] = (tr != null ? tr.tx() : 0f) + udx;
        out6[1] = (tr != null ? tr.ty() : 0f) + udy;
        out6[2] = tr != null ? tr.sx() : 1f;
        out6[3] = tr != null ? tr.sy() : 1f;
        out6[4] = tr != null ? tr.rotate() : 0f;
        out6[5] = tr != null ? tr.skew() : 0f;
        return true;
    }
}
