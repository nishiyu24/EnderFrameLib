package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.kernel.Clock;
import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.paint.RenderBackend;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.software.SoftwareRenderBackend;
import com.nishiyu.enderframelib.core.text.font.SystemFonts;
import com.nishiyu.enderframelib.core.text.model.FontResolver;

import java.util.List;
import java.util.Map;

/**
 * Minecraft も OpenGL も使わずに、HTML/CSS 文字列から画素までを通す入口。
 * パース → レイアウト → ディスプレイリスト → {@link SoftwareRenderBackend}。
 *
 * <pre>{@code
 * SoftwareRenderBackend img = SoftwareRendering.renderHtml(html, 800, 600, Clock.SYSTEM.millis());
 * img.writePng(Path.of("out.png"));
 * }</pre>
 */
public final class SoftwareRendering {
    private SoftwareRendering() {}

    /** HTML を 1 回だけ組んだフレーム（シーンつき。表示項目の列はテストの道具 {@code TestItems} がシーンから組む）。 */
    public static FrameRender.Frame frame(String html, int width, int height, FontResolver fonts) {
        com.nishiyu.enderframelib.core.EngineContext.current().updateHost(h -> h.withPixelSizeFallback(com.nishiyu.enderframelib.core.paint.software.ImageSizes.STANDARD));
        UiDocumentCompiler compiler = new UiDocumentCompiler();
        Document doc = compiler.parseDocument(html, "", width, height);
        return FrameRender.document(doc, width, height, Map.of(), new ScrollState(), fonts, new InteractionState());
    }

    /** 描画器へ渡すフレームの値（シーンから描画器が列を組む。列だけのフレームにしない〔M3b-4c-2b-3b-2a。マスクの印は組む側の階層にある〕）。 */
    private static com.nishiyu.enderframelib.core.paint.RenderFrame renderFrame(String html, int width, int height, long nowMs) {
        return frame(html, width, height, SystemFonts.system()).renderFrame().at(nowMs);
    }

    /** HTML を時刻 {@code nowMs} の見た目で描いた {@link SoftwareRenderBackend} を返す（等倍・透明な地）。 */
    public static SoftwareRenderBackend renderHtml(String html, int width, int height, long nowMs) {
        return renderHtml(html, width, height, 1f, nowMs);
    }

    public static SoftwareRenderBackend renderHtml(String html, int width, int height, float devicePixelRatio, long nowMs) {
        SoftwareRenderBackend backend = new SoftwareRenderBackend(width, height, devicePixelRatio);
        backend.render(renderFrame(html, width, height, nowMs));
        return backend;
    }

    /** HTML をレイアウトして、任意の {@link RenderBackend}（CPU でも GPU でも）に時刻 {@code nowMs} の見た目で描く。 */
    public static <B extends RenderBackend> B renderHtml(String html, int width, int height, long nowMs, B backend) {
        backend.render(renderFrame(html, width, height, nowMs));
        return backend;
    }
}
