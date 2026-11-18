package com.nishiyu.enderframelib.core.paint.gl;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.software.SoftwareRenderBackend;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import org.junit.jupiter.api.Test;

/**
 * B9 の M0: 表示項目の表現を作り替える前後で「見えるもの」が変わらないことを固定するゴールデン。
 * 現在の実装から記録した、シナリオ × フレームの SOFTWARE 画素ハッシュと表示リスト要約ハッシュ（{@code golden/frames.txt}）と比べる。
 * 実行時に新旧を切り替えて比べるのではなく、固定の基準と比べる（新旧の経路を共存させないため）。
 * 画素は OS ごとの記録（{@code frames-pixels-<os>.txt}。フォントの描画が OS で違う）、表示リスト要約・項目数は OS に依らない {@code frames.txt}。
 * 意図した見た目の変更のときだけ {@code -Dgolden.update=true} で取り直し、差分をレビューする。
 */
class GoldenFrameTest {
    private static final long T0 = 10_000L;
    private static final String RESOURCE = "/golden/frames.txt";

    private static long itemsHash(List<DisplayItem> items) {
        CRC32 c = new CRC32();
        StringBuilder sb = new StringBuilder();
        for (DisplayItem it : items) {
            sb.setLength(0);
            sb.append(it.id()).append('|') /* key() はプロセス内の採番で他のテストの影響を受けるので ID 文字列で */.append(it.type()).append('|')
                    .append(it.x()).append(',').append(it.y()).append(',').append(it.width()).append(',').append(it.height()).append('|')
                    .append(it.color()).append(',').append(it.bgColor()).append(',').append(it.radius()).append('|')
                    .append(Float.floatToIntBits(it.opacity())).append('|')
                    .append(Float.floatToIntBits(it.translateX())).append(',').append(Float.floatToIntBits(it.translateY())).append(',')
                    .append(Float.floatToIntBits(it.scaleX())).append(',').append(Float.floatToIntBits(it.scaleY())).append(',')
                    .append(Float.floatToIntBits(it.rotate())).append('|').append(it.text()).append('\n');
            c.update(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
        return c.getValue();
    }

    private static long pixelsHash(int[] px) {
        CRC32 c = new CRC32();
        byte[] b = new byte[4];
        for (int p : px) {
            b[0] = (byte) p; b[1] = (byte) (p >> 8); b[2] = (byte) (p >> 16); b[3] = (byte) (p >> 24);
            c.update(b);
        }
        return c.getValue();
    }

    private static void record(Map<String, String> out, String name, StandaloneView v, SoftwareRenderBackend sw, long now) {
        v.frame(now, sw, true);
        List<DisplayItem> items = v.items();
        out.put(name, Long.toHexString(pixelsHash(sw.pixmap().pixels())) + " " + Long.toHexString(itemsHash(items)) + " " + items.size());
    }

    /** 全シナリオを実行して「名前 → 画素ハッシュ 項目ハッシュ 項目数」を返す。 */
    static Map<String, String> run() throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        for (String doc : List.of("showcase", "large-list", "varied-list")) {
            String html = GpuBaselineTest.doc(doc);
            if (!doc.equals("showcase")) html = html.replace("</style>", " li:hover { background: #eef; }</style>");
            try (StandaloneView v = new StandaloneView(html, 800, 600, FontResolver.FALLBACK);
                 SoftwareRenderBackend sw = new SoftwareRenderBackend(800, 600, 1f)) {
                long t = T0;
                record(out, doc + "/open", v, sw, t);
                // :hover の規則が掛かる要素（リストは item-100.. / それ以外はボタン）を順に乗せる
                List<String> hoverKeys = new ArrayList<>();
                if (doc.equals("showcase")) collectButtons(v.root(), hoverKeys, 4);
                else for (int i = 100; i < 104; i++) hoverKeys.add(v.root().getNodeById("item-" + i).key());
                for (int i = 0; i < hoverKeys.size(); i++) {
                    v.ui().setHoveredElement(hoverKeys.get(i));
                    record(out, doc + "/hover" + i, v, sw, t += 16);
                }
                v.ui().setHoveredElement(null);
                record(out, doc + "/leave", v, sw, t += 16);
                v.mouseMove(400, 300, sw.sessionId());
                for (int i = 0; i < 3; i++) {
                    v.scroll(0, -3, sw.sessionId()); // 負が下方向
                    record(out, doc + "/scroll" + i, v, sw, t += 16);
                }
                v.scroll(0, 9, sw.sessionId());
                record(out, doc + "/scrollBack", v, sw, t += 16);
            }
            // スクロールの慣性は実時間に依るので、スクロールしない新しいビューで幅の変更と再構築を取る
            try (StandaloneView v = new StandaloneView(html, 800, 600, FontResolver.FALLBACK);
                 SoftwareRenderBackend sw = new SoftwareRenderBackend(800, 600, 1f)) {
                long t = T0;
                v.frame(t, sw, true);
                for (int w : new int[]{760, 700, 800}) {
                    v.resize(w, 600);
                    // 幅の変更は実時間の間引き（UiView.resizeDue）で遅れて反映される。反映されるまで描く
                    for (int k = 0; k < 6; k++) {
                        v.frame(t += 500, sw, true);
                        try { Thread.sleep(40); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    }
                    record(out, doc + "/resize" + w, v, sw, t += 500);
                }
                v.invalidate();
                record(out, doc + "/invalidate", v, sw, t += 16);
            }
        }
        return out;
    }

    private static void collectButtons(com.nishiyu.enderframelib.core.dom.node.HtmlNode n, List<String> out, int max) {
        if (out.size() >= max) return;
        if ("button".equals(n.tag)) out.add(n.key());
        for (var c : n.children) collectButtons(c, out, max);
    }

    /** 画素のハッシュはフォントの描画が OS で違うので OS ごとのファイル、表示リスト要約と項目数は OS に依らない 1 つのファイル。 */
    private static String osKey() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        return os.contains("win") ? "windows" : os.contains("mac") ? "mac" : "linux";
    }

    private static Map<String, String> read(String resource) throws IOException {
        Map<String, String> m = new LinkedHashMap<>();
        try (InputStream in = GoldenFrameTest.class.getResourceAsStream(resource)) {
            if (in == null) return null;
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (line.isBlank() || line.startsWith("#")) continue;
                int sp = line.indexOf('\t');
                m.put(line.substring(0, sp), line.substring(sp + 1).trim());
            }
        }
        return m;
    }

    private static void write(Path file, Map<String, String> m) throws IOException {
        write(file, m, null);
    }

    /** {@code header} があれば先頭に書く（画素のファイルの書体の環境の指紋。{@link com.nishiyu.enderframelib.core.paint.FontEnvironment}）。 */
    private static void write(Path file, Map<String, String> m, String header) throws IOException {
        List<String> lines = new ArrayList<>();
        if (header != null) lines.add(header);
        m.forEach((k, v) -> lines.add(k + "\t" + v));
        Files.createDirectories(file.getParent());
        Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
    }

    @Test
    void runIsDeterministic() throws IOException {
        Map<String, String> a = run(), b = run();
        List<String> bad = new ArrayList<>();
        for (var e : a.entrySet()) if (!e.getValue().equals(b.get(e.getKey()))) bad.add(e.getKey() + " " + e.getValue() + " vs " + b.get(e.getKey()));
        assertTrue(bad.isEmpty(), "同じ入力で 2 回描いて同じハッシュにならない（ゴールデンの前提が崩れている）: " + bad);
    }

    @Test
    void framesMatchGolden() throws IOException {
        Map<String, String> now = run();
        Map<String, String> items = new LinkedHashMap<>(), pixels = new LinkedHashMap<>();
        for (var e : now.entrySet()) {
            String[] f = e.getValue().split(" ");
            pixels.put(e.getKey(), f[0]);
            items.put(e.getKey(), f[1] + " " + f[2]);
        }
        String pixelsResource = "/golden/frames-pixels-" + osKey() + ".txt";
        if (Boolean.getBoolean("golden.update")) {
            write(Path.of("core/src/test/resources/golden/frames.txt"), items);
            write(Path.of("core/src/test/resources/golden/frames-pixels-" + osKey() + ".txt"), pixels,
                    com.nishiyu.enderframelib.core.paint.FontEnvironment.HEADER + com.nishiyu.enderframelib.core.paint.FontEnvironment.fingerprint());
            return;
        }
        Map<String, String> goldenItems = read(RESOURCE);
        assertNotNull(goldenItems, "ゴールデンが無い。-Dgolden.update=true で記録する");
        List<String> bad = new ArrayList<>();
        for (var e : items.entrySet()) if (!e.getValue().equals(goldenItems.get(e.getKey()))) bad.add("items " + e.getKey() + " now=" + e.getValue() + " golden=" + goldenItems.get(e.getKey()));
        for (String k : goldenItems.keySet()) if (!items.containsKey(k)) bad.add(k + " が実行されなかった");
        // 画素は OS ごとの記録（無い OS では表示リストだけ比べる。記録は -Dgolden.update=true をその OS で走らせる）
        Map<String, String> goldenPixels = read(pixelsResource);
        String pixelsText;
        try (InputStream in = GoldenFrameTest.class.getResourceAsStream(pixelsResource)) {
            pixelsText = in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        // 書体の環境が記録と違う（CI の ubuntu-latest など）なら画素は比べない（表示リストは比べる）
        if (goldenPixels != null && com.nishiyu.enderframelib.core.paint.FontEnvironment.pixelsComparable(pixelsText, "GoldenFrameTest")) {
            for (var e : pixels.entrySet()) if (!e.getValue().equals(goldenPixels.get(e.getKey()))) bad.add("pixels " + e.getKey() + " now=" + e.getValue() + " golden=" + goldenPixels.get(e.getKey()));
        }
        assertTrue(bad.isEmpty(), "ゴールデンと違う: " + bad);
    }
}
