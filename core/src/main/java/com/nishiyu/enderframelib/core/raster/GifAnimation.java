package com.nishiyu.enderframelib.core.raster;

import java.util.ArrayList;
import java.util.Arrays;
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
     * GIF を読む（{@link ImageDecoder#standard()} でフレームを取り出して合成する）。
     * GIF でない・壊れている・上限を超えるときは null。
     */
    public static GifAnimation decode(byte[] bytes) {
        return decode(bytes, ImageDecoder.standard());
    }

    public static GifAnimation decode(byte[] bytes, ImageDecoder decoder) {
        if (!looksLikeGif(bytes)) return null;
        try {
            ImageDecoder.GifStream gif = decoder.decodeGif(bytes, MAX_FRAMES, MAX_TOTAL_PIXELS);
            if (gif == null || gif.frames().isEmpty()) return null;
            int w = gif.width(), h = gif.height(), count = gif.frames().size();

            int[] canvas = new int[w * h]; // 合成中の非事前乗算 ARGB（初期は透明）
            int[] backup = null;
            List<Pixmap> frames = new ArrayList<>(count);
            int[] delays = new int[count];
            int prevDisposal = 0;
            int[] prevRect = {0, 0, w, h};

            for (int i = 0; i < count; i++) {
                ImageDecoder.GifFrame f = gif.frames().get(i);
                // 前のフレームの破棄を、このフレームを描く前に行う
                if (prevDisposal == 2) clear(canvas, w, h, prevRect);
                else if (prevDisposal == 3 && backup != null) System.arraycopy(backup, 0, canvas, 0, canvas.length);
                if (f.disposal() == 3) backup = canvas.clone();

                int fw = f.width(), fh = f.height();
                int[] rgb = f.argb();
                for (int y = 0; y < fh; y++) {
                    int cy = y + f.top();
                    if (cy < 0 || cy >= h) continue;
                    for (int x = 0; x < fw; x++) {
                        int cx = x + f.left();
                        if (cx < 0 || cx >= w) continue;
                        int p = rgb[y * fw + x];
                        if ((p >>> 24) != 0) canvas[cy * w + cx] = p; // 透明な画素は下の内容を残す
                    }
                }
                frames.add(Pixmap.fromStraightArgb(w, h, canvas.clone()));
                delays[i] = f.delayMs() <= 10 ? DEFAULT_DELAY_MS : f.delayMs();
                prevDisposal = f.disposal();
                prevRect = new int[]{f.left(), f.top(), fw, fh};
            }
            return new GifAnimation(w, h, frames, delays);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void clear(int[] canvas, int w, int h, int[] rect) {
        int x0 = Math.max(0, rect[0]), y0 = Math.max(0, rect[1]);
        int x1 = Math.min(w, rect[0] + rect[2]), y1 = Math.min(h, rect[1] + rect[3]);
        for (int y = y0; y < y1; y++) Arrays.fill(canvas, y * w + x0, y * w + x1, 0);
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
