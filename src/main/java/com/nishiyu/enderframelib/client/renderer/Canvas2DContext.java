package com.nishiyu.enderframelib.client.renderer;

import com.nishiyu.enderframelib.client.ClientWebManager;
import com.nishiyu.enderframelib.client.SessionContext;
import com.nishiyu.enderframelib.common.CanvasSurface;
import com.nishiyu.enderframelib.core.text.model.FontResolver;

/**
 * {@code <canvas id="...">} の描画面。実体はコアの {@link CanvasSurface}（標準の Canvas 2D と補助関数）で、
 * ここはセッション台帳からの引き当て・Minecraft のフォント・描画用テクスチャだけを足す。
 */
public class Canvas2DContext extends CanvasSurface {

    public static Canvas2DContext getOrCreate(String sessionId, String canvasId) {
        SessionContext session = ClientWebManager.getOrCreateSession(sessionId);
        return session.canvases().computeIfAbsent(stripCanvasSuffix(canvasId), k -> new Canvas2DContext());
    }

    public static Canvas2DContext get(String sessionId, String canvasId) {
        if (sessionId == null || canvasId == null) return null;
        SessionContext session = ClientWebManager.getSession(sessionId);
        return session != null ? session.canvases().get(stripCanvasSuffix(canvasId)) : null;
    }

    private static String stripCanvasSuffix(String id) {
        return (id != null && id.endsWith("_canvas")) ? id.substring(0, id.length() - 7) : id;
    }

    @Override
    protected FontResolver fontResolver() {
        return WebFontManager.McFontResolver.INSTANCE;
    }

    @Override
    public synchronized Canvas2DContext resize(int width, int height) {
        super.resize(width, height);
        return this;
    }

    @Override
    public synchronized Canvas2DContext clear() {
        super.clear();
        return this;
    }

    /** 描画用のテクスチャ。何も描いていなければ null。 */
    synchronized WebMediaManager.ImageInfo texture(String key) {
        if (context2d().modCount() == 0) return null;
        return RasterTextures.textureFor(key, context2d().bitmap(), context2d().modCount(), width(), height());
    }
}
