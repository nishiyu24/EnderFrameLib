package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.paint.DisplayListBuilder;
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
 * SoftwareRenderBackend img = SoftwareRendering.renderHtml(html, 800, 600, System.currentTimeMillis());
 * img.writePng(Path.of("out.png"));
 * }</pre>
 */
public final class SoftwareRendering {
    private SoftwareRendering() {}

    /** HTML をレイアウトして、ディスプレイリスト（アニメーションの補間前）を得る。 */
    public static List<DisplayItem> displayList(String html, int width, int height, FontResolver fonts) {
        com.nishiyu.enderframelib.core.paint.software.ImageSizes.installDefault();
        UiDocumentCompiler compiler = new UiDocumentCompiler();
        Document doc = compiler.parseDocument(html, "", width, height);
        ScrollState scroll = new ScrollState();
        LayoutBox root = compiler.layoutDocument(doc, width, height, Map.of(), scroll, fonts, new InteractionState());
        return new DisplayListBuilder(width, height, doc.sheet.keyframes, scroll).render(root);
    }

    /** システムフォントで {@link #displayList(String, int, int, FontResolver)}。 */
    public static List<DisplayItem> displayList(String html, int width, int height) {
        return displayList(html, width, height, SystemFonts.system());
    }

    /** HTML を時刻 {@code nowMs} の見た目で描いた {@link SoftwareRenderBackend} を返す（等倍・透明な地）。 */
    public static SoftwareRenderBackend renderHtml(String html, int width, int height, long nowMs) {
        return renderHtml(html, width, height, 1f, nowMs);
    }

    public static SoftwareRenderBackend renderHtml(String html, int width, int height, float devicePixelRatio, long nowMs) {
        SoftwareRenderBackend backend = new SoftwareRenderBackend(width, height, devicePixelRatio);
        backend.render(displayList(html, width, height), nowMs);
        return backend;
    }

    /** HTML をレイアウトして、任意の {@link RenderBackend}（CPU でも GPU でも）に時刻 {@code nowMs} の見た目で描く。 */
    public static <B extends RenderBackend> B renderHtml(String html, int width, int height, long nowMs, B backend) {
        backend.render(displayList(html, width, height), nowMs);
        return backend;
    }
}
