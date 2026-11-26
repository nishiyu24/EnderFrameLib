package com.nishiyu.enderframelib.core.paint.gl;

import com.nishiyu.enderframelib.common.SoftwareRendering;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.model.PaintOffsets;
import com.nishiyu.enderframelib.core.paint.model.TextureRef;
import com.nishiyu.enderframelib.core.paint.resource.HostTextures;
import com.nishiyu.enderframelib.core.paint.resource.PaintResources;
import com.nishiyu.enderframelib.core.paint.software.SoftwareRenderBackend;
import com.nishiyu.enderframelib.core.raster.Pixmap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL32;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ホストが描いた中身（{@link HostTextures}）を、コアが項目の中身として重なりの順どおりに貼ること（M3a: MOD のゲームのモデル・
 * {@code <mc-scene>}・ビットマップ書体の文字をコアの経路で描くための口）。
 */
class HostTexturesGlTest {

    private static final long T0 = 10_000L;
    private static final int TEX_ARGB = 0xFF20C040;

    private static LwjglGlRenderBackend probe;
    private static String unavailable;

    @BeforeAll
    static void probe() {
        try {
            probe = new LwjglGlRenderBackend(16, 16);
        } catch (Throwable t) {
            unavailable = t.toString();
        }
    }

    @AfterAll
    static void cleanup() {
        if (probe != null) probe.close();
    }

    private static void requireGl() {
        Assumptions.assumeTrue(probe != null, "OpenGL を使えない: " + unavailable);
    }

    private static String page(String body) {
        return "<html><body style='margin:0;background:#fff'>" + body + "</body></html>";
    }

    /** 1 色の 4×4 テクスチャ（現在のコンテキストに作る）。 */
    private static int solidTexture(int argb) {
        ByteBuffer buf = org.lwjgl.BufferUtils.createByteBuffer(4 * 4 * 4);
        for (int i = 0; i < 16; i++) {
            buf.put((byte) (argb >>> 16)).put((byte) (argb >>> 8)).put((byte) argb).put((byte) (argb >>> 24));
        }
        buf.flip();
        int tex = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 4, 4, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
        return tex;
    }

    /** 聞かれた項目を覚え、{@code match} に当たる項目にだけテクスチャを渡すホスト。 */
    private static final class RecordingHost implements HostTextures {
        final List<DisplayItem> asked = new ArrayList<>();
        final List<DisplayItem> served = new ArrayList<>();
        final java.util.function.Predicate<DisplayItem> match;
        final TextureRef ref;

        RecordingHost(TextureRef ref, java.util.function.Predicate<DisplayItem> match) {
            this.ref = ref;
            this.match = match;
        }

        @Override
        public TextureRef texture(DisplayItem item, int deviceW, int deviceH) {
            asked.add(item);
            if (!match.test(item)) return null;
            served.add(item);
            return ref;
        }
    }

    private static boolean isItemImage(DisplayItem it) {
        return it.type() == ElementType.IMAGE && it.text() != null && it.text().startsWith("item:");
    }

    @Test
    void hostTextureFillsAnImageWhoseSourceTheCoreCannotLoad() {
        requireGl();
        try (LwjglGlRenderBackend g = new LwjglGlRenderBackend(40, 40, 1f)) {
            int tex = solidTexture(TEX_ARGB);
            try {
                RecordingHost host = new RecordingHost(new TextureRef(tex, 4, 4), HostTexturesGlTest::isItemImage);
                g.hostTextures(host);
                SoftwareRendering.renderHtml(page("<img src='item:minecraft:diamond' style='display:block;width:20px;height:20px'>"),
                        40, 40, T0, g);
                assertEquals(1, host.served.size(), "画像の項目でホストに聞いていない");
                Pixmap p = g.pixmap();
                assertEquals(TEX_ARGB, p.getStraight(10, 10), "ホストの中身が貼られていない");
                assertEquals(0xFFFFFFFF, p.getStraight(30, 30), "箱の外まで貼った");
                assertEquals(0L, g.readbackCount(), "ホストの中身で読み戻しが起きた");
            } finally {
                GL11.glDeleteTextures(tex);
            }
        }
    }

    /** 中身は重なりの順のとおりに描かれ、後に描く要素（ポップアップなど）がホストの中身を隠す。 */
    @Test
    void laterContentCoversTheHostTexture() {
        requireGl();
        try (LwjglGlRenderBackend g = new LwjglGlRenderBackend(40, 40, 1f)) {
            int tex = solidTexture(TEX_ARGB);
            try {
                g.hostTextures(new RecordingHost(new TextureRef(tex, 4, 4), HostTexturesGlTest::isItemImage));
                SoftwareRendering.renderHtml(page(
                        "<img src='item:minecraft:diamond' style='display:block;width:30px;height:30px'>"
                                + "<div style='position:absolute;left:0;top:0;width:10px;height:10px;background:#f00'></div>"),
                        40, 40, T0, g);
                Pixmap p = g.pixmap();
                assertEquals(0xFFFF0000, p.getStraight(5, 5), "後の要素がホストの中身の下に隠れた");
                assertEquals(TEX_ARGB, p.getStraight(20, 20));
            } finally {
                GL11.glDeleteTextures(tex);
            }
        }
    }

    /** 文字の項目もホストの中身に置き換えられる（ビットマップ書体）。同じ項目の一覧を続けて描いても中身が残る。 */
    @Test
    void hostTextureReplacesTextAndSurvivesARepeatedFrame() {
        requireGl();
        String html = page("<div style='font:24px sans-serif;color:#000;width:80px'>HOST</div>");
        List<DisplayItem> items = SoftwareRendering.displayList(html, 80, 40);
        DisplayItem text = items.stream().filter(it -> "HOST".equals(it.text())).findFirst().orElseThrow();
        int cx = Math.round(text.x() + text.width() / 2f), cy = Math.round(text.y() + text.height() / 2f);
        try (LwjglGlRenderBackend g = new LwjglGlRenderBackend(80, 40, 1f)) {
            int tex = solidTexture(TEX_ARGB);
            try {
                RecordingHost host = new RecordingHost(new TextureRef(tex, 4, 4), it -> "HOST".equals(it.text()));
                g.hostTextures(host);
                for (int frame = 0; frame < 3; frame++) {
                    g.render(items, PaintOffsets.EMPTY, T0 + frame);
                    assertEquals(TEX_ARGB, g.pixmap().getStraight(cx, cy), "フレーム " + frame + " で文字の中身が貼られていない");
                }
            } finally {
                GL11.glDeleteTextures(tex);
            }
        }
    }

    /** ホストが null を返せば、コアが普段どおり描く（ホストを持たないときと同じ画素）。 */
    @Test
    void nullFromTheHostLeavesTheCoreOutputUnchanged() {
        requireGl();
        String body = "<div style='font:20px sans-serif;color:#036;padding:4px'>Plain</div>"
                + "<div style='width:20px;height:20px;background:#0a0;border-radius:4px'></div>";
        try (LwjglGlRenderBackend plain = new LwjglGlRenderBackend(80, 60, 1f);
             LwjglGlRenderBackend hosted = new LwjglGlRenderBackend(80, 60, 1f)) {
            SoftwareRendering.renderHtml(page(body), 80, 60, T0, plain);
            RecordingHost host = new RecordingHost(null, it -> false);
            hosted.hostTextures(host);
            SoftwareRendering.renderHtml(page(body), 80, 60, T0, hosted);
            assertTrue(host.asked.stream().anyMatch(it -> "Plain".equals(it.text())), "文字の項目でホストに聞いていない");
            assertTrue(host.asked.stream().noneMatch(it -> it.type() == ElementType.RECT && (it.text() == null || it.text().isEmpty())),
                    "中身の無い矩形でホストに聞いた");
            Pixmap a = plain.pixmap(), b = hosted.pixmap();
            for (int y = 0; y < 60; y++) {
                for (int x = 0; x < 80; x++) {
                    assertEquals(a.getStraight(x, y), b.getStraight(x, y), "画素が変わった @" + x + "," + y);
                }
            }
        }
    }

    /** ホストのフェンスを待たせても描画が進む（ホストの GL コンテキストで置いたフェンスの受け口）。 */
    @Test
    void waitingForAHostFenceKeepsRendering() {
        requireGl();
        try (LwjglGlRenderBackend g = new LwjglGlRenderBackend(20, 20, 1f)) {
            long fence = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            GL11.glFlush();
            try {
                g.waitForHost(fence);
                SoftwareRendering.renderHtml(page("<div style='width:20px;height:20px;background:#00f'></div>"), 20, 20, T0, g);
                assertEquals(0xFF0000FF, g.pixmap().getStraight(10, 10));
            } finally {
                GL32.glDeleteSync(fence);
            }
            g.waitForHost(0L); // 0 は何もしない
        }
    }

    /** CPU のバックエンドはホストに聞かない（テクスチャを読み戻さない）。 */
    @Test
    void softwareBackendNeverAsksTheHost() {
        AtomicInteger asked = new AtomicInteger();
        PaintResources resources = new PaintResources().hostTextures((item, w, h) -> {
            asked.incrementAndGet();
            return new TextureRef(1, 4, 4);
        });
        SoftwareRenderBackend cpu = new SoftwareRenderBackend(40, 40, 1f, "host-test", resources);
        SoftwareRendering.renderHtml(page("<img src='item:minecraft:diamond' style='display:block;width:20px;height:20px'>"
                + "<div style='font:16px sans-serif'>text</div>"), 40, 40, T0, cpu);
        assertEquals(0, asked.get());
        assertNotEquals(TEX_ARGB, cpu.pixmap().getStraight(10, 10));
    }
}
