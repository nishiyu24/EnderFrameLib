package com.nishiyu.enderframelib.core.raster.svg;

import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.node.NodeType;
import com.nishiyu.enderframelib.core.dom.parser.HtmlParser;
import com.nishiyu.enderframelib.core.raster.Pixmap;
import com.nishiyu.enderframelib.core.text.model.FontResolver;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;

/**
 * 画像としての SVG（{@code <img src="*.svg">}・{@code background-image: url(*.svg)}）。
 * ファイルの文字列を HTML パーサで木にし、{@link SvgRenderer} で描く。
 */
public final class SvgImage {

    /** デコードしてよい SVG ファイルの最大バイト数。 */
    public static final int MAX_BYTES = 4 * 1024 * 1024;
    /** ラスタライズの一辺の上限（px）。巨大な指定でメモリを使い切らない。 */
    public static final int MAX_RASTER = 2048;

    private final HtmlNode root;
    private final double width;
    private final double height;

    private SvgImage(HtmlNode root, double width, double height) {
        this.root = root;
        this.width = width;
        this.height = height;
    }

    /** 文書の最初の {@code svg} 要素を取る。無ければ null。 */
    public static SvgImage parse(String text) {
        if (text == null || text.length() > MAX_BYTES) return null;
        HtmlNode tree = HtmlParser.parse(stripProlog(text));
        HtmlNode svg = findSvg(tree);
        if (svg == null) return null;
        double[] d = SvgSize.intrinsic(svg);
        double w = d[0], h = d[1];
        double ratio = SvgSize.aspectRatio(svg);
        // 寸法が無い SVG は 300×150（比があれば幅 300 で比に合わせる）
        if (Double.isNaN(w) && Double.isNaN(h)) {
            w = 300;
            h = Double.isNaN(ratio) ? 150 : 300 / ratio;
        } else if (Double.isNaN(w)) {
            w = Double.isNaN(ratio) ? 300 : h * ratio;
        } else if (Double.isNaN(h)) {
            h = Double.isNaN(ratio) ? 150 : w / ratio;
        }
        return new SvgImage(svg, w, h);
    }

    /** {@code data:image/svg+xml[;base64],...} の中身。SVG でない・読めないなら null。 */
    public static SvgImage fromDataUri(String uri) {
        String text = decodeDataUri(uri);
        return text == null ? null : parse(text);
    }

    private static final java.util.Map<String, SvgImage> DATA_CACHE = new java.util.LinkedHashMap<>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<String, SvgImage> eldest) {
            return size() > 64;
        }
    };

    /** {@link #fromDataUri} の結果を使い回す（レイアウトと描画が毎回解析し直さないため）。読めない URI は null。 */
    public static SvgImage cachedFromDataUri(String uri) {
        synchronized (DATA_CACHE) {
            SvgImage hit = DATA_CACHE.get(uri);
            if (hit != null) return hit;
        }
        SvgImage parsed = fromDataUri(uri);
        if (parsed != null) {
            synchronized (DATA_CACHE) {
                DATA_CACHE.put(uri, parsed);
            }
        }
        return parsed;
    }

    public static boolean isSvgDataUri(String src) {
        return src != null && src.regionMatches(true, 0, "data:image/svg+xml", 0, 18);
    }

    /** URL かパスが SVG らしいか（拡張子か data URI）。 */
    public static boolean looksLikeSvg(String src) {
        if (src == null) return false;
        if (isSvgDataUri(src)) return true;
        String s = src;
        int cut = s.indexOf('?');
        if (cut >= 0) s = s.substring(0, cut);
        cut = s.indexOf('#');
        if (cut >= 0) s = s.substring(0, cut);
        return s.toLowerCase(Locale.ROOT).endsWith(".svg");
    }

    static String decodeDataUri(String uri) {
        if (!isSvgDataUri(uri)) return null;
        int comma = uri.indexOf(',');
        if (comma < 0) return null;
        String meta = uri.substring(0, comma).toLowerCase(Locale.ROOT);
        String body = uri.substring(comma + 1);
        try {
            if (meta.contains(";base64")) {
                byte[] bytes = Base64.getMimeDecoder().decode(body);
                return bytes.length > MAX_BYTES ? null : new String(bytes, StandardCharsets.UTF_8);
            }
            return URLDecoder.decode(body.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String stripProlog(String t) {
        String s = t.startsWith("﻿") ? t.substring(1) : t;
        return s.replaceFirst("^\\s*<\\?xml[^>]*\\?>", "");
    }

    private static HtmlNode findSvg(HtmlNode n) {
        if (n.nodeType == NodeType.ELEMENT && "svg".equals(n.tag)) return n;
        for (HtmlNode c : n.children) {
            HtmlNode f = findSvg(c);
            if (f != null) return f;
        }
        return null;
    }

    public HtmlNode root() {
        return root;
    }

    /** 固有の幅（px）。 */
    public double width() {
        return width;
    }

    public double height() {
        return height;
    }

    /** {@code pxW × pxH}（一辺は {@link #MAX_RASTER} まで）に描く。 */
    public Pixmap rasterize(int pxW, int pxH, int currentColor, FontResolver fonts) {
        return SvgRenderer.render(root, Math.min(MAX_RASTER, pxW), Math.min(MAX_RASTER, pxH), currentColor, fonts);
    }
}
