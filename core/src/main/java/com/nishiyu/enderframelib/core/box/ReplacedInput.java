package com.nishiyu.enderframelib.core.box;

import com.nishiyu.enderframelib.core.dom.node.ElementTraits;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.node.NodeType;
import com.nishiyu.enderframelib.core.replaced.ReplacedElements;
import com.nishiyu.enderframelib.core.media.ImageChoice;
import com.nishiyu.enderframelib.core.media.ImageSources;
import com.nishiyu.enderframelib.core.raster.svg.SvgSize;

import java.util.ArrayList;
import java.util.List;

/**
 * 置換要素・部品の大きさを決める入力の写し（Engine v3 M2b-1）。不変の値で、{@link BoxNode#replaced} に載る。
 *
 * <p>置換要素の内在の大きさは属性（{@code canvas} の {@code width}・{@code img} の {@code src} / {@code srcset}・{@code input} の
 * {@code type} / {@code size}・{@code svg} の {@code viewBox}）や子（{@code select} の選択肢の文字）で決まり、スタイルには現れない。
 * レイアウトが生きた DOM を読むと、属性だけが変わったときに {@code BoxNode} が前回と同じ参照のままで、キャッシュが古い大きさを返す。
 * そこで入力側（{@link BoxTreeCompiler}）がコンパイルのときに写しを作り、値が前回と同じなら前回の参照を使う（入力は不変・
 * 参照の同一性で照合。計画書 §2.1）。レイアウト（{@code ReplacedFlow}）はこの写しだけを読む。</p>
 *
 * @param tag      要素名
 * @param type     {@code input} の {@code type}（既定 text）
 * @param size     {@code input} の {@code size} 属性（無ければ -1）
 * @param width    {@code canvas} / {@code svg} / {@code img} の固有の幅（CSS px。分からなければ NaN）
 * @param height   固有の高さ（分からなければ NaN）
 * @param pending  大きさを外部の解決に待つ画像（リモート・SVG のファイル）か
 * @param labels   {@code select} の選択肢の文字（他は空）
 */
public record ReplacedInput(String tag, String type, int size, float width, float height, boolean pending, List<String> labels) {

    /** {@link #of} の写しがビューポートの大きさに依るか（{@code img} の {@code srcset} は {@code sizes} と候補をビューポートで選ぶ）。 */
    static boolean dependsOnViewport(HtmlNode n) {
        if (n == null || n.nodeType != NodeType.ELEMENT || !ElementTraits.isImage(n.tag)) return false;
        String srcset = n.attrs.get("srcset");
        return srcset != null && !srcset.isBlank();
    }

    /** 置換要素なら写しを作る（{@code button} は中身を持つので作らない）。そうでなければ null。 */
    static ReplacedInput of(HtmlNode n, float viewportW, float viewportH) {
        if (n.nodeType != NodeType.ELEMENT) return null;
        String tag = n.tag;
        if (ElementTraits.isButton(tag) || !(ReplacedElements.isReplacedTag(tag) || ElementTraits.isEmbeddedMedia(tag))) return null;
        String type = n.attrs.getOrDefault("type", "text");
        int size = -1;
        String sz = n.attrs.get("size");
        if (sz != null) {
            try {
                size = Integer.parseInt(sz.trim());
            } catch (NumberFormatException ignored) {
                // 既定の幅
            }
        }
        float w = Float.NaN, h = Float.NaN;
        boolean pending = false;
        if (ElementTraits.isCanvas(tag) || ElementTraits.isEmbeddedMedia(tag)) { // 既定の物体の大きさ 300 x 150（CSS Images 3 §4.1）
            w = attr(n, "width", 300);
            h = attr(n, "height", 150);
        } else if (ElementTraits.isSvg(tag)) {
            double[] d = SvgSize.intrinsic(n);
            w = (float) Math.max(1, Math.round(Double.isNaN(d[0]) ? 300 : d[0]));
            h = (float) Math.max(1, Math.round(Double.isNaN(d[1]) ? 150 : d[1]));
        } else if (ElementTraits.isImage(tag)) {
            ImageChoice sel = ImageSources.forImg(n.attrs, viewportW, viewportH, 1f);
            String src = sel != null ? sel.url() : n.attrs.getOrDefault("src", "");
            if (com.nishiyu.enderframelib.core.raster.svg.SvgImage.isSvgDataUri(src)) {
                var svg = com.nishiyu.enderframelib.core.raster.svg.SvgImage.cachedFromDataUri(src);
                if (svg != null) {
                    double density = sel != null ? sel.density() : 1;
                    w = (float) Math.max(1, Math.round(Math.round(svg.width()) / density));
                    h = (float) Math.max(1, Math.round(Math.round(svg.height()) / density));
                }
            }
            pending = Float.isNaN(w) && (src.startsWith("http://") || src.startsWith("https://")
                    || com.nishiyu.enderframelib.core.raster.svg.SvgImage.looksLikeSvg(src));
        }
        List<String> labels = List.of();
        if (ElementTraits.isSelect(tag)) {
            List<String> out = new ArrayList<>();
            for (HtmlNode c : n.children) {
                if ("optgroup".equals(c.tag)) {
                    for (HtmlNode o : c.children) if (ElementTraits.isOption(o.tag)) out.add(label(o));
                } else if (ElementTraits.isOption(c.tag)) {
                    out.add(label(c));
                }
            }
            labels = List.copyOf(out);
        }
        return new ReplacedInput(tag, type, size, w, h, pending, labels);
    }

    private static String label(HtmlNode o) {
        String l = o.attrs.containsKey("label") ? o.attrs.get("label") : o.getTextContent();
        return l == null ? "" : l.strip().replaceAll("\\s+", " ");
    }

    private static float attr(HtmlNode n, String name, int def) {
        String v = n.attrs.get(name);
        if (v == null) return def;
        try {
            int i = Integer.parseInt(v.trim());
            return i >= 0 && i <= 32767 ? i : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
