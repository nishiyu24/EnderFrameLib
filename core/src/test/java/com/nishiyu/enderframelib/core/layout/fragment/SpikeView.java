package com.nishiyu.enderframelib.core.layout.fragment;

import com.nishiyu.enderframelib.common.Document;
import com.nishiyu.enderframelib.common.DocumentLoader;
import com.nishiyu.enderframelib.core.box.BoxNode;
import com.nishiyu.enderframelib.core.box.BoxTreeCompiler;
import com.nishiyu.enderframelib.core.box.StyleSource;
import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.dom.node.ChangeLedger;
import com.nishiyu.enderframelib.core.dom.node.HtmlDocument;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.text.model.FontResolver;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * M2a の縦割りスパイクのハーネス（テスト用）。実 DOM（{@code HtmlDocument}）＋ M1 の実 {@code Style} を、旧 {@code LayoutBox} の経路を
 * 通さずに {@link BoxTreeCompiler} → {@link FragmentLayout} へ直接つなぐ。1 フレーム = 台帳の凍結 → コンパイル → 整形。
 */
final class SpikeView {
    final Document doc;
    final InteractionState state = new InteractionState();
    final StyleSource styles;
    final BoxTreeCompiler compiler;
    final FragmentLayout layout;
    final int vw, vh;
    BoxNode boxRoot;

    SpikeView(String html, int vw, int vh) {
        this.vw = vw;
        this.vh = vh;
        this.doc = DocumentLoader.parseDocument(html, null, vw, vh);
        ((HtmlDocument) doc.root).setInteractionState(state);
        this.styles = new StyleSource(doc.sheet, state, null, vw, vh);
        this.compiler = new BoxTreeCompiler(styles);
        this.layout = new FragmentLayout(new LayoutSession(FontResolver.FALLBACK));
    }

    ChangeLedger ledger() {
        return state.changeLedger();
    }

    /** 1 フレーム: 台帳を凍結し、箱の木を増分で作り、整形する。 */
    LayoutFragment frame() {
        ledger().freeze();
        boxRoot = compiler.compile(doc.root, ledger());
        return layout.layout(boxRoot, vw, vh);
    }

    /** 箱の木だけを作る（計測用）。 */
    BoxNode compileOnly() {
        ledger().freeze();
        boxRoot = compiler.compile(doc.root, ledger());
        return boxRoot;
    }

    HtmlNode byId(String id) {
        return doc.root.getNodeById(id);
    }

    LayoutCache.Stats cacheStats() {
        return layout.session().cache().stats();
    }

    /** 同じ DOM をゼロから（前回の木もキャッシュも無しで）作った結果（FrameOracle 相当の独立の面）。 */
    LayoutFragment scratch() {
        BoxTreeCompiler fresh = new BoxTreeCompiler(new StyleSource(doc.sheet, state, null, vw, vh));
        BoxNode root = fresh.compile(doc.root, null);
        return new FragmentLayout(new LayoutSession(FontResolver.FALLBACK)).layout(root, vw, vh);
    }

    /** 旧経路（{@code StandaloneView}）を描画なしで回すためのバックエンド（比較・A/B 用）。 */
    static final class NullBackend implements com.nishiyu.enderframelib.core.paint.RenderBackend {
        @Override public void render(List<com.nishiyu.enderframelib.core.paint.model.DisplayItem> items, long nowMs) { }
        @Override public com.nishiyu.enderframelib.core.raster.Pixmap pixmap() { throw new UnsupportedOperationException(); }
        @Override public float devicePixelRatio() { return 1f; }
    }

    // ---- ダンプ（絶対座標） ----

    /** 断片木を絶対座標の行の列にする（全再計算との比較用）。 */
    static List<String> dump(LayoutFragment root) {
        List<String> out = new ArrayList<>();
        dump(root, 0, 0, 0, out);
        return out;
    }

    private static void dump(LayoutFragment f, float x, float y, int depth, List<String> out) {
        String name = f.box == null ? "(anon)" : f.box.node.tag + idOf(f.box.node);
        out.add(String.format(Locale.ROOT, "%s%s %.3f,%.3f %.3fx%.3f", "  ".repeat(depth), name, x, y, f.width, f.height));
        float cx = x + f.insetLeft, cy = y + f.insetTop;
        if (f.lines != null) {
            for (LineFragment l : f.lines) {
                StringBuilder sb = new StringBuilder();
                for (LineFragment.Piece p : l.pieces()) sb.append('[').append(p.string()).append('@').append(fmt(p.x())).append(']');
                out.add(String.format(Locale.ROOT, "%s| %.3f,%.3f %.3fx%.3f %s", "  ".repeat(depth + 1), cx + l.x(), cy + l.y(), l.width(), l.height(), sb));
            }
        }
        if (f.flow != null) f.flow.forEach((c, x1, y1) -> dump(c, cx + x1, cy + y1, depth + 1, out));
        if (f.oof != null) for (PlacedFragment p : f.oof) dump(p.frag(), x + p.x(), y + p.y(), depth + 1, out);
    }

    private static String fmt(float v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }

    private static String idOf(HtmlNode n) {
        String id = n.attrs.get("id");
        return id != null ? "#" + id : "";
    }

    /** {@code id} の要素の断片の絶対位置 {x, y, w, h}（無ければ null）。 */
    static float[] find(LayoutFragment root, String id) {
        float[][] hit = {null};
        find(root, 0, 0, id, hit);
        return hit[0];
    }

    private static void find(LayoutFragment f, float x, float y, String id, float[][] hit) {
        if (hit[0] != null) return;
        if (f.box != null && id.equals(f.box.node.attrs.get("id"))) {
            hit[0] = new float[]{x, y, f.width, f.height};
            return;
        }
        float cx = x + f.insetLeft, cy = y + f.insetTop;
        if (f.flow != null) f.flow.forEach((c, x1, y1) -> find(c, cx + x1, cy + y1, id, hit));
        if (f.oof != null) for (PlacedFragment p : f.oof) find(p.frag(), x + p.x(), y + p.y(), id, hit);
    }

    // ---- 文書 ----

    static String largeList(int count) {
        StringBuilder sb = new StringBuilder(count * 80 + 256);
        sb.append("<html><head><style>body { margin: 8px; } ul { list-style: none; padding: 0; } li { padding: 4px; border-bottom: 1px solid #ccc; }</style></head><body>");
        sb.append("<div id=\"scroller\" style=\"height: 400px; overflow-y: auto;\"><ul id=\"list\">");
        for (int i = 1; i <= count; i++) {
            sb.append("<li id=\"item-").append(i).append("\">Row item #").append(i).append(" with description text</li>");
        }
        return sb.append("</ul></div></body></html>").toString();
    }

    private static final String[] PALETTE = {"#fff", "#eef", "#fee", "#efe", "#ffe", "#eff", "#fef", "#dde", "#edd", "#ded", "#eed"};
    private static final String[] BORDER = {"#ccc", "#99c", "#c99", "#9c9", "#cc9", "#9cc", "#c9c", "#888"};
    private static final String LOREM = "alpha beta gamma delta epsilon zeta eta theta iota kappa ";

    /** {@code MemoryAndLatencyGate.variedList} と同じ構造。 */
    static String variedList(int count) {
        StringBuilder sb = new StringBuilder(count * 240 + 256);
        sb.append("<html><head><style>body { margin: 8px; } ul { list-style: none; padding: 0; } li { border-bottom: 1px solid #ccc; } li:hover { color: #c00 !important; }</style></head><body>");
        sb.append("<div id=\"scroller\" style=\"height: 400px; overflow-y: auto;\"><ul id=\"list\">");
        for (int i = 1; i <= count; i++) {
            int pad = 2 + (i * 7) % 9, mb = i % 5, bl = (i * 3) % 5, fs = 80 + (i * 5) % 6 * 10;
            sb.append("<li id=\"item-").append(i).append("\" style=\"padding:").append(pad).append("px ").append(pad + (i % 4)).append("px;margin-bottom:")
                    .append(mb).append("px;background:").append(PALETTE[(i * 3) % PALETTE.length]).append(";border-left:").append(bl).append("px solid ")
                    .append(BORDER[i % BORDER.length]).append(";border-bottom:").append(1 + i % 3).append("px solid ").append(BORDER[(i * 5) % BORDER.length])
                    .append(";font-size:").append(fs).append("%").append(i % 6 == 0 ? ";color:#224" : "").append("\">");
            sb.append("Row item #").append(i).append(" ").append(LOREM.repeat(1 + (i * 13) % 6));
            if (i % 3 == 0) sb.append("<span style=\"background:").append(PALETTE[i % PALETTE.length]).append(";padding:1px ").append(2 + i % 5).append("px;border-radius:").append(i % 4).append("px\">tag").append(i % 7).append("</span>");
            if (i % 4 == 0) sb.append(" <b>bold ").append(i % 10).append("</b>");
            if (i % 7 == 0) sb.append("<div style=\"margin-top:").append(i % 5).append("px;padding-left:").append(4 + i % 11).append("px;color:#666;font-size:").append(70 + i % 4 * 10).append("%\">sub ").append(i).append(" ").append(LOREM.repeat(1 + i % 3)).append("</div>");
            sb.append("</li>");
        }
        return sb.append("</ul></div></body></html>").toString();
    }

    /**
     * 深さ {@code depth} の入れ子 flex（各段: row・{@code flex-grow}・{@code flex-wrap}・内容基準の基準の大きさ）。
     * 葉は {@code leaf-<段>-<番号>}、各段の容器は {@code f<段>-<番号>}。
     */
    static String nestedFlex(int depth, int fanout) {
        return nestedFlex(depth, fanout, false);
    }

    /**
     * {@code uneven} なら、番号の末尾が 1 の葉に経路ごとに違う高さを与えて兄弟の部分木の高さを不ぞろいにする。各段の row が短い項目を stretch で
     * 測り直し（pass 2）、伸びた column がさらに中の項目を flex-grow で測り直す、という 2 パスが段ごとに連鎖する。
     */
    static String nestedFlex(int depth, int fanout, boolean uneven) {
        StringBuilder sb = new StringBuilder("<html><head><style>body{margin:0} .f{display:flex;flex-wrap:wrap;padding:2px;border:1px solid #888}"
                + " .c{display:flex;flex-direction:column;padding:1px} .g{flex-grow:1} .leaf{padding:1px 3px}</style></head><body>");
        nest(sb, depth, fanout, "0", uneven);
        return sb.append("</body></html>").toString();
    }

    private static void nest(StringBuilder sb, int depth, int fanout, String path, boolean uneven) {
        if (depth == 0) {
            String h = uneven && path.endsWith("1") ? " style=\"height:" + (12 + (path.hashCode() & 0x7fff) % 23) + "px\"" : "";
            sb.append("<span class=\"leaf\" id=\"leaf-").append(path).append("\"").append(h).append(">leaf ").append(path).append(" text</span>");
            return;
        }
        String cls = depth % 2 == 0 ? "f" : "c";
        sb.append("<div class=\"").append(cls).append(" g\" id=\"f").append(path).append("\">");
        for (int i = 0; i < fanout; i++) nest(sb, depth - 1, fanout, path + i, uneven);
        sb.append("</div>");
    }
}
