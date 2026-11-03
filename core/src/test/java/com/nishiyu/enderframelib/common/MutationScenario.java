package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.software.SoftwareRenderBackend;
import com.nishiyu.enderframelib.core.text.model.FontResolver;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 差分テストの共通部品: 文書に乱数で DOM の書き換え・マウスの移動・リサイズ・時間の経過を 1 手ずつ与え、毎手の表示リストの署名を返す。
 * 設定（フラグ）だけを変えて同じ手順を 2 回走らせ、署名の列が同じになることを確かめるのに使う。
 */
final class MutationScenario {
    private static final int[][] SIZES = {{320, 240}, {260, 240}, {400, 300}};

    /** 直近の手順の 1 手ごとの説明（失敗の再現・調査用）。 */
    static final List<String> LOG = new ArrayList<>();

    private MutationScenario() {}

    static String sig(DisplayItem i) {
        return i.id() + "|" + i.baseId() + "|" + i.type() + "|" + i.x() + "," + i.y() + "," + i.width() + "," + i.height()
                + "|" + i.text() + "|" + i.color() + "|" + i.bgColor() + "|" + i.radius() + "|" + i.opacity()
                + "|" + i.translateX() + "," + i.translateY() + "," + i.scaleX() + "," + i.scaleY() + "," + i.rotate()
                + "|" + i.transitionDuration() + "|" + i.events() + "|" + (i.animDef() == null ? "" : i.animDef().frames().size());
    }

    static void elements(HtmlNode node, List<HtmlElement> out) {
        for (HtmlNode c : node.children) {
            if (c instanceof HtmlElement e && !e.tag.startsWith("#")) {
                out.add(e);
                elements(e, out);
            }
        }
    }

    /** {@code seed} の手順を {@code steps} 手進め、毎手の表示リストの署名を返す。 */
    static List<List<String>> run(String html, String[] classes, String[] styles, long seed, int steps) {
        return run(html, classes, styles, seed, steps, true);
    }

    /** 描画先に何もしないもの（表示リストを作る経路だけを走らせたい重いページ用。ソフトウェア描画は blur などで遅い）。 */
    static final class NoDraw implements com.nishiyu.enderframelib.core.paint.RenderBackend {
        private final SoftwareRenderBackend tiny = new SoftwareRenderBackend(1, 1, 1f);

        @Override public void render(List<DisplayItem> items, long nowMs) {
            // 本物のバックエンドは描くたびに全項目の補間状態を更新する（ヒットテストはその値を読む）
            for (DisplayItem it : items) com.nishiyu.enderframelib.core.paint.CssAnimator.getValuesAt(sessionId(), it, nowMs);
        }
        @Override public com.nishiyu.enderframelib.core.raster.Pixmap pixmap() { return tiny.pixmap(); }
        @Override public float devicePixelRatio() { return 1f; }
        @Override public String sessionId() { return tiny.sessionId(); }
        @Override public void close() { tiny.close(); }
    }

    static List<List<String>> run(String html, String[] classes, String[] styles, long seed, int steps, boolean draw) {
        Random rnd = new Random(seed);
        LOG.clear();
        StandaloneView view = new StandaloneView(html, 320, 240, FontResolver.FALLBACK);
        try (com.nishiyu.enderframelib.core.paint.RenderBackend backend = draw ? new SoftwareRenderBackend(400, 300, 1f) : new NoDraw()) {
            List<List<String>> out = new ArrayList<>();
            long now = 1000;
            view.frame(now, backend, true);
            for (int step = 0; step < steps; step++) {
                List<HtmlElement> els = new ArrayList<>();
                elements(view.root(), els);
                HtmlElement target = els.get(rnd.nextInt(els.size()));
                switch (rnd.nextInt(9)) {
                    case 0, 1 -> { String v = classes[rnd.nextInt(classes.length)]; LOG.add("class #" + target.getId() + " = '" + v + "'"); target.setAttribute("class", v); }
                    case 2 -> { String v = styles[rnd.nextInt(styles.length)]; LOG.add("style #" + target.getId() + " = '" + v + "'"); target.setAttribute("style", v); }
                    case 3 -> {
                        if (target.children.size() <= 1) {
                            String v = "t" + rnd.nextInt(100) + (rnd.nextBoolean() ? " long text for wrapping" : "");
                            LOG.add("text #" + target.getId() + " = '" + v + "'");
                            target.setTextContent(v);
                        }
                    }
                    case 4 -> {
                        HtmlElement add = (HtmlElement) view.root().ownerDocument.createElement(rnd.nextBoolean() ? "div" : "span");
                        add.setAttribute("id", "z" + step); // DOM API で作った要素は id を持たないので、明示する（showcase と同じ）
                        add.setAttribute("class", classes[rnd.nextInt(classes.length)]);
                        add.setTextContent("n" + step);
                        LOG.add("append <" + add.tag + " class='" + add.attrs.get("class") + "'> to #" + target.getId());
                        target.appendChild(add);
                    }
                    case 5 -> {
                        if (!target.children.isEmpty() && !"d".equals(target.getId())) {
                            HtmlNode gone = target.children.get(rnd.nextInt(target.children.size()));
                            LOG.add("remove " + gone.tag + "#" + gone.getId() + " from #" + target.getId());
                            target.removeChild(gone);
                        }
                    }
                    case 6 -> {
                        int mx = rnd.nextInt(420) - 10, my = rnd.nextInt(320) - 10;
                        LOG.add("mouse " + mx + "," + my);
                        view.mouseMove(mx, my, backend.sessionId());
                    }
                    case 7 -> {
                        int[] size = SIZES[rnd.nextInt(SIZES.length)];
                        LOG.add("resize " + size[0] + "x" + size[1]);
                        view.resize(size[0], size[1]);
                    }
                    default -> LOG.add("(time only)"); // 時間だけ進める
                }
                now += 30 + rnd.nextInt(400);
                view.frame(now, backend, true);
                // 連続したリサイズは、直前のレイアウトにかかった実時間に応じて次のフレームへ持ち越される（壁時計）。
                // 手順を決定的にするため、反映されるまで同じ時刻のフレームを回す
                for (int spin = 0; spin < 2000 && (view.ui().rootW() != view.width() || view.ui().rootH() != view.height()); spin++) {
                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    view.frame(now, backend, true);
                }
                List<String> sigs = new ArrayList<>();
                java.util.Set<String> ids = new java.util.HashSet<>();
                for (DisplayItem it : view.items()) {
                    assertTrue(ids.add(it.id()), "seed " + seed + " step " + step + ": 項目の id が重複: " + it.id());
                    sigs.add(sig(it));
                }
                out.add(sigs);
            }
            return out;
        } finally {
            view.close();
        }
    }
}
