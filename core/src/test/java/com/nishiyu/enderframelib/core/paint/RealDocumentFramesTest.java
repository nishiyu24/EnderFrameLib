package com.nishiyu.enderframelib.core.paint;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.common.UiView;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.node.HtmlText;
import com.nishiyu.enderframelib.core.dom.parser.MarkdownParser;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
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
import java.util.function.Consumer;
import java.util.zip.CRC32;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * T2: 同梱の実文書 9 本を、操作の手順（開く → ホバー → ホバーを外す → スクロール → 文字の変更 → 幅の変更）で流して、
 * B10・B12・A16 の成果（差分の経路で描いた絵）を固定する。
 * <ol>
 *   <li>{@link #framesMatchGolden}: 毎フレームの SOFTWARE の画素・表示リストの要約・ヒットテストを、記録した基準と比べる
 *       （{@code golden/realdocs.txt} は OS に依らない表示リストとヒットテスト、画素は OS ごとの {@code golden/realdocs-pixels-<os>.txt}。
 *       取り直しは {@code -Dgolden.update=true}。差分を目で確かめてからコミット）。</li>
 *   <li>{@link #incrementalMatchesFullRebuildEveryFrame}: 同じ手順を「差分の経路（既定）」と「近道をすべて切って毎フレーム全体を
 *       作り直す」で流し、毎フレームの画素・表示リストの値・ヒットテストが一致すること。差分の経路が実際に使われたことも確かめる。</li>
 *   <li>{@link #gpuMatchesSoftwareEveryFrame}: GPU のある機械では、同じ手順の GL / Vulkan の画素が SOFTWARE と一致すること（CI では中断）。</li>
 * </ol>
 */
class RealDocumentFramesTest {
    private static final int W = 800, H = 600;
    private static final long T0 = 10_000L;
    private static final Path FIXTURES = Path.of("src/main/resources/data/enderframelib/enderframe_ui");
    private static final Path TEST_FIXTURES = Path.of("src/test/resources/benchmark");

    /** 同梱の実文書（{@code FineProfileMain} / {@code CostGateMain} と同じ 9 本）。 */
    static final List<String> DOCS = List.of("showcase.html", "voidwave.html", "surface_test.html", "animation_test.html",
            "media_test.html", "hud_test.html", "item_block_test.html", "machine_ui.html", "guidebook.md");

    static String html(String name) throws IOException {
        Path p = FIXTURES.resolve(name);
        if (!Files.exists(p)) p = TEST_FIXTURES.resolve(name);
        String text = Files.readString(p, StandardCharsets.UTF_8).replace("\r\n", "\n");
        return name.endsWith(".md") ? MarkdownParser.toHtml(text) : text;
    }

    @AfterEach
    void restoreShortcuts() throws ReflectiveOperationException {
        setShortcuts(true);
    }

    /** 1 フレームの記録。画素は比べるときだけ持つ（ゴールデンはハッシュだけ）。 */
    record Frame(String name, int[] pixels, List<DisplayItem> items, String hits) {
        String itemsKey() { return Long.toHexString(itemsHash(items)) + " " + items.size() + " " + Long.toHexString(crc(hits)); }
        String pixelsKey() { return Long.toHexString(pixelsHash(pixels)); }
    }

    static void setShortcuts(boolean on) throws ReflectiveOperationException {
        setShortcuts(on, on);
    }

    /**
     * テストだけが切り替える近道の印。false で毎フレーム全体の作り直しに近い経路になる。{@code scrollNodes} はスクロールする中身を
     * 1 つの節にまとめる近道（sticky を含むものも）で、切ると表示リストの項目の構成（節の項目）が変わるので、項目を 1 対 1 で
     * 比べる構成では入れたままにし、画素とヒットテストだけを比べる構成で切る。
     */
    static void setShortcuts(boolean on, boolean scrollNodes) throws ReflectiveOperationException {
        DisplayListBuilder.STACKING_PATCH_ENABLED = on;
        DisplayListBuilder.scrollContentNodes = scrollNodes;
        PaintOrder.STICKY_IN_SCROLL_NODE = scrollNodes;
        LayoutBox.cutoffEnabled = on;
        var damage = SoftwareRenderBackend.class.getDeclaredField("damageEnabled");
        damage.setAccessible(true);
        damage.setBoolean(null, on);
    }

    // ---- 操作の手順 --------------------------------------------------------------------------------------

    /**
     * 操作の手順を流して、記録するフレームごとに {@code sink} を呼ぶ。{@code full} なら各フレームの前に全体の再レイアウトを強いる
     * （近道の印は呼び出し側が切る）。時刻は固定の列なので、アニメーション・遷移の途中も同じ入力なら同じ絵になる。
     */
    static void script(String doc, com.nishiyu.enderframelib.core.paint.RenderBackend backend, boolean full, boolean keepPixels,
                       Consumer<Frame> sink) throws IOException {
        String html = html(doc);
        try (StandaloneView v = new StandaloneView(html, W, H, FontResolver.FALLBACK)) {
            long[] t = {T0};
            Recorder rec = new Recorder(v, backend, full, keepPixels, sink, doc);
            rec.frame("open", t[0]);

            // ホバー: :hover の規則が掛かる項目（interactiveState）の中心へ、実際の入力の経路でマウスを動かす
            List<double[]> targets = hoverTargets(v);
            for (int i = 0; i < targets.size(); i++) {
                v.mouseMove(targets.get(i)[0], targets.get(i)[1], backend.sessionId());
                rec.frame("hover" + i + "a", t[0] += 40);   // 遷移の途中
                rec.frame("hover" + i + "b", t[0] += 400);  // 遷移の確定後
            }
            v.mouseMove(-50, -50, backend.sessionId());
            rec.frame("leave", t[0] += 400);

            // スクロール: 画面の中央（文書そのもの）と、見えている最初のスクロールコンテナ
            v.mouseMove(W / 2.0, H / 2.0, backend.sessionId());
            v.scroll(0, -3, backend.sessionId()); // 負が下方向
            rec.frame("scrollA", t[0] += 16);
            rec.frame("scrollB", t[0] += 300);
            double[] sc = firstScrollContainer(v);
            if (sc != null) {
                v.mouseMove(sc[0], sc[1], backend.sessionId());
                v.scroll(0, -2, backend.sessionId());
                rec.frame("innerScrollA", t[0] += 16);
                rec.frame("innerScrollB", t[0] += 300);
            }
            v.mouseMove(-50, -50, backend.sessionId());
            v.scroll(0, 9, backend.sessionId());
            rec.frame("scrollBack", t[0] += 300);

            // 文字の変更（ボタンの外の最初の文字。長さの違う 2 つへ）
            HtmlText text = firstText(v.root());
            if (text != null) {
                String original = text.getData();
                text.setData("T2 changed text that is noticeably longer than before " + doc);
                rec.frame("textLong", t[0] += 16);
                text.setData("T2");
                rec.frame("textShort", t[0] += 16);
                text.setData(original);
                rec.frame("textBack", t[0] += 16);
            }

            // 幅の変更（ホストの経路。反映は実時間で間引かれるので、反映されるまで描いてから記録する）
            v.resize(700, H);
            for (int k = 0; k < 6; k++) {
                rec.frameUnrecorded(t[0] += 500);
                try { Thread.sleep(40); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            rec.frame("resize700", t[0] += 500);
        }
    }

    private static final class Recorder {
        final StandaloneView v;
        final com.nishiyu.enderframelib.core.paint.RenderBackend backend;
        final boolean full, keepPixels;
        final Consumer<Frame> sink;
        final String doc;

        Recorder(StandaloneView v, com.nishiyu.enderframelib.core.paint.RenderBackend backend, boolean full, boolean keepPixels,
                 Consumer<Frame> sink, String doc) {
            this.v = v; this.backend = backend; this.full = full; this.keepPixels = keepPixels; this.sink = sink; this.doc = doc;
        }

        void frameUnrecorded(long now) {
            if (full) v.ui().markInvalidation(UiView.InvalidationLevel.RELAYOUT);
            v.frame(now, backend, true);
        }

        void frame(String name, long now) {
            frameUnrecorded(now);
            int[] px = backend.pixmap().pixels().clone();
            List<DisplayItem> items = new ArrayList<>(v.ui().renderCache().size());
            for (DisplayItem it : v.ui().renderCache()) items.add(it.frozen());
            sink.accept(new Frame(doc + "/" + name, keepPixels ? px : new int[]{(int) pixelsHash(px)}, items, hits(v)));
        }
    }

    /** 8 × 6 の格子でヒットテストした要素の ID。 */
    private static String hits(StandaloneView v) {
        StringBuilder h = new StringBuilder();
        for (int y = 50; y < H; y += 100) {
            for (int x = 50; x < W; x += 100) {
                var hit = v.ui().hitTest(x, y);
                h.append(hit == null ? "-" : hit.element().baseId()).append(' ');
            }
        }
        return h.toString();
    }

    /**
     * ホバーする点（最大 3 つ）。先に {@code button}・{@code a}・class に {@code btn} を含む要素（FineProfileMain・Chrome 側の trace.js と
     * 同じ選び方）、足りなければ :hover の規則が掛かる部品の大きさの項目。中心のヒットテストがその要素に当たるものだけ。
     */
    private static List<double[]> hoverTargets(StandaloneView v) {
        List<double[]> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        List<DisplayItem> items = v.ui().renderCache();
        List<String> preferred = new ArrayList<>();
        collectHoverables(v.root(), preferred);
        for (String key : preferred) {
            if (out.size() >= 3) break;
            for (DisplayItem it0 : items) {
                if (!key.equals(it0.baseId()) || it0.type() == com.nishiyu.enderframelib.core.paint.model.ElementType.NODE
                        || it0.width() <= 4 || it0.height() <= 4) continue;
                DisplayItem it = DisplayItem.resolveAbsolute(items, it0);
                double cx = it.x() + it.width() / 2.0, cy = it.y() + it.height() / 2.0;
                if (cx < 0 || cy < 0 || cx >= W || cy >= H) break;
                var hit = v.ui().hitTest(cx, cy);
                if (hit != null && within(items, hit.element(), key) && seen.add(key)) out.add(new double[]{cx, cy});
                break;
            }
        }
        for (DisplayItem it0 : items) {
            if (out.size() >= 3) break;
            if (it0.meta() == null || !it0.meta().interactiveState() || it0.width() <= 4 || it0.height() <= 4) continue;
            // :hover の規則を持つ祖先（html・body・大きな入れ物）ではなく、部品の大きさのものを選ぶ
            if (it0.type() == com.nishiyu.enderframelib.core.paint.model.ElementType.NODE || (long) it0.width() * it0.height() * 8 > (long) W * H) continue;
            DisplayItem it = DisplayItem.resolveAbsolute(items, it0); // 項目の座標は親相対
            double cx = it.x() + it.width() / 2.0, cy = it.y() + it.height() / 2.0;
            if (cx < 0 || cy < 0 || cx >= W || cy >= H) continue;
            var hit = v.ui().hitTest(cx, cy);
            // 中心がその要素自身（か、その中の文字）に当たるものだけ（上に別の要素が重なっていない）
            if (hit == null || !within(items, hit.element(), it0.baseId())) continue;
            if (!seen.add(it0.baseId())) continue;
            out.add(new double[]{cx, cy});
        }
        return out;
    }

    private static void collectHoverables(HtmlNode n, List<String> out) {
        if (n instanceof com.nishiyu.enderframelib.core.dom.node.HtmlElement
                && ("button".equals(n.tag) || "a".equals(n.tag) || n.hasClass("btn"))) out.add(n.key());
        for (HtmlNode c : n.children) collectHoverables(c, out);
    }

    /** {@code hit} が要素 {@code baseId} の項目か、その子孫の項目か（親の連鎖をたどる）。 */
    private static boolean within(List<DisplayItem> items, DisplayItem hit, String baseId) {
        Map<String, DisplayItem> byId = new java.util.HashMap<>();
        for (DisplayItem it : items) byId.putIfAbsent(it.id(), it);
        DisplayItem cur = hit;
        for (int guard = 0; cur != null && guard < 64; guard++) {
            if (baseId.equals(cur.baseId())) return true;
            String pid = cur.hints().parentId();
            cur = pid == null ? null : byId.get(pid);
        }
        return false;
    }

    /** 画面の中にある、文書の根でない最初のスクロールコンテナの中心。 */
    private static double[] firstScrollContainer(StandaloneView v) {
        LayoutBox root = v.ui().layoutRoot();
        return root == null ? null : findScroll(root, root);
    }

    private static double[] findScroll(LayoutBox b, LayoutBox root) {
        if (b != root && b.isScrollContainer() && b.w > 20 && b.h > 20 && b.x >= 0 && b.y >= 0 && b.x + b.w / 2 < W && b.y + b.h / 2 < H
                && b.node != null && !"body".equals(b.node.tag) && !"html".equals(b.node.tag)) {
            return new double[]{b.x + b.w / 2.0, b.y + b.h / 2.0};
        }
        if (b.children != null) for (LayoutBox c : b.children) {
            double[] r = findScroll(c, root);
            if (r != null) return r;
        }
        return null;
    }

    /** body の下で最初の、空白だけでない文字（button / label の中は除く。FineProfileMain の text と同じ選び方）。 */
    private static HtmlText firstText(HtmlNode n) {
        for (HtmlNode c : n.children) {
            if (c instanceof HtmlText tx) {
                if (!tx.getData().isBlank() && !"button".equals(n.tag) && !"label".equals(n.tag)
                        && !"style".equals(n.tag) && !"script".equals(n.tag) && !"title".equals(n.tag)) return tx;
                continue;
            }
            if ("head".equals(c.tag)) continue;
            HtmlText r = firstText(c);
            if (r != null) return r;
        }
        return null;
    }

    // ---- ハッシュ ----------------------------------------------------------------------------------------

    static long crc(String s) {
        CRC32 c = new CRC32();
        c.update(s.getBytes(StandardCharsets.UTF_8));
        return c.getValue();
    }

    /** {@code GoldenFrameTest} と同じ要約（key() はプロセス内の採番なので ID 文字列で）。 */
    static long itemsHash(List<DisplayItem> items) {
        CRC32 c = new CRC32();
        StringBuilder sb = new StringBuilder();
        for (DisplayItem it : items) {
            sb.setLength(0);
            sb.append(it.id()).append('|').append(it.type()).append('|')
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

    static long pixelsHash(int[] px) {
        CRC32 c = new CRC32();
        byte[] b = new byte[4];
        for (int p : px) {
            b[0] = (byte) p; b[1] = (byte) (p >> 8); b[2] = (byte) (p >> 16); b[3] = (byte) (p >> 24);
            c.update(b);
        }
        return c.getValue();
    }

    // ---- ① ゴールデン ------------------------------------------------------------------------------------

    private static Map<String, String[]> runAll() throws IOException {
        Map<String, String[]> out = new LinkedHashMap<>();
        for (String doc : DOCS) {
            try (SoftwareRenderBackend sw = new SoftwareRenderBackend(W, H, 1f)) {
                script(doc, sw, false, true, f -> out.put(f.name(), new String[]{f.itemsKey(), f.pixelsKey()}));
            }
        }
        return out;
    }

    private static String osKey() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        return os.contains("win") ? "windows" : os.contains("mac") ? "mac" : "linux";
    }

    private static Map<String, String> read(String resource) throws IOException {
        try (InputStream in = RealDocumentFramesTest.class.getResourceAsStream(resource)) {
            if (in == null) return null;
            Map<String, String> m = new LinkedHashMap<>();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (line.isBlank() || line.startsWith("#")) continue;
                int tab = line.indexOf('\t');
                m.put(line.substring(0, tab), line.substring(tab + 1).trim());
            }
            return m;
        }
    }

    private static void write(Path file, String header, Map<String, String> m) throws IOException {
        StringBuilder sb = new StringBuilder(header);
        m.forEach((k, v) -> sb.append(k).append('\t').append(v).append('\n'));
        Files.createDirectories(file.getParent());
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }

    @Test
    void framesMatchGolden() throws IOException {
        Map<String, String[]> now = runAll();
        Map<String, String> items = new LinkedHashMap<>(), pixels = new LinkedHashMap<>();
        now.forEach((k, v) -> { items.put(k, v[0]); pixels.put(k, v[1]); });
        String pixelsFile = "golden/realdocs-pixels-" + osKey() + ".txt";
        if (Boolean.getBoolean("golden.update")) {
            write(Path.of("core/src/test/resources/golden/realdocs.txt"),
                    "# RealDocumentFramesTest（T2）: 実文書 × 操作の手順の、表示リストの要約ハッシュ 項目数 ヒットテストのハッシュ。OS に依らない。\n"
                            + "# 取り直しは -Dgolden.update=true（差分を目で確かめてからコミット）。\n", items);
            write(Path.of("core/src/test/resources/" + pixelsFile),
                    "# RealDocumentFramesTest（T2）: SOFTWARE の画素のハッシュ（書体の描画が OS で違うので OS ごと）。\n"
                            + FontEnvironment.HEADER + FontEnvironment.fingerprint() + "\n", pixels);
            return;
        }
        Map<String, String> goldenItems = read("/golden/realdocs.txt");
        assertNotNull(goldenItems, "ゴールデンが無い。-Dgolden.update=true で記録する");
        List<String> bad = new ArrayList<>();
        for (var e : items.entrySet()) if (!e.getValue().equals(goldenItems.get(e.getKey()))) bad.add("items " + e.getKey() + " now=" + e.getValue() + " golden=" + goldenItems.get(e.getKey()));
        for (String k : goldenItems.keySet()) if (!items.containsKey(k)) bad.add(k + " が実行されなかった");
        Map<String, String> goldenPixels = read("/" + pixelsFile);
        String pixelsText;
        try (InputStream in = RealDocumentFramesTest.class.getResourceAsStream("/" + pixelsFile)) {
            pixelsText = in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        // 記録の無い OS・書体の環境が記録と違う（CI の ubuntu-latest など）ときは、表示リストとヒットテストだけ比べる
        if (goldenPixels != null && FontEnvironment.pixelsComparable(pixelsText, "RealDocumentFramesTest")) {
            for (var e : pixels.entrySet()) if (!e.getValue().equals(goldenPixels.get(e.getKey()))) bad.add("pixels " + e.getKey() + " now=" + e.getValue() + " golden=" + goldenPixels.get(e.getKey()));
        }
        assertTrue(bad.isEmpty(), "ゴールデンと違う（" + bad.size() + " 件）: " + (bad.size() > 12 ? bad.subList(0, 12) : bad));
    }

    @Test
    void scriptIsDeterministic() throws IOException {
        // ゴールデンの前提: 同じ入力で 2 回流して同じ値になる（実時間に依る経路が混じっていない）
        Map<String, String[]> a = runAll(), b = runAll();
        List<String> bad = new ArrayList<>();
        a.forEach((k, v) -> { if (!java.util.Arrays.equals(v, b.get(k))) bad.add(k + " " + java.util.Arrays.toString(v) + " vs " + java.util.Arrays.toString(b.get(k))); });
        assertEquals(List.of(), bad);
        assertTrue(a.size() >= DOCS.size() * 8, "記録したフレームが少ない: " + a.size());
        for (String doc : DOCS) assertTrue(a.containsKey(doc + "/textLong"), doc + " の文字の変更が流れていない");
    }

    // ---- ② 差分の経路 = 全体の作り直し -------------------------------------------------------------------

    /**
     * 項目の見えている値。位置は祖先の節（親相対の原点と、節の移動量）を足した画面の位置にする: 差分の経路はスクロールの量を
     * 中身の節の移動量で持ち、全体の作り直しは子の位置に焼き込むので、項目の欄をそのまま比べると表現の違いで食い違う。
     * クリップは合成の仕方が同じ理由で違いうるので比べない（画素で比べる）。
     */
    private static List<String> visibleValues(List<DisplayItem> items) {
        Map<String, DisplayItem> byId = new java.util.HashMap<>(items.size() * 2);
        for (DisplayItem it : items) byId.putIfAbsent(it.id(), it);
        List<String> out = new ArrayList<>(items.size());
        for (DisplayItem it : items) {
            if (it.type() == com.nishiyu.enderframelib.core.paint.model.ElementType.NODE) {
                // 節は描かれない入れ物で、位置と移動量の分け方がまさに表現の違い。構成（id と種類）だけ比べ、効き目は子孫の位置で見る
                out.add(it.id() + "|NODE");
                continue;
            }
            float x = it.x(), y = it.y();
            boolean node = false;
            String pid = it.hints().parentId();
            for (int guard = 0; pid != null && !pid.isEmpty() && guard < 64; guard++) {
                DisplayItem anc = byId.get(pid);
                if (anc == null) break;
                if (anc.type() == com.nishiyu.enderframelib.core.paint.model.ElementType.NODE) {
                    if (anc.hints().relativeOrigin()) { x += anc.x(); y += anc.y(); }
                    x += anc.translateX();
                    y += anc.translateY();
                }
                pid = anc.hints().parentId();
            }
            out.add(it.id() + "|" + it.type() + "|" + x + "," + y + "," + it.width() + "," + it.height() + "|"
                    + Integer.toHexString(it.color()) + "," + Integer.toHexString(it.bgColor()) + "," + it.radius() + "," + it.opacity() + "|"
                    + (node ? "" : it.translateX() + "," + it.translateY() + ",") + it.scaleX() + "," + it.scaleY() + "," + it.rotate() + "|" + it.text());
        }
        return out;
    }

    /**
     * 文書ごとに「差分の経路（既定）」と「近道を切って毎フレーム全体を作り直す」を流して、最初に食い違ったフレームの説明を集める。
     * {@code withItems} なら表示リストの値も 1 対 1 で比べる（スクロールの節の近道は両方で入れたまま）。
     */
    private static List<String> compareIncrementalWithFull(boolean withItems) throws Exception {
        List<String> bad = new ArrayList<>();
        for (String doc : DOCS) {
            List<Frame> incremental = new ArrayList<>(), full = new ArrayList<>();
            setShortcuts(true);
            try (SoftwareRenderBackend sw = new SoftwareRenderBackend(W, H, 1f)) {
                script(doc, sw, false, true, incremental::add);
            }
            setShortcuts(false, withItems);
            try (SoftwareRenderBackend sw = new SoftwareRenderBackend(W, H, 1f)) {
                script(doc, sw, true, true, full::add);
            }
            setShortcuts(true);
            assertEquals(full.size(), incremental.size(), doc + " のフレーム数");
            for (int f = 0; f < full.size(); f++) {
                Frame e = full.get(f), a = incremental.get(f);
                String why = null;
                if (!e.hits().equals(a.hits())) why = "ヒットテスト\n  full: " + e.hits() + "\n  incr: " + a.hits();
                else if (!withItems) { }
                else if (e.items().size() != a.items().size()) why = "項目数 " + e.items().size() + " ≠ " + a.items().size();
                else {
                    List<String> ev = visibleValues(e.items()), av = visibleValues(a.items());
                    for (int i = 0; i < ev.size() && why == null; i++) {
                        if (!ev.get(i).equals(av.get(i))) why = "項目 " + i + "\n  full: " + ev.get(i) + "\n  incr: " + av.get(i);
                    }
                }
                if (why == null && !java.util.Arrays.equals(e.pixels(), a.pixels())) {
                    int n = 0;
                    for (int i = 0; i < e.pixels().length; i++) if (e.pixels()[i] != a.pixels()[i]) n++;
                    why = "画素 " + n + " 個";
                }
                if (why != null) { bad.add(a.name() + ": " + why); break; } // 文書ごとに最初の食い違いだけ
            }
        }
        return bad;
    }

    @Test
    void incrementalMatchesFullRebuildEveryFrame() throws Exception {
        long stack0 = DisplayListBuilder.STAT_STACKING_PATCH_HITS.sum();
        long slice0 = DisplayListBuilder.STAT_SLICE_PATCH_HITS.sum();
        long text0 = UiView.TEXT_PATCH_HITS.get();
        assertEquals(List.of(), compareIncrementalWithFull(true));
        // 空の検証ではない: 差分の経路（箱のスライスの差し替え・文字のパッチ・部分木の組み直し）が実際に使われた
        assertTrue(DisplayListBuilder.STAT_SLICE_PATCH_HITS.sum() > slice0, "箱のスライスの差し替えが使われた");
        assertTrue(UiView.TEXT_PATCH_HITS.get() > text0, "文字のパッチが使われた");
        assertTrue(DisplayListBuilder.STAT_STACKING_PATCH_HITS.sum() > stack0, "スタッキングコンテキストの部分木の組み直しが使われた");
    }

    /** スクロールする中身の節（sticky を含むものも）まで切った作り直しと、画素とヒットテストが毎フレーム一致する。 */
    @Test
    void incrementalMatchesRebuildWithoutScrollNodesEveryFrame() throws Exception {
        assertEquals(List.of(), compareIncrementalWithFull(false));
    }

    // ---- ③ GPU = SOFTWARE -----------------------------------------------------------------------------

    private static int maxDiff(int[] a, int[] b) {
        int max = 0;
        for (int i = 0; i < a.length; i++) {
            if (a[i] == b[i]) continue;
            for (int sh = 0; sh < 32; sh += 8) max = Math.max(max, Math.abs(((a[i] >>> sh) & 0xFF) - ((b[i] >>> sh) & 0xFF)));
        }
        return max;
    }

    /** 差が {@link #GPU_TOLERANCE} を超える画素の数と外接矩形（原因の切り分け用）。 */
    static String diffBox(int[] a, int[] b) {
        int n = 0, x0 = W, y0 = H, x1 = -1, y1 = -1;
        for (int i = 0; i < a.length; i++) {
            if (maxDiff(new int[]{a[i]}, new int[]{b[i]}) <= GPU_TOLERANCE) continue;
            n++;
            int x = i % W, y = i / W;
            x0 = Math.min(x0, x); y0 = Math.min(y0, y); x1 = Math.max(x1, x); y1 = Math.max(y1, y);
        }
        return "（" + n + " 画素・範囲 " + x0 + "," + y0 + " – " + x1 + "," + y1 + "）";
    }

    private interface BackendFactory { com.nishiyu.enderframelib.core.paint.RenderBackend open() throws Exception; }

    private static void gpuVsSoftware(String api, BackendFactory factory, List<String> bad, Map<String, Integer> worst) throws Exception {
        for (String doc : DOCS) {
            List<Frame> cpu = new ArrayList<>(), gpu = new ArrayList<>();
            try (SoftwareRenderBackend sw = new SoftwareRenderBackend(W, H, 1f)) {
                script(doc, sw, false, true, cpu::add);
            }
            try (var g = factory.open()) {
                script(doc, g, false, true, gpu::add);
            }
            assertEquals(cpu.size(), gpu.size(), api + " " + doc + " のフレーム数");
            int docWorst = 0;
            int allowed = GPU_KNOWN.getOrDefault(api + " " + doc, GPU_TOLERANCE);
            for (int f = 0; f < cpu.size(); f++) {
                int d = maxDiff(cpu.get(f).pixels(), gpu.get(f).pixels());
                docWorst = Math.max(docWorst, d);
                if (d > allowed) { bad.add(api + " " + gpu.get(f).name() + ": SOFTWARE との最大差 " + d + "（許容 " + allowed + "）" + diffBox(cpu.get(f).pixels(), gpu.get(f).pixels())); break; }
            }
            worst.put(api + " " + doc, docWorst);
        }
    }

    /** GPU と SOFTWARE の 1 チャンネルの最大差の許容（{@code GpuBaselineTest} と同じ）。 */
    private static final int GPU_TOLERANCE = 8;

    /**
     * 既知の GPU と SOFTWARE の差（API と文書 → 許す最大差）。{@code golden/realdocs-gpu-known.txt}。原因を直したら行を消す
     * （ENGINE_REARCHITECTURE_PLAN T2 の残り）。載っていない組み合わせは {@link #GPU_TOLERANCE}。
     */
    private static final Map<String, Integer> GPU_KNOWN = readGpuKnown();

    private static Map<String, Integer> readGpuKnown() {
        Map<String, Integer> m = new LinkedHashMap<>();
        try {
            Map<String, String> raw = read("/golden/realdocs-gpu-known.txt");
            if (raw != null) raw.forEach((k, v) -> m.put(k, Integer.parseInt(v.split("\\s+")[0])));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return m;
    }

    @Test
    void gpuMatchesSoftwareEveryFrame() throws Exception {
        List<String> bad = new ArrayList<>();
        Map<String, Integer> worst = new LinkedHashMap<>();
        int apis = 0;
        try (var probe = new com.nishiyu.enderframelib.core.paint.gl.LwjglGlRenderBackend(8, 8)) {
            apis++;
        } catch (Throwable t) {
            System.out.println("T2: OpenGL を使えないので GL は飛ばす: " + t);
        }
        if (apis == 1) gpuVsSoftware("GL", () -> new com.nishiyu.enderframelib.core.paint.gl.LwjglGlRenderBackend(W, H), bad, worst);
        boolean vk = false;
        // CPU 上のソフトウェア実装（WSL の lavapipe）は外す: voidwave の backdrop-filter のぼかしのパイプラインを作るところでネイティブに
        // 落ち、テストの JVM ごと止まる。検証レイヤーを有効にすると実機でも同じ経路（drawFx の vkUpdateDescriptorSets）で検証レイヤーが
        // 落ちるので、検証レイヤーは使わない（記述子の更新が不正な疑い。T2b）
        try (var probe = new com.nishiyu.enderframelib.core.paint.vulkan.VkRenderBackend(8, 8)) {
            vk = !probe.device().context().isCpuDevice();
            if (!vk) System.out.println("T2: Vulkan のデバイスが CPU 上のソフトウェア実装なので Vulkan は飛ばす");
        } catch (Throwable t) {
            System.out.println("T2: Vulkan を使えないので Vulkan は飛ばす: " + t);
        }
        if (vk) {
            apis++;
            gpuVsSoftware("VK", () -> new com.nishiyu.enderframelib.core.paint.vulkan.VkRenderBackend(W, H), bad, worst);
        }
        Assumptions.assumeTrue(apis > 0, "GPU が無い（CI）");
        System.out.println("T2 GPU vs SOFTWARE max diff per doc: " + worst);
        assertEquals(List.of(), bad);
    }
}
