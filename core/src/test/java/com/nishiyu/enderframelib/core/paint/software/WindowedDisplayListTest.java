package com.nishiyu.enderframelib.core.paint.software;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.common.UiView;
import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.paint.DisplayListBuilder;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 窓付きの表示リスト（大きい文書で、画面と余白の外の箱の項目を作らない）の検証。
 * ① 局所パッチ（子の増減・文字・スタイル）の結果が、毎回全体を窓付きで組み直した結果と一致する。
 * ② 窓付きと窓なしで、スクロール・変更のあとの全画素が一致する（見た目は変わらない）。
 */
class WindowedDisplayListTest {
    private static final int W = 400, H = 300, ROWS = 1200;

    @AfterEach
    void restore() {
        DisplayListBuilder.WINDOWED_ENABLED = true;
        UiView.childPatchEnabled = true;
    }

    private static String html() {
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < ROWS; i++) {
            rows.append("<div class=row id=r-").append(i).append(">Item ").append(i)
                    .append(" <span class=tag>t").append(i % 7).append("</span> some text</div>");
        }
        return "<style>body{margin:4px;background:#222;color:#eee;font-size:13px;line-height:16px}"
                + ".row{padding:3px 6px;margin:1px 0;background:#334;border-bottom:1px solid #556}"
                + ".row.hot{background:#643}.tag{background:#468;padding:0 3px;border-radius:2px}"
                + ".row:hover{background:#456}</style><div id=list>" + rows + "</div>";
    }

    private static HtmlNode list(StandaloneView v) {
        return v.root().getNodeById("list");
    }

    private static HtmlElement fresh(int n) {
        HtmlElement e = new HtmlElement("div");
        e.setAttribute("class", "row");
        e.updateTextContent("new " + n);
        return e;
    }

    static String lastOp = "";

    /** 乱数で選んだ位置（可視・余白・遠い画面外）への挿入・削除・文字・スタイルの変更。 */
    private static void mutate(StandaloneView v, Random rnd, int step) {
        HtmlNode p = list(v);
        List<HtmlNode> kids = new ArrayList<>(p.children);
        int pos = switch (rnd.nextInt(4)) {
            case 0 -> rnd.nextInt(12);               // 可視
            case 1 -> 12 + rnd.nextInt(10);          // 窓の境目
            default -> rnd.nextInt(kids.size());      // どこでも
        };
        pos = Math.min(pos, kids.size() - 1);
        HtmlNode at = kids.get(pos);
        int op = rnd.nextInt(5);
        lastOp = "op" + op + " pos=" + pos + " id=" + at.getId();
        switch (op) {
            case 0, 1 -> p.insertBefore(fresh(step), at);
            case 2 -> { if (kids.size() > 50) p.removeChild(at); }
            case 3 -> { if (at instanceof HtmlElement el) el.updateTextContent("edit " + step + " " + "w ".repeat(rnd.nextInt(6))); }
            default -> { if (at instanceof HtmlElement el) el.toggleClass("hot"); }
        }
    }

    @Test
    void windowedPatchMatchesWindowedFullRebuild() {
        long windowedBefore = DisplayListBuilder.STAT_WINDOWED_BUILDS.sum();
        for (long seed = 1; seed <= 4; seed++) {
            StandaloneView full = new StandaloneView(html(), W, H, FontResolver.FALLBACK);
            StandaloneView patched = new StandaloneView(html(), W, H, FontResolver.FALLBACK);
            try {
                UiView.childPatchEnabled = false;
                full.ui().flushInvalidation();
                UiView.childPatchEnabled = true;
                patched.ui().flushInvalidation();
                Random rf = new Random(seed), rp = new Random(seed);
                for (int step = 0; step < 60; step++) {
                    UiView.childPatchEnabled = true;
                    mutate(full, rf, step);
                    mutate(patched, rp, step);
                    UiView.childPatchEnabled = false;
                    full.ui().flushInvalidation();
                    List<DisplayItem> exp = new ArrayList<>(full.ui().renderCache());
                    UiView.childPatchEnabled = true;
                    patched.ui().flushInvalidation();
                    List<DisplayItem> act = patched.ui().renderCache();
                    if (!exp.equals(act)) {
                        for (int i = 0; i < Math.min(exp.size(), act.size()); i++) if (!exp.get(i).equals(act.get(i))) { for (int j = Math.max(0, i - 4); j < Math.min(exp.size(), i + 6); j++) System.out.println("AT " + j + " exp " + brief(exp.get(j)) + " | act " + brief(act.get(j))); break; }
                        System.out.println("SIZE exp=" + exp.size() + " act=" + act.size() + " " + lastOp + " firstExp=" + brief(exp.get(0)) + " lastExp=" + brief(exp.get(exp.size() - 1)) + " lastAct=" + brief(act.get(act.size() - 1)));
                    }
                    assertEquals(exp.size(), act.size(), "seed " + seed + " 手順 " + step + ": 項目の数 " + lastOp);
                    for (int i = 0; i < exp.size(); i++) assertEquals(exp.get(i), act.get(i), "seed " + seed + " 手順 " + step + ": 項目 " + i + " " + lastOp);
                }
            } finally {
                full.close();
                patched.close();
            }
        }
        assertTrue(DisplayListBuilder.STAT_WINDOWED_BUILDS.sum() > windowedBefore, "窓付きの描画が使われていない");
    }

    private static String brief(DisplayItem it) {
        return it.id() + " " + it.type() + " (" + it.x() + "," + it.y() + " " + it.width() + "x" + it.height() + ") bg=" + Integer.toHexString(it.bgColor()) + " c=" + Integer.toHexString(it.color()) + " tx=" + it.translateX() + "," + it.translateY();
    }

    private static int[] frame(StandaloneView v, SoftwareRenderBackend cpu, boolean windowed, long now) {
        DisplayListBuilder.WINDOWED_ENABLED = windowed;
        v.frame(now, cpu, true);
        return cpu.pixmap().pixels().clone();
    }

    @Test
    void windowedAndFullListsRenderIdenticalPixels() {
        long windowedBefore = DisplayListBuilder.STAT_WINDOWED_BUILDS.sum();
        for (long seed = 1; seed <= 3; seed++) {
            StandaloneView a = new StandaloneView(html(), W, H, FontResolver.FALLBACK);
            StandaloneView b = new StandaloneView(html(), W, H, FontResolver.FALLBACK);
            try (SoftwareRenderBackend ca = new SoftwareRenderBackend(W, H, 1f); SoftwareRenderBackend cb = new SoftwareRenderBackend(W, H, 1f)) {
                long now = 1000;
                assertArrayEquals(frame(b, cb, false, now), frame(a, ca, true, now), "seed " + seed + " 初回");
                a.mouseMove(100, 100, ca.sessionId());
                b.mouseMove(100, 100, cb.sessionId());
                Random ra = new Random(seed), rb = new Random(seed), rs = new Random(seed * 7);
                StringBuilder log = new StringBuilder();
                for (int step = 0; step < 80; step++) {
                    now += 16;
                    int kind = rs.nextInt(4);
                    if (kind == 0) { // 小さいスクロール（平行移動の範囲）と大きいスクロール（作り直し）
                        double d = (rs.nextInt(3) == 0 ? 25 : 3) * (rs.nextBoolean() ? 1 : -1);
                        log.append(" S").append(d);
                        a.scroll(0, d, ca.sessionId());
                        b.scroll(0, d, cb.sessionId());
                    } else if (kind == 1) {
                        double x = 20 + rs.nextInt(300), y = 20 + rs.nextInt(250);
                        log.append(" M").append((int) x).append(",").append((int) y);
                        a.mouseMove(x, y, ca.sessionId());
                        b.mouseMove(x, y, cb.sessionId());
                    } else {
                        log.append(" X");
                        mutate(a, ra, step);
                        mutate(b, rb, step);
                    }
                    int[] pb = frame(b, cb, false, now), pa = frame(a, ca, true, now);
                    if (!java.util.Arrays.equals(pb, pa)) {
                        java.util.Set<String> have = new java.util.HashSet<>();
                        for (DisplayItem it : a.ui().renderCache()) have.add(it.id());
                        int shown = 0;
                        for (DisplayItem it : b.ui().renderCache()) if (!have.contains(it.id()) && shown++ < 8) System.out.println("MISSING " + it.id() + " y=" + it.y() + " h=" + it.height());
                        List<DisplayItem> la = a.ui().renderCache(), lb = b.ui().renderCache();
                        int dn = 0;
                        for (int i = 0; i < Math.min(la.size(), lb.size()) && dn < 6; i++) if (!la.get(i).equals(lb.get(i))) { dn++; System.out.println("DIFF " + i + " win: " + brief(la.get(i)) + " ALL: " + brief(lb.get(i))); }
                        System.out.println("SIZES a=" + a.ui().renderCache().size() + " b=" + b.ui().renderCache().size() + " scrollA=" + a.ui().renderCache().stream().filter(i -> i.id() != null && i.id().endsWith("_scrollc")).map(i -> i.id() + ":" + i.translateY()).toList());
                    }
                    assertArrayEquals(pb, pa, "seed " + seed + " 手順 " + step + " (種別 " + kind + ")");
                }
            } finally {
                a.close();
                b.close();
            }
        }
        assertTrue(DisplayListBuilder.STAT_WINDOWED_BUILDS.sum() > windowedBefore, "窓付きの描画が使われていない");
    }
}
