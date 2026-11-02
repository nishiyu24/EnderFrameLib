package com.nishiyu.enderframelib.core.raster;

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
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

/**
 * アニメーション GIF を、Minecraft にも GL にも依存せずに読む。全フレームを論理画面の大きさに合成し
 * （破棄方法 {@code disposal} を反映）、事前乗算の {@link Pixmap} と表示時間（ms）の並びにする。
 *
 * <p>再生は {@link Player}: 時刻から現在のフレームを決め、1 枚の表示用 {@code Pixmap} へ書き込む
 * （変わったときだけ {@link Pixmap#markChanged()}）。ブラウザと同じく、表示時間が 0〜10ms のフレームは 100ms として扱う。
 */
public final class GifAnimation {

    /** 展開後の画素の総数の上限（4 バイト × この数 = メモリ）。巨大な GIF での枯渇を防ぐ。 */
    public static final long MAX_TOTAL_PIXELS = 16L * 1024 * 1024;
    public static final int MAX_FRAMES = 1000;
    static final int DEFAULT_DELAY_MS = 100;

    private final int width;
    private final int height;
    private final List<Pixmap> frames;
    private final int[] delaysMs;
    private final long totalMs;

    private GifAnimation(int width, int height, List<Pixmap> frames, int[] delaysMs) {
        this.width = width;
        this.height = height;
        this.frames = frames;
        this.delaysMs = delaysMs;
        long sum = 0;
        for (int d : delaysMs) sum += d;
        this.totalMs = sum;
    }

    /** 1 画素の空の 1 枚（GIF ではないことの目印用）。 */
    public static GifAnimation single() {
        return new GifAnimation(1, 1, List.of(new Pixmap(1, 1)), new int[]{DEFAULT_DELAY_MS});
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int frameCount() {
        return frames.size();
    }

    /** 1 周の長さ（ms）。 */
    public long durationMs() {
        return totalMs;
    }

    public Pixmap frame(int index) {
        return frames.get(index);
    }

    public int delayMs(int index) {
        return delaysMs[index];
    }

    /** {@code bytes} が GIF の先頭（{@code GIF87a} / {@code GIF89a}）か。 */
    public static boolean looksLikeGif(byte[] bytes) {
        return bytes != null && bytes.length >= 6 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == '8';
    }

    /** 経過時間 {@code elapsedMs} に表示するフレーム。{@code loop} が偽なら最後のフレームで止まる。 */
    public int frameIndexAt(long elapsedMs, boolean loop) {
        if (frames.size() <= 1 || totalMs <= 0) return 0;
        long t = elapsedMs < 0 ? 0 : elapsedMs;
        if (loop) t %= totalMs;
        else if (t >= totalMs) return frames.size() - 1;
        for (int i = 0; i < delaysMs.length; i++) {
            if (t < delaysMs[i]) return i;
            t -= delaysMs[i];
        }
        return frames.size() - 1;
    }

    /**
     * GIF を読む。GIF でない・壊れている・上限を超えるときは null。
     */
    public static GifAnimation decode(byte[] bytes) {
        if (!looksLikeGif(bytes)) return null;
        ImageReader reader = null;
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("gif");
            if (!readers.hasNext()) return null;
            reader = readers.next();
            reader.setInput(in, false, false);
            int count = reader.getNumImages(true);
            if (count <= 0 || count > MAX_FRAMES) return null;

            int[] screen = logicalScreen(reader);
            int w = screen[0], h = screen[1];
            // 論理画面の記載が無い／小さすぎるときは先頭フレームの大きさに合わせる
            if (w <= 0 || h <= 0) {
                BufferedImage first = reader.read(0);
                w = first.getWidth();
                h = first.getHeight();
            }
            if (w > Pixmap.MAX_DIMENSION || h > Pixmap.MAX_DIMENSION || (long) w * h * count > MAX_TOTAL_PIXELS) return null;

            int[] canvas = new int[w * h]; // 合成中の非事前乗算 ARGB（初期は透明）
            int[] backup = null;
            List<Pixmap> frames = new ArrayList<>(count);
            int[] delays = new int[count];
            int prevDisposal = 0;
            int[] prevRect = {0, 0, w, h};

            for (int i = 0; i < count; i++) {
                // 前のフレームの破棄を、このフレームを描く前に行う
                if (prevDisposal == 2) clear(canvas, w, h, prevRect);
                else if (prevDisposal == 3 && backup != null) System.arraycopy(backup, 0, canvas, 0, canvas.length);

                BufferedImage img = reader.read(i);
                FrameMeta meta = frameMeta(reader.getImageMetadata(i));
                if (meta.disposal == 3) backup = canvas.clone();

                int fw = img.getWidth(), fh = img.getHeight();
                int[] rgb = img.getRGB(0, 0, fw, fh, null, 0, fw);
                for (int y = 0; y < fh; y++) {
                    int cy = y + meta.top;
                    if (cy < 0 || cy >= h) continue;
                    for (int x = 0; x < fw; x++) {
                        int cx = x + meta.left;
                        if (cx < 0 || cx >= w) continue;
                        int p = rgb[y * fw + x];
                        if ((p >>> 24) != 0) canvas[cy * w + cx] = p; // 透明な画素は下の内容を残す
                    }
                }
                frames.add(Pixmap.fromStraightArgb(w, h, canvas.clone()));
                delays[i] = meta.delayMs;
                prevDisposal = meta.disposal;
                prevRect = new int[]{meta.left, meta.top, fw, fh};
            }
            return new GifAnimation(w, h, frames, delays);
        } catch (IOException | RuntimeException e) {
            return null;
        } finally {
            if (reader != null) reader.dispose();
        }
    }

    private static void clear(int[] canvas, int w, int h, int[] rect) {
        int x0 = Math.max(0, rect[0]), y0 = Math.max(0, rect[1]);
        int x1 = Math.min(w, rect[0] + rect[2]), y1 = Math.min(h, rect[1] + rect[3]);
        for (int y = y0; y < y1; y++) Arrays.fill(canvas, y * w + x0, y * w + x1, 0);
    }

    private record FrameMeta(int left, int top, int delayMs, int disposal) {}

    private static int[] logicalScreen(ImageReader reader) throws IOException {
        IIOMetadata sm = reader.getStreamMetadata();
        if (sm == null) return new int[]{0, 0};
        Node root = sm.getAsTree("javax_imageio_gif_stream_1.0");
        Node lsd = child(root, "LogicalScreenDescriptor");
        if (lsd == null) return new int[]{0, 0};
        return new int[]{intAttr(lsd, "logicalScreenWidth", 0), intAttr(lsd, "logicalScreenHeight", 0)};
    }

    private static FrameMeta frameMeta(IIOMetadata md) {
        Node root = md.getAsTree("javax_imageio_gif_image_1.0");
        Node desc = child(root, "ImageDescriptor");
        Node gce = child(root, "GraphicControlExtension");
        int left = desc == null ? 0 : intAttr(desc, "imageLeftPosition", 0);
        int top = desc == null ? 0 : intAttr(desc, "imageTopPosition", 0);
        int delay = gce == null ? 0 : intAttr(gce, "delayTime", 0) * 10;
        if (delay <= 10) delay = DEFAULT_DELAY_MS;
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
        return new FrameMeta(left, top, delay, disposal);
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

    /**
     * 再生位置。{@link #pixmap()} は 1 枚を使い回し、{@link #update(long)} で現在のフレームを書き込む。
     * 描画スレッドでだけ使う。
     */
    public static final class Player {
        private final GifAnimation anim;
        private final Pixmap display;
        private long startMs = Long.MIN_VALUE;
        private int shown = -1;

        public Player(GifAnimation anim) {
            this.anim = anim;
            this.display = new Pixmap(anim.width, anim.height);
        }

        public Pixmap pixmap() {
            return display;
        }

        public GifAnimation animation() {
            return anim;
        }

        /** 動いて見えるか（2 枚以上）。 */
        public boolean isAnimated() {
            return anim.frameCount() > 1;
        }

        /** 最初の呼び出しの時刻を 0 として、{@code nowMs} のフレームにする。 @return 表示を書き換えたか */
        public boolean update(long nowMs, boolean loop) {
            if (startMs == Long.MIN_VALUE) startMs = nowMs;
            int idx = anim.frameIndexAt(nowMs - startMs, loop);
            if (idx == shown) return false;
            System.arraycopy(anim.frame(idx).pixels(), 0, display.pixels(), 0, display.pixels().length);
            display.markChanged();
            shown = idx;
            return true;
        }
    }
}
