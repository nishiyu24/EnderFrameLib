package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.client.renderer.DisplayListBuilder;
import com.nishiyu.enderframelib.core.css.CssProperty;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.text.model.FontResolver;

import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.nishiyu.enderframelib.common.Document;
import com.nishiyu.enderframelib.common.RenderPipeline;
import com.nishiyu.enderframelib.core.paint.*;
import com.nishiyu.enderframelib.core.paint.model.*;
import com.nishiyu.enderframelib.core.text.*;
import com.nishiyu.enderframelib.core.text.model.*;
import com.nishiyu.enderframelib.core.layout.LayoutContext;
import com.nishiyu.enderframelib.core.layout.model.Viewport;
import com.nishiyu.enderframelib.core.layout.model.FormattingContext;
import com.nishiyu.enderframelib.core.layout.model.ConstraintSpace;
import com.nishiyu.enderframelib.core.layout.model.LayoutMetrics;
import com.nishiyu.enderframelib.core.css.model.Overflow;
import com.nishiyu.enderframelib.core.css.model.TextAlign;
import com.nishiyu.enderframelib.core.css.model.BorderStyle;

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

    /** ケースをレイアウトして、id 付き要素の幾何・スクロール量・計算値を文書順に返す。 */
    static Map<String, Snapshot> dump(String caseName) {
        // ケースは body の中身として書かれている。ブラウザ側（fidelity-dump.html）と同じく、
        // パーサが html / head / body を暗黙に補う（W7。W6 まではここで包んでいた）
        UiDocumentCompiler parser = new UiDocumentCompiler();
        Document doc = parser.parseDocument(caseHtml(caseName), "", VIEWPORT_W, VIEWPORT_H);
        LayoutBox root = parser.layoutDocument(doc, VIEWPORT_W, VIEWPORT_H, Map.of(),
                new ScrollState(), FontResolver.FALLBACK);

        // 描画順は本番の描画と同じ並べ替えから取る（W5 で描画モデルを替えてもこの経路のまま）
        List<LayoutBox> painted = new DisplayListBuilder(VIEWPORT_W, VIEWPORT_H, doc.sheet.keyframes, new ScrollState())
                .paintOrder(root);

        // ルート箱はパーサが作る合成ノード（html_root）なので、著者が書いた要素だけを拾う。
        // 辿るのは要素ツリー: display: contents の要素は箱を作らないが、ブラウザ側も
        // getBoundingClientRect の 0 矩形として出すので、ダンプからは落とさない
        Set<String> authored = authoredIds(caseHtml(caseName));
        Map<String, LayoutBox> boxes = new LinkedHashMap<>();
        for (LayoutBox child : root.elementChildren) collect(child, authored, boxes);

        List<LayoutBox> paintedWithId = new ArrayList<>();
        for (LayoutBox box : painted) {
            if (boxes.containsValue(box)) paintedWithId.add(box);
        }

        List<String> caseProps = declaredProperties(caseHtml(caseName));

        Map<String, Snapshot> snapshots = new LinkedHashMap<>();
        boxes.forEach((id, box) -> snapshots.put(id, createSnapshot(box, paintedWithId, caseProps)));
        return snapshots;
    }

    private static Snapshot createSnapshot(LayoutBox box, List<LayoutBox> paintedWithId, List<String> caseProps) {
        float x = box.x;
        float y = box.y;
        float w = box.w;
        float h = box.h;
        int z = stackDepthAtCenter(box, paintedWithId);

        boolean isInlineNonBfc = box.display != null && box.display.isInlineLevel() && !box.establishesBfc;
        float cw = isInlineNonBfc ? 0 : Math.max(0, w - box.bL - box.bR);
        float ch = isInlineNonBfc ? 0 : Math.max(0, h - box.bT - box.bB);

        float sw = isInlineNonBfc ? 0 : Math.max(cw, (box.scrollWidth > 0 ? box.scrollWidth : w) - box.bL - box.bR);
        float sh = isInlineNonBfc ? 0 : Math.max(ch, (box.scrollHeight > 0 ? box.scrollHeight : h) - box.bT - box.bB);

        Map<String, String> computed = new LinkedHashMap<>();
        computed.put("display", computeDisplay(box));
        computed.put("position", box.style.getOrDefault(CssProperty.POSITION, "static"));
        computed.put("box-sizing", box.style.getOrDefault(CssProperty.BOX_SIZING, "content-box"));
        computed.put("overflow", computeOverflow(box));

        Set<String> allProps = new LinkedHashSet<>(caseProps);
        String elProps = box.node.attrs.get("data-fidelity-properties");
        if (elProps != null) {
            for (String p : elProps.split(",")) {
                String trimmed = p.trim().toLowerCase(java.util.Locale.ROOT);
                if (!trimmed.isEmpty()) allProps.add(trimmed);
            }
        }
        for (String p : allProps) {
            if (!computed.containsKey(p)) {
                String val = box.style.get(p);
                if (val != null) computed.put(p, val.trim());
            }
        }

        return new Snapshot(x, y, w, h, z, sw, sh, cw, ch, computed);
    }

    private static String computeDisplay(LayoutBox box) {
        String disp = box.style.get(CssProperty.DISPLAY);
        if (disp != null) return disp.trim().toLowerCase(java.util.Locale.ROOT);
        if (box.display != null) {
            if (box.display.isInlineLevel()) return "inline";
            return "block";
        }
        return "block";
    }

    private static String computeOverflow(LayoutBox box) {
        String ox = box.style.get(CssProperty.OVERFLOW_X);
        String oy = box.style.get(CssProperty.OVERFLOW_Y);
        String o = box.style.get(CssProperty.OVERFLOW);
        if (ox != null && oy != null) {
            return ox.equals(oy) ? ox : ox + " " + oy;
        }
        if (o != null) return o;
        if (ox != null) return ox;
        if (oy != null) return oy;
        return "visible";
    }

    private static void collect(LayoutBox box, Set<String> authored, Map<String, LayoutBox> out) {
        String id = box.node.attrs.get("id");
        if (id != null && authored.contains(id)) out.putIfAbsent(id, box);
        for (LayoutBox child : box.elementChildren) collect(child, authored, out);
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
    private static final Pattern CASE_PROPERTIES = Pattern.compile(
            "(?:<meta\\s+name=[\"']fidelity-properties[\"']\\s+content=[\"']([^\"']+)[\"']|<!--\\s*fidelity-properties:\\s*([^>]+)-->)");

    /**
     * ケースの HTML から宣言された追加プロパティのリストを抽出する。
     * ケース全体の meta / コメントに加え、要素ごとの {@code data-fidelity-properties} も読む
     * （ブラウザ側の fidelity-dump.html と同じ 2 経路）。
     */
    static List<String> declaredProperties(String html) {
        List<String> props = new ArrayList<>();
        Matcher m = CASE_PROPERTIES.matcher(html);
        while (m.find()) addProperties(props, m.group(1) != null ? m.group(1) : m.group(2));

        Matcher element = ELEMENT_PROPERTIES.matcher(html);
        while (element.find()) addProperties(props, element.group(1));
        return props;
    }

    private static void addProperties(List<String> props, String raw) {
        if (raw == null) return;
        for (String p : raw.split(",")) {
            String trimmed = p.trim().toLowerCase(Locale.ROOT);
            if (!trimmed.isEmpty() && !props.contains(trimmed)) props.add(trimmed);
        }
    }

    private static final Pattern ELEMENT_PROPERTIES = Pattern.compile(
            "data-fidelity-properties\\s*=\\s*[\"']([^\"']+)[\"']");

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

    /**
     * 矩形が点を含み、かつビューポート内で、祖先の overflow クリップで切られていないこと。
     * 行に分かれたインラインボックスは断片で当たる（断片の間の隙間には当たらない。ブラウザと同じ）。
     */
    private static boolean hits(LayoutBox box, float px, float py) {
        if (px < 0 || py < 0 || px >= VIEWPORT_W || py >= VIEWPORT_H) return false;
        if (!contains(box.x, box.y, box.w, box.h, px, py)) return false;
        if (!box.inlineFragments.isEmpty()
                && box.inlineFragments.stream().noneMatch(f -> contains(f.x, f.y, f.w, f.h, px, py))) {
            return false;
        }
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

    /** 期待値ファイル（と、ブラウザ側の出力）の読み取り。旧 6 列形式と拡張形式の両方を解釈する。 */
    static Map<String, Snapshot> parseDump(String text) {
        Map<String, Snapshot> snapshots = new LinkedHashMap<>();
        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            List<String> parts = splitFields(trimmed);
            if (parts.size() < 6) throw new IllegalArgumentException("ダンプ行の列数が 6 未満: " + trimmed);
            String id = parts.get(0);
            float x = Float.parseFloat(parts.get(1));
            float y = Float.parseFloat(parts.get(2));
            float w = Float.parseFloat(parts.get(3));
            float h = Float.parseFloat(parts.get(4));
            int z = Integer.parseInt(parts.get(5));
            float sw = w, sh = h, cw = w, ch = h;
            Map<String, String> computed = new LinkedHashMap<>();
            for (int i = 6; i < parts.size(); i++) {
                int eq = parts.get(i).indexOf('=');
                if (eq > 0) {
                    String k = parts.get(i).substring(0, eq);
                    String v = parts.get(i).substring(eq + 1);
                    switch (k) {
                        case "sw", "scrollWidth" -> sw = Float.parseFloat(v);
                        case "sh", "scrollHeight" -> sh = Float.parseFloat(v);
                        case "cw", "clientWidth" -> cw = Float.parseFloat(v);
                        case "ch", "clientHeight" -> ch = Float.parseFloat(v);
                        default -> computed.put(k, v);
                    }
                }
            }
            snapshots.put(id, new Snapshot(x, y, w, h, z, sw, sh, cw, ch, computed));
        }
        return snapshots;
    }

    static String format(Map<String, Snapshot> snapshots) {
        StringBuilder sb = new StringBuilder();
        snapshots.forEach((id, s) -> {
            sb.append(id).append(' ')
                    .append(number(s.x)).append(' ')
                    .append(number(s.y)).append(' ')
                    .append(number(s.w)).append(' ')
                    .append(number(s.h)).append(' ')
                    .append(s.z).append(' ')
                    .append("sw=").append(number(s.scrollWidth)).append(' ')
                    .append("sh=").append(number(s.scrollHeight)).append(' ')
                    .append("cw=").append(number(s.clientWidth)).append(' ')
                    .append("ch=").append(number(s.clientHeight));
            s.computed.forEach((k, v) -> sb.append(' ').append(k).append('=').append(quoteValue(v)));
            sb.append('\n');
        });
        return sb.toString();
    }

    /**
     * 計算値は空白を含む（{@code rgb(10, 20, 30)}・{@code matrix(1, 0, 0, 1, 0, 0)}）。
     * 列の区切りと衝突しないよう、空白・引用符・バックスラッシュを含む値は二重引用符で包む。
     * ブラウザ側（fidelity-dump.html）の {@code formatValue} と同じ規則。
     */
    static String quoteValue(String value) {
        boolean needsQuotes = value.chars().anyMatch(Character::isWhitespace)
                || value.indexOf('"') >= 0 || value.indexOf('\\') >= 0;
        if (!needsQuotes) return value;
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    /** 1 行を、二重引用符の内側の空白を保ったままフィールドに分ける。 */
    static List<String> splitFields(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        boolean escaped = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (escaped) {
                current.append(c);
                escaped = false;
            } else if (inQuotes && c == '\\') {
                escaped = true;
            } else if (c == '"') {
                inQuotes = !inQuotes;
            } else if (!inQuotes && Character.isWhitespace(c)) {
                if (!current.isEmpty()) {
                    fields.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (inQuotes) throw new IllegalArgumentException("閉じていない引用符がある: " + line);
        if (escaped) current.append('\\');
        if (!current.isEmpty()) fields.add(current.toString());
        return fields;
    }

    /** 整数なら小数点を付けずに書く（ブラウザ側の出力と並べて読めるように）。 */
    static String number(float v) {
        if (v == Math.rint(v)) return Integer.toString((int) v);
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private static String readResource(String path) {
        try (InputStream is = FidelityDumper.class.getResourceAsStream(path)) {
            if (is == null) throw new IllegalStateException("忠実度コーパスのリソースが無い: " + path);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 幾何・描画順・スクロール量・計算値をすべて保持するスナップショット。 */
    record Snapshot(
            float x, float y, float w, float h, int z,
            float scrollWidth, float scrollHeight, float clientWidth, float clientHeight,
            Map<String, String> computed
    ) {
        /** 互換用コンストラクタ（幾何と描画順のみ）。 */
        Snapshot(float x, float y, float w, float h, int z) {
            this(x, y, w, h, z, w, h, w, h, Map.of());
        }

        float field(String name) {
            return switch (name) {
                case "x" -> x;
                case "y" -> y;
                case "w" -> w;
                case "h" -> h;
                case "z" -> z;
                case "scrollWidth", "sw" -> scrollWidth;
                case "scrollHeight", "sh" -> scrollHeight;
                case "clientWidth", "cw" -> clientWidth;
                case "clientHeight", "ch" -> clientHeight;
                default -> throw new IllegalArgumentException("未知の数値フィールド: " + name);
            };
        }

        String computedValue(String property) {
            return computed.get(property);
        }
    }

    static final String[] NUMERIC_FIELDS = {"x", "y", "w", "h", "z", "scrollWidth", "scrollHeight", "clientWidth", "clientHeight"};
    static final String[] GEOMETRY_FIELDS = {"x", "y", "w", "h", "z"};
    static final String[] FIELDS = NUMERIC_FIELDS;

    /**
     * 幾何は ±0.5px、スクロール量は ±1.0px、描画順は完全一致。
     */
    static float tolerance(String field) {
        return switch (field) {
            case "z" -> 0f;
            case "x", "y", "w", "h" -> 0.5f;
            case "scrollWidth", "scrollHeight", "clientWidth", "clientHeight", "sw", "sh", "cw", "ch" -> 1.0f;
            default -> 0f;
        };
    }
}