package com.nishiyu.enderframelib.core.raster;

import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.software.ImageProvider;
import org.junit.jupiter.api.Test;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class GifAnimationTest {

    /** 赤→緑→青の単色 3 枚（各 delayCs センチ秒）。 */
    static byte[] gif(int w, int h, int delayCs) throws Exception {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            writer.prepareWriteSequence(null);
            Color[] colors = {Color.RED, Color.GREEN, Color.BLUE};
            for (Color c : colors) {
                BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = img.createGraphics();
                g.setColor(c);
                g.fillRect(0, 0, w, h);
                g.dispose();
                ImageWriteParam param = writer.getDefaultWriteParam();
                IIOMetadata md = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(img), param);
                String fmt = md.getNativeMetadataFormatName();
                IIOMetadataNode root = (IIOMetadataNode) md.getAsTree(fmt);
                IIOMetadataNode gce = new IIOMetadataNode("GraphicControlExtension");
                gce.setAttribute("disposalMethod", "none");
                gce.setAttribute("userInputFlag", "FALSE");
                gce.setAttribute("transparentColorFlag", "FALSE");
                gce.setAttribute("delayTime", String.valueOf(delayCs));
                gce.setAttribute("transparentColorIndex", "0");
                root.appendChild(gce);
                md.setFromTree(fmt, root);
                writer.writeToSequence(new IIOImage(img, null, md), param);
            }
            writer.endWriteSequence();
        }
        writer.dispose();
        return out.toByteArray();
    }

    @Test
    void decodesFramesAndDelays() throws Exception {
        GifAnimation a = GifAnimation.decode(gif(4, 3, 20));
        assertNotNull(a);
        assertEquals(3, a.frameCount());
        assertEquals(4, a.width());
        assertEquals(3, a.height());
        assertEquals(200, a.delayMs(0));
        assertEquals(600, a.durationMs());
        assertEquals(0xFFFF0000, a.frame(0).get(1, 1));
        assertEquals(0xFF00FF00 | 0, a.frame(1).get(1, 1) | 0xFF000000);
        assertEquals(0xFF0000FF, a.frame(2).get(0, 0));
    }

    @Test
    void shortDelaysBecomeHundredMs() throws Exception {
        GifAnimation a = GifAnimation.decode(gif(2, 2, 0));
        assertEquals(100, a.delayMs(0));
    }

    @Test
    void frameIndexLoopsOrStops() throws Exception {
        GifAnimation a = GifAnimation.decode(gif(2, 2, 20));
        assertEquals(0, a.frameIndexAt(0, true));
        assertEquals(0, a.frameIndexAt(199, true));
        assertEquals(1, a.frameIndexAt(200, true));
        assertEquals(2, a.frameIndexAt(450, true));
        assertEquals(0, a.frameIndexAt(600, true));
        assertEquals(0, a.frameIndexAt(650, true));
        assertEquals(2, a.frameIndexAt(10_000, false));
        assertEquals(0, a.frameIndexAt(-5, false));
    }

    @Test
    void rejectsNonGifAndGarbage() {
        assertNull(GifAnimation.decode(null));
        assertNull(GifAnimation.decode(new byte[]{1, 2, 3}));
        assertNull(GifAnimation.decode("GIF89a-broken".getBytes()));
    }

    @Test
    void playerWritesOnlyOnChange() throws Exception {
        GifAnimation.Player p = new GifAnimation.Player(GifAnimation.decode(gif(2, 2, 20)));
        assertTrue(p.update(1000, true));
        int v = p.pixmap().version();
        assertFalse(p.update(1100, true));
        assertEquals(v, p.pixmap().version());
        assertTrue(p.update(1200, true));
        assertTrue(p.pixmap().version() > v);
        assertEquals(0xFF00FF00, p.pixmap().get(0, 0) | 0); // 緑
    }

    @Test
    void providerAdvancesGifVideoItemsByClock(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.gif");
        Files.write(file, gif(2, 2, 20));
        AtomicLong now = new AtomicLong(5000);
        ImageProvider.Web web = ImageProvider.web().clock(now::get);
        try {
            DisplayItem item = new DisplayItem("g", "g", ElementType.VIDEO, 0, 0, 2, 2,
                    file.toString(), 0, 0, java.util.Map.of(), 0, 1f, 0, 0, 1f, 1f, 0, 0, null, null, null);
            ImageProvider.Image first = web.load(item, 2, 2);
            assertNotNull(first);
            assertEquals(0xFFFF0000, first.pixmap().get(0, 0));
            assertTrue(web.hasAnimation());
            now.set(5250);
            ImageProvider.Image second = web.load(item, 2, 2);
            assertSame(first.pixmap(), second.pixmap());
            assertEquals(0xFF00FF00, second.pixmap().get(0, 0) | 0);
            // loop 指定なし: 1 周して最後で止まる
            now.set(99_000);
            assertEquals(0xFF0000FF, web.load(item, 2, 2).pixmap().get(0, 0));
        } finally {
            web.fetcher().close();
        }
    }
}
