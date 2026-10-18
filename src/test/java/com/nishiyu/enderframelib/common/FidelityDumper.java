package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.client.renderer.UIRenderer;
import com.nishiyu.enderframelib.core.css.CssProperty;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.ScrollState;
import com.nishiyu.enderframelib.core.text.FontResolver;

import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 忠実度コーパスをエンジンに通して、ブラウザ側と同じ形式のジオメトリを出す。
 *
 * ブラウザ側（preview/fidelity-dump.html）は同じコーパスを getBoundingClientRect で
 * 同形式に出す。両者を {@link WebFidelityTest} が突き合わせる。
 * 形式は 1 行 1 要素の {@code "<id> <x> <y> <w> <h> <z>"} で、id 属性を持つ要素だけを
 * 文書順に並べる（擬似要素やテキストノードは id を持たないので自然に落ちる）。
 * JSON ではなく行指向にしているのは、パーサを持たずに差分が読めるようにするため。
 *
 * <p>{@code z} は描画順の列。要素の矩形の中心で、その要素より<b>上に描かれている</b>
 * id 付き要素の数（0 = 最前面）。中心が自分に当たらない（大きさ 0・ビューポート外・
 * 祖先の overflow で切られている）ときは -1。ブラウザ側は同じ点で
 * {@code elementsFromPoint} を引き、id 付き要素だけを数える。
 * 全要素の大域的な順位ではなく「重なっている相手との前後」だけを測るのは、
 * 重ならない要素どうしの順序がブラウザから観測できないため。
 */
final class FidelityDumper {

    /** コーパスのビューポート。ケース側の期待値もこの寸法で書かれている。 */
    static final int VIEWPORT_W = 320;
    static final int VIEWPORT_H = 200;

    private static final String CASE_DIR = "/assets/enderframelib/preview/fidelity/cases/";
    private static final String CASE_INDEX = "/assets/enderframelib/preview/fidelity/cases.txt";

    private FidelityDumper() {}

    /** 索引ファイルに並んだケース名。ブラウザ側もこの同じ索引を読む。 */
    static List<String> caseNames() {
        List<String> names = new ArrayList<>();
        for (String line : readResource(CASE_INDEX).split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) names.add(trimmed);
        }
        return names;
    }

    static String caseHtml(String caseName) {
        return readResource(CASE_DIR + caseName + ".html");
    }

    /** ケースをレイアウトして、id 付き要素の矩形を文書順に返す。 */
    static Map<String, Rect> dump(String caseName) {
        UIParser parser = new UIParser();
        UIParser.Document doc = parser.parseDocument(caseHtml(caseName), "", VIEWPORT_W, VIEWPORT_H);
        LayoutBox root = parser.layoutDocument(doc, VIEWPORT_W, VIEWPORT_H, Map.of(),
                new ScrollState(), FontResolver.FALLBACK);

        // 描画順は本番の描画と同じ並べ替えから取る（W5 で描画モデルを替えてもこの経路のまま）
        List<LayoutBox> painted = new UIRenderer(VIEWPORT_W, VIEWPORT_H, doc.sheet.keyframes, new ScrollState())
                .paintOrder(root);

        // ルート箱はパーサが作る合成ノード（html_root）なので、著者が書いた要素だけを拾う
        Set<String> authored = authoredIds(caseHtml(caseName));
        Map<String, LayoutBox> boxes = new LinkedHashMap<>();
        for (LayoutBox child : root.children) collect(child, authored, boxes);

        List<LayoutBox> paintedWithId = new ArrayList<>();
        for (LayoutBox box : painted) {
            if (boxes.containsValue(box)) paintedWithId.add(box);
        }

        Map<String, Rect> rects = new LinkedHashMap<>();
        boxes.forEach((id, box) -> rects.put(id,
                new Rect(box.x, box.y, box.w, box.h, stackDepthAtCenter(box, paintedWithId))));
        return rects;
    }

    private static void collect(LayoutBox box, Set<String> authored, Map<String, LayoutBox> out) {
        String id = box.node.attrs.get("id");
        if (id != null && authored.contains(id)) out.putIfAbsent(id, box);
        for (LayoutBox child : box.children) collect(child, authored, out);
    }

    /**
     * ケースの原文に書かれた id。
     *
     * 何が壊れていたか: パーサは id の無い要素にも {@code gen_N} を id 属性として振るため、
     * 属性の有無で拾うと著者が書いていない要素まで数え、描画順の列（z）が
     * ブラウザ側（{@code [id]} は著者の id だけ）とずれた。
     */
    private static Set<String> authoredIds(String html) {
        Set<String> ids = new HashSet<>();
        Matcher m = AUTHORED_ID.matcher(html);
        while (m.find()) ids.add(m.group(1));
        return ids;
    }

    private static final Pattern AUTHORED_ID = Pattern.compile("\\bid\\s*=\\s*\"([^\"]+)\"");

    /** 中心点で自分より後に描かれる（= 上に重なる）id 付き要素の数。当たらなければ -1。 */
    private static int stackDepthAtCenter(LayoutBox box, List<LayoutBox> paintOrder) {
        int index = paintOrder.indexOf(box);
        float cx = box.x + box.w / 2f;
        float cy = box.y + box.h / 2f;
        if (index < 0 || !hits(box, cx, cy)) return -1;
        int above = 0;
        for (int i = index + 1; i < paintOrder.size(); i++) {
            if (hits(paintOrder.get(i), cx, cy)) above++;
        }
        return above;
    }

    /** 矩形が点を含み、かつビューポート内で、祖先の overflow クリップで切られていないこと。 */
    private static boolean hits(LayoutBox box, float px, float py) {
        if (px < 0 || py < 0 || px >= VIEWPORT_W || py >= VIEWPORT_H) return false;
        if (!contains(box.x, box.y, box.w, box.h, px, py)) return false;
        for (LayoutBox a = box.parent; a != null; a = a.parent) {
            String tag = a.node.tag;
            if ("root".equals(tag) || "html".equals(tag) || "body".equals(tag)) continue;
            if ("visible".equals(a.style.getOrDefault(CssProperty.OVERFLOW, "visible"))) continue;
            // クリップは padding box（ボーダーの内側）。描画側の computeChildClipRect と同じ
            if (!contains(a.x + a.bL, a.y + a.bT, a.w - a.bL - a.bR, a.h - a.bT - a.bB, px, py)) return false;
        }
        return true;
    }

    private static boolean contains(float x, float y, float w, float h, float px, float py) {
        return w > 0 && h > 0 && px >= x && px < x + w && py >= y && py < y + h;
    }

    /** 期待値ファイル（と、ブラウザ側の出力）の読み取り。 */
    static Map<String, Rect> parseDump(String text) {
        Map<String, Rect> rects = new LinkedHashMap<>();
        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            String[] parts = trimmed.split("\\s+");
            if (parts.length != 6) throw new IllegalArgumentException("ダンプ行の列数が 6 でない: " + trimmed);
            rects.put(parts[0], new Rect(
                    Float.parseFloat(parts[1]), Float.parseFloat(parts[2]),
                    Float.parseFloat(parts[3]), Float.parseFloat(parts[4]),
                    Integer.parseInt(parts[5])));
        }
        return rects;
    }

    static String format(Map<String, Rect> rects) {
        StringBuilder sb = new StringBuilder();
        rects.forEach((id, r) -> sb.append(id).append(' ').append(number(r.x)).append(' ').append(number(r.y))
                .append(' ').append(number(r.w)).append(' ').append(number(r.h)).append(' ').append(r.z).append('\n'));
        return sb.toString();
    }

    /** 整数なら小数点を付けずに書く（ブラウザ側の出力と並べて読めるように）。 */
    static String number(float v) {
        if (v == Math.rint(v)) return Integer.toString((int) v);
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    private static String readResource(String path) {
        try (InputStream is = FidelityDumper.class.getResourceAsStream(path)) {
            if (is == null) throw new IllegalStateException("忠実度コーパスのリソースが無い: " + path);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 幾何は float のまま持つ（W2）。丸めた値で比べると ±0.5px の誤差が 2 回乗る。 */
    record Rect(float x, float y, float w, float h, int z) {
        float field(String name) {
            return switch (name) {
                case "x" -> x;
                case "y" -> y;
                case "w" -> w;
                case "h" -> h;
                case "z" -> z;
                default -> throw new IllegalArgumentException("未知のフィールド: " + name);
            };
        }
    }

    static final String[] FIELDS = {"x", "y", "w", "h", "z"};

    /**
     * 幾何は ±0.5px、描画順は完全一致。
     *
     * ブラウザ側は各値を Math.round した整数を出し、エンジン側は丸める前の float を出すので、
     * 両者の幾何が同じなら差は 0.5px 以内に収まる。W2 以前はエンジンの幾何が int で、
     * 途中の切り捨てが溜まるぶんを ±1px で吸収していた。
     */
    static float tolerance(String field) {
        return "z".equals(field) ? 0f : 0.5f;
    }
}
