package com.nishiyu.enderframelib.core.paint.software;

import com.nishiyu.enderframelib.core.raster.RasterImageEntry;

import com.nishiyu.enderframelib.core.paint.PaintServices;
import com.nishiyu.enderframelib.core.paint.RasterPainting;
import com.nishiyu.enderframelib.core.raster.Pixmap;
import com.nishiyu.enderframelib.core.raster.RasterImageStore;
import com.nishiyu.enderframelib.core.raster.svg.SvgImage;
import com.nishiyu.enderframelib.core.text.font.SystemFonts;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 画像項目（{@code <img>}・{@code background-image}）の {@code src} を画素に解決する差し込み口。
 * Minecraft のリソースや HTTP を引く実装は呼び出し側が差し替える。既定は {@link #standard()}。
 */
@FunctionalInterface
public interface ImageProvider {

    /**
     * 画像の画素と CSS px の固有サイズ。
     *
     * @param pixmap 事前乗算の画素
     * @param cssW   固有の幅（CSS px。{@code raster:} の画像などは画素の数と違う）
     */
    record Image(Pixmap pixmap, float cssW, float cssH) {}

    /**
     * {@code src} を引く。{@code boxW × boxH} は描く先の大きさ（デバイス画素）で、SVG のようにベクターの画像はこの大きさで
     * ラスタライズする。引けなければ（読み込み中・失敗・未対応）null。
     */
    Image load(String src, int boxW, int boxH);

    /**
     * 画像の項目そのもの（種別・属性つき）を渡して引く。動画のように {@code src} だけでは決まらないものは
     * こちらを上書きする。既定は {@link #load(String, int, int)}。
     *
     * <p>返す {@link Image#pixmap()} を使い回して中身を書き換えるなら、そのたびに {@code Pixmap.markChanged()} を呼ぶ。
     */
    default Image load(com.nishiyu.enderframelib.core.paint.model.DisplayItem item, int boxW, int boxH) {
        // <canvas> の text は要素の id で画像の出所ではない。画素を持つ側（ホスト）が上書きして渡す
        if (item.type() == com.nishiyu.enderframelib.core.paint.model.ElementType.CANVAS) return null;
        return load(item.text(), boxW, boxH);
    }

    /**
     * 既定の解決。次を引く: {@code raster:}（{@link RasterImageStore}）・{@code data:} の SVG と PNG/JPEG/GIF・
     * {@code file:} とローカルパス・クラスパス（{@code classpath:} か先頭 {@code /}）。HTTP は引かない（{@link #web()} を使う）。
     */
    static ImageProvider standard() {
        return Standard.INSTANCE;
    }

    /**
     * {@link #standard()} に加えて {@code http(s)://} の画像も引く（背景スレッドで取得するので、初回は null を返し、
     * 取れた次のフレームから見える。ローカル／LAN への接続は断る）。ウェブページを描くための入口。
     * 描き直しの合図には {@link HttpImageFetcher#completedCount()} が使える。
     */
    static Web web() {
        return new Web(false);
    }

    /** {@link #web()} の実体。{@link #fetcher()} で取得の進み具合を見られる。 */
    final class Web implements ImageProvider {
        private final HttpImageFetcher fetcher;
        private final Standard delegate;

        Web(boolean allowPrivate) {
            fetcher = new HttpImageFetcher(allowPrivate);
            delegate = new Standard(fetcher);
        }

        public HttpImageFetcher fetcher() {
            return fetcher;
        }

        @Override
        public Image load(String src, int boxW, int boxH) {
            return delegate.load(src, boxW, boxH);
        }
    }

    final class Standard implements ImageProvider {
        static final Standard INSTANCE = new Standard(null);
        private static final int MAX_CACHE = 128;
        private static final Image MISSING = new Image(new Pixmap(0, 0), 0, 0);
        private final Map<String, Image> decoded = new ConcurrentHashMap<>();

        private final HttpImageFetcher remote;

        private Standard(HttpImageFetcher remote) {
            this.remote = remote;
        }

        private static boolean isHttp(String src) {
            return src.startsWith("http://") || src.startsWith("https://");
        }

        @Override
        public Image load(String src, int boxW, int boxH) {
            if (src == null || src.isBlank()) return null;
            try {
                if (RasterImageStore.isRasterSrc(src)) return fromRaster(src);
                if (SvgImage.looksLikeSvg(src)) return fromSvg(src, boxW, boxH);
                Image cached = decoded.get(src);
                if (cached == null) {
                    // 取得中は「無い」と覚えない（取れた次のフレームで引き直す）
                    if (isHttp(src) && (remote == null || remote.get(src) == HttpImageFetcher.PENDING)) return null;
                    cached = decodeBitmap(src);
                    if (decoded.size() >= MAX_CACHE) decoded.clear();
                    decoded.put(src, cached == null ? MISSING : cached);
                }
                return cached == MISSING ? null : cached;
            } catch (RuntimeException | IOException e) {
                return null;
            }
        }

        private static Image fromRaster(String src) {
            RasterImageEntry e = RasterImageStore.get(RasterPainting.keyOf(src));
            if (e == null) return null;
            int[] css = RasterPainting.cssSizeOf(src);
            Pixmap p = e.pixmap();
            return new Image(p, css != null ? css[0] : p.width(), css != null ? css[1] : p.height());
        }

        private Image fromSvg(String src, int boxW, int boxH) throws IOException {
            SvgImage svg = SvgImage.isSvgDataUri(src) ? PaintServices.svg(src) : null;
            if (svg == null) {
                byte[] bytes = readBytes(src);
                if (bytes == null) return null;
                svg = SvgImage.parse(new String(bytes, StandardCharsets.UTF_8));
            }
            if (svg == null) return null;
            Pixmap p = svg.rasterize(Math.max(1, boxW), Math.max(1, boxH), 0xFF000000, SystemFonts.system());
            return p == null ? null : new Image(p, (float) svg.width(), (float) svg.height());
        }

        private Image decodeBitmap(String src) throws IOException {
            byte[] bytes = readBytes(src);
            if (bytes == null) return null;
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
            if (img == null) return null;
            int w = img.getWidth(), h = img.getHeight();
            int[] argb = img.getRGB(0, 0, w, h, null, 0, w);
            return new Image(Pixmap.fromStraightArgb(w, h, argb), w, h);
        }

        private byte[] readBytes(String src) throws IOException {
            if (src.startsWith("data:")) {
                int comma = src.indexOf(',');
                if (comma < 0) return null;
                String head = src.substring(5, comma);
                String body = src.substring(comma + 1);
                return head.endsWith(";base64") ? Base64.getMimeDecoder().decode(body)
                        : java.net.URLDecoder.decode(body.replace("+", "%2B"), StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8);
            }
            if (isHttp(src)) {
                if (remote == null) return null;
                byte[] got = remote.get(src);
                return got == HttpImageFetcher.PENDING ? null : got;
            }
            if (src.startsWith("classpath:") || src.startsWith("/")) {
                String path = src.startsWith("classpath:") ? src.substring(10) : src;
                try (InputStream in = ImageProvider.class.getResourceAsStream(path.startsWith("/") ? path : "/" + path)) {
                    return in == null ? null : in.readAllBytes();
                }
            }
            Path path = src.startsWith("file:") ? Path.of(java.net.URI.create(src)) : Path.of(src);
            return Files.isRegularFile(path) ? Files.readAllBytes(path) : null;
        }
    }
}
