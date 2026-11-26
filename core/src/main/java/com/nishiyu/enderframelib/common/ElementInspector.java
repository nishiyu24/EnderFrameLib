package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.model.AnimationSpec;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.node.NodeType;
import com.nishiyu.enderframelib.core.paint.tree.PaintBox;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.model.ImageFitMode;
import com.nishiyu.enderframelib.core.paint.model.RenderMeta;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 組み込みの要素インスペクタ（{@code F12}）。ポインタの下の要素の箱モデル（margin / border / padding / content）を
 * 色分けして重ね、要素の選択子・大きさ・主な算出スタイルを小さなパネルに出す。
 * 値の取り出し（{@link #inspect}）と重ね描きの項目づくり（{@link #overlay}）を分けてあるので、
 * ウィンドウ無しでも値だけ検査できる。
 */
public final class ElementInspector {

    private ElementInspector() {}

    /** 表示する算出スタイル（この順。未設定・既定の値は出さない）。 */
    private static final String[] SHOWN = {
            "display", "position", "width", "height", "color", "background-color", "font-size", "font-family",
            "font-weight", "line-height", "opacity", "overflow", "z-index", "flex-direction", "grid-template-columns"
    };

    private static final int MARGIN = 0x66F9CC9D, BORDER = 0x66FDDD9B, PADDING = 0x66C3D08B, CONTENT = 0x668CB6C0;
    private static final int PANEL_BG = 0xE61E1E2E, PANEL_TEXT = 0xFFCDD6F4;
    private static final int FONT_PX = 12, LINE = 15, PANEL_W = 300;

    /**
     * 1 つの要素の検査結果。座標はビューポート左上が原点の border box（{@code x, y, w, h}）。
     * 四辺の値は上・右・下・左の順。
     */
    public record Info(String selector, float x, float y, float w, float h,
                       float[] margin, float[] border, float[] padding, Map<String, String> style, String text) {}

    /** 要素の検査結果。要素でない・レイアウトされていない（{@code display: none} など）なら null。 */
    public static Info inspect(UiView ui, HtmlNode node) {
        if (ui == null || node == null || node.nodeType != NodeType.ELEMENT) return null;
        PaintBox box = UiFocus.findBox(ui.boxRoot(), node);
        if (box == null || box.displayNone()) return null;
        Map<String, String> shown = new LinkedHashMap<>();
        ComputedStyle computed = box.style();
        if (computed != null) {
            com.nishiyu.enderframelib.core.css.Style st = computed.typed();
            for (String p : SHOWN) {
                String v = st.cssProperty(p);
                if (v != null && !v.isBlank() && !v.equals("auto") && !v.equals("normal") && !v.equals("none")
                        && !v.equals(com.nishiyu.enderframelib.core.css.Style.DEFAULT.cssProperty(p))) shown.put(p, v);
            }
        }
        String text = node.children.size() == 1 && node.children.getFirst().nodeType == NodeType.TEXT
                ? node.children.getFirst().getTextContent().strip() : "";
        return new Info(selectorOf(node), box.x(), box.y(), box.w(), box.h(),
                new float[]{box.mT(), box.mR(), box.mB(), box.mL()}, new float[]{box.bT(), box.bR(), box.bB(), box.bL()},
                new float[]{box.pT(), box.pR(), box.pB(), box.pL()}, shown, text);
    }

    /** {@code div#main.card.big}（タグ・id・class）。 */
    public static String selectorOf(HtmlNode node) {
        StringBuilder sb = new StringBuilder(node.tag == null ? "" : node.tag.toLowerCase(Locale.ROOT));
        String id = node.attrs.get("id");
        if (id != null && !id.isBlank()) sb.append('#').append(id.trim());
        String cls = node.attrs.get("class");
        if (cls != null) {
            for (String c : cls.trim().split("\\s+")) if (!c.isEmpty()) sb.append('.').append(c);
        }
        return sb.toString();
    }

    /** ポインタ {@code (x, y)} の下の要素（ホバーと同じヒットテスト）。無ければ null。 */
    public static HtmlNode pick(UiView ui, double x, double y) {
        var hit = ui.hitTest(x, y);
        if (hit == null || hit.element() == null) return null;
        String id = hit.element().baseId();
        HtmlNode node = id == null ? null : ui.findNode(id);
        while (node != null && node.nodeType != NodeType.ELEMENT) node = node.parent;
        return node;
    }

    /** 箱モデルの色分けとパネル。{@code info} が null なら空。 */
    public static List<DisplayItem> overlay(Info info, int viewW, int viewH) {
        if (info == null) return List.of();
        List<DisplayItem> out = new ArrayList<>();
        float[] m = info.margin(), b = info.border(), p = info.padding();
        float x = info.x(), y = info.y(), w = info.w(), h = info.h();
        // 外側から内側へ、輪（四つの帯）を重ねる
        float[] mBox = {x - m[3], y - m[0], w + m[1] + m[3], h + m[0] + m[2]};
        float[] bBox = {x, y, w, h};
        float[] pBox = {x + b[3], y + b[0], w - b[1] - b[3], h - b[0] - b[2]};
        float[] cBox = {pBox[0] + p[3], pBox[1] + p[0], pBox[2] - p[1] - p[3], pBox[3] - p[0] - p[2]};
        int[] n = {0};
        ring(out, n, mBox, bBox, MARGIN);
        ring(out, n, bBox, pBox, BORDER);
        ring(out, n, pBox, cBox, PADDING);
        rect(out, n, cBox[0], cBox[1], Math.max(0, cBox[2]), Math.max(0, cBox[3]), CONTENT);

        List<String> lines = new ArrayList<>();
        lines.add(info.selector());
        lines.add(Math.round(w * 10) / 10f + " x " + Math.round(h * 10) / 10f + "  @ " + Math.round(x) + ", " + Math.round(y));
        lines.add("margin " + sides(m) + "  padding " + sides(p));
        if (!isZero(b)) lines.add("border " + sides(b));
        for (Map.Entry<String, String> e : info.style().entrySet()) lines.add(e.getKey() + ": " + e.getValue());
        if (!info.text().isEmpty()) lines.add("\"" + (info.text().length() > 40 ? info.text().substring(0, 40) + "..." : info.text()) + "\"");

        int ph = lines.size() * LINE + 8;
        float px = Math.max(0, Math.min(x, viewW - PANEL_W));
        float py = y + h + m[2] + 6 + ph <= viewH ? y + h + m[2] + 6 : Math.max(0, y - m[0] - 6 - ph);
        rect(out, n, px, py, PANEL_W, ph, PANEL_BG);
        RenderMeta meta = new RenderMeta(null, null, null, FONT_PX, 0f, 0f, 0f, 0f, 0, false);
        for (int i = 0; i < lines.size(); i++) {
            out.add(new DisplayItem("__inspector_t" + i, "__inspector", ElementType.TEXT, Math.round(px) + 6,
                    Math.round(py) + 4 + i * LINE, PANEL_W - 12, LINE, lines.get(i), i == 0 ? 0xFFF9E2AF : PANEL_TEXT, 0,
                    Map.of(), 0, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, ImageFitMode.FILL, meta));
        }
        return out;
    }

    private static String sides(float[] s) {
        return fmt(s[0]) + " " + fmt(s[1]) + " " + fmt(s[2]) + " " + fmt(s[3]);
    }

    private static String fmt(float v) {
        return v == Math.rint(v) ? Integer.toString((int) v) : Float.toString(Math.round(v * 10) / 10f);
    }

    private static boolean isZero(float[] s) {
        return s[0] == 0 && s[1] == 0 && s[2] == 0 && s[3] == 0;
    }

    /** {@code outer} と {@code inner}（x, y, w, h）の間の帯を四つの矩形で塗る。 */
    private static void ring(List<DisplayItem> out, int[] n, float[] outer, float[] inner, int argb) {
        float top = Math.max(0, inner[1] - outer[1]);
        float bottom = Math.max(0, outer[1] + outer[3] - (inner[1] + inner[3]));
        float left = Math.max(0, inner[0] - outer[0]);
        float right = Math.max(0, outer[0] + outer[2] - (inner[0] + inner[2]));
        float midY = outer[1] + top, midH = Math.max(0, outer[3] - top - bottom);
        if (top > 0) rect(out, n, outer[0], outer[1], outer[2], top, argb);
        if (bottom > 0) rect(out, n, outer[0], outer[1] + outer[3] - bottom, outer[2], bottom, argb);
        if (left > 0) rect(out, n, outer[0], midY, left, midH, argb);
        if (right > 0) rect(out, n, outer[0] + outer[2] - right, midY, right, midH, argb);
    }

    private static void rect(List<DisplayItem> out, int[] n, float x, float y, float w, float h, int argb) {
        if (!(w > 0) || !(h > 0)) return;
        int x0 = Math.round(x), y0 = Math.round(y);
        out.add(new DisplayItem("__inspector_r" + n[0]++, "__inspector", ElementType.RECT, x0, y0,
                Math.round(x + w) - x0, Math.round(y + h) - y0, "", 0, argb, Map.of(), 0, 1f, 0, 0, 1f, 1f, 0, 0,
                AnimationSpec.NONE, ImageFitMode.FILL, null));
    }
}
