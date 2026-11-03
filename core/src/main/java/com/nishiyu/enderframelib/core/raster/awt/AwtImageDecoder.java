package com.nishiyu.enderframelib.core.raster.awt;

import com.nishiyu.enderframelib.core.raster.ImageDecoder;
import com.nishiyu.enderframelib.core.raster.Pixmap;
import org.w3c.dom.Node;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** JDK の ImageIO による {@link ImageDecoder}（L3）。AWT への依存はこのクラスに閉じる。 */
public final class AwtImageDecoder implements ImageDecoder {

    @Override
    public Bitmap decode(byte[] bytes) throws IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
        if (img == null) return null;
        int w = img.getWidth(), h = img.getHeight();
        return new Bitmap(w, h, img.getRGB(0, 0, w, h, null, 0, w));
    }

    @Override
    public GifStream decodeGif(byte[] bytes, int maxFrames, long maxTotalPixels) {
        ImageReader reader = null;
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("gif");
            if (!readers.hasNext()) return null;
            reader = readers.next();
            reader.setInput(in, false, false);
            int count = reader.getNumImages(true);
            if (count <= 0 || count > maxFrames) return null;

            int[] screen = logicalScreen(reader);
            int w = screen[0], h = screen[1];
            // 論理画面の記載が無いときは先頭フレームの大きさに合わせる
            if (w <= 0 || h <= 0) {
                BufferedImage first = reader.read(0);
                w = first.getWidth();
                h = first.getHeight();
            }
            if (w > Pixmap.MAX_DIMENSION || h > Pixmap.MAX_DIMENSION || (long) w * h * count > maxTotalPixels) return null;

            List<GifFrame> frames = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                BufferedImage img = reader.read(i);
                int[] meta = frameMeta(reader.getImageMetadata(i)); // left, top, delayMs, disposal
                int fw = img.getWidth(), fh = img.getHeight();
                frames.add(new GifFrame(meta[0], meta[1], fw, fh, img.getRGB(0, 0, fw, fh, null, 0, fw), meta[2], meta[3]));
            }
            return new GifStream(w, h, frames);
        } catch (IOException | RuntimeException e) {
            return null;
        } finally {
            if (reader != null) reader.dispose();
        }
    }

    private static int[] logicalScreen(ImageReader reader) throws IOException {
        IIOMetadata sm = reader.getStreamMetadata();
        if (sm == null) return new int[]{0, 0};
        Node root = sm.getAsTree("javax_imageio_gif_stream_1.0");
        Node lsd = child(root, "LogicalScreenDescriptor");
        if (lsd == null) return new int[]{0, 0};
        return new int[]{intAttr(lsd, "logicalScreenWidth", 0), intAttr(lsd, "logicalScreenHeight", 0)};
    }

    private static int[] frameMeta(IIOMetadata md) {
        Node root = md.getAsTree("javax_imageio_gif_image_1.0");
        Node desc = child(root, "ImageDescriptor");
        Node gce = child(root, "GraphicControlExtension");
        int left = desc == null ? 0 : intAttr(desc, "imageLeftPosition", 0);
        int top = desc == null ? 0 : intAttr(desc, "imageTopPosition", 0);
        int delay = gce == null ? 0 : intAttr(gce, "delayTime", 0) * 10;
        int disposal = 0;
        if (gce != null) {
            Node d = gce.getAttributes().getNamedItem("disposalMethod");
            String s = d == null ? "none" : d.getNodeValue();
            disposal = switch (s) {
                case "restoreToBackgroundColor" -> 2;
                case "restoreToPrevious" -> 3;
                default -> 0;
            };
        }
        return new int[]{left, top, delay, disposal};
    }

    private static Node child(Node root, String name) {
        if (root instanceof IIOMetadataNode n) {
            for (Node c = n.getFirstChild(); c != null; c = c.getNextSibling()) {
                if (name.equals(c.getNodeName())) return c;
            }
        }
        return null;
    }

    private static int intAttr(Node n, String name, int fallback) {
        Node a = n.getAttributes().getNamedItem(name);
        if (a == null) return fallback;
        try {
            return Integer.parseInt(a.getNodeValue());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
