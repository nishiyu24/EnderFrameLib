package com.nishiyu.enderframelib.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nishiyu.enderframelib.core.dom.ChangeReads;
import com.nishiyu.enderframelib.core.dom.node.ChangeLedger;
import com.nishiyu.enderframelib.core.dom.node.ChangeLedger.Kind;
import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.node.HtmlText;
import com.nishiyu.enderframelib.core.paint.DisplayListBuilder;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * K5b L0・L1（変更台帳。設計書 §3.6）: ①台帳の単体〔追記・凍結・世代・祖先を辿る {@code touches}・あふれ・節を絞れない変更〕
 * ②読み手 {@link ChangeReads}〔文字の節・描画だけの属性の節を台帳から導く〕③<b>台帳が文字・描画だけの属性の唯一の入口</b>:
 * 乱数の操作列のどの手でも、結果は最初から作った結果と一致する。台帳の追記が取りこぼされる変異は、結果が古いままになって殺される。
 */
public class ChangeLedgerTest {
    /** root(div) > [a(section) > b(p)], c(span)。{@code out} に b・c・a を返す。 */
    private static HtmlElement tree(HtmlElement[] out) {
        HtmlElement root = new HtmlElement("div"), a = new HtmlElement("section"), b = new HtmlElement("p"), c = new HtmlElement("span");
        root.appendChild(a);
        a.appendChild(b);
        root.appendChild(c);
        out[0] = b;
        out[1] = c;
        out[2] = a;
        return root;
    }

    @Test
    void appendFreezeAndTouchesWalkAncestorsOnly() {
        HtmlElement[] n = new HtmlElement[3];
        HtmlElement root = tree(n);
        HtmlElement b = n[0], c = n[1], a = n[2];
        ChangeLedger l = new ChangeLedger();
        assertEquals(0L, l.freeze());
        assertEquals(0L, l.epoch(), "何も無い静止フレームは世代を進めない");
        l.append(b, Kind.TEXT);
        l.append(b, Kind.TEXT); // 同じ節の同じ変更が続くだけ
        assertEquals(1, l.pendingCount());
        assertTrue(l.pendingHas(b, Kind.TEXT));
        assertFalse(l.pendingHas(b, Kind.ATTR));
        assertEquals(1L, l.freeze());
        assertEquals(0, l.pendingCount());
        assertEquals(1, l.frozenSize());
        assertTrue(l.frozenIs(0, Kind.TEXT) && l.frozenNode(0) == b);
        assertTrue(l.touches(b) && l.touches(a) && l.touches(root), "自分と祖先は触れられている");
        assertFalse(l.touches(c), "兄弟の部分木は触れられていない");
        assertEquals(2L, l.freeze(), "凍結した世代が残っているので次の凍結で世代が進み、中身は空になる");
        assertFalse(l.touches(b));
        assertEquals(0, l.frozenSize());
        assertEquals(2L, l.freeze(), "空から空は進めない");
    }

    @Test
    void nodelessAndOverflowTouchEverything() {
        HtmlElement[] n = new HtmlElement[3];
        tree(n);
        ChangeLedger l = new ChangeLedger();
        l.append(null, Kind.ENV);
        l.append(n[0], Kind.TEXT);
        l.freeze();
        assertTrue(l.touches(n[1]), "節を絞れない変更は全体に効く");
        assertFalse(l.frozenOverflow());
        assertEquals(2, l.frozenSize(), "節を絞れない変更があっても、個々の変更は読める");
        l.freeze();
        for (int i = 0; i < ChangeLedger.MAX_ENTRIES + 5; i++) l.append(new HtmlElement("i"), Kind.STYLE);
        assertEquals(Integer.MAX_VALUE, l.pendingCount(), "あふれたら個々を捨てる");
        l.freeze();
        assertTrue(l.touches(n[1]));
        assertTrue(l.frozenOverflow());
        assertNull(ChangeReads.texts(l), "あふれた世代は個々に頼らず全体を作り直す（読み手は null）");
    }

    @Test
    void readsDeriveTextAndPaintAttrNodesFromTheFacts() {
        HtmlElement[] n = new HtmlElement[3];
        tree(n);
        ChangeLedger l = new ChangeLedger();
        l.append(n[0], Kind.TEXT);
        l.append(n[1], Kind.TEXT);
        l.append(n[0], Kind.TEXT);
        l.append(n[0], Kind.ATTR, "value");
        l.append(n[1], Kind.ATTR, "class");
        l.append(n[2], Kind.CHILDREN);
        l.freeze();
        assertEquals(List.of(n[0], n[1]), ChangeReads.texts(l), "文字の節は重複なしで（追記の順）");
        assertEquals(List.of(n[0]), ChangeReads.paintAttrs(l, (node, name) -> !name.equals("class")), "描画だけの属性は呼び出し側の分類で絞る");
        l.freeze();
        assertEquals(List.of(), ChangeReads.texts(l));
    }

    private static final String HTML = "<style>.hot{color:red} .row:hover{background:#eef} .big{font-size:20px}</style>"
            + "<div id=list><div class=row id=r0>row 0</div><div class=row id=r1>row 1 <b>x</b></div><div class=row id=r2>row 2</div>"
            + "<div class=row id=r3>row 3</div><div class=row id=r4>row 4</div></div><progress id=pg value=3 max=10></progress><p id=tail>tail</p>";

    private static void walk(HtmlNode n, List<HtmlNode> out) {
        out.add(n);
        for (HtmlNode c : n.children) walk(c, out);
    }

    private static String sig(DisplayItem i) {
        return i.id() + "|" + i.baseId() + "|" + i.type() + "|" + i.x() + "," + i.y() + "," + i.width() + "," + i.height()
                + "|" + i.text() + "|" + i.color() + "|" + i.bgColor();
    }

    /** 今の表示リストと、同じ DOM から最初から作った表示リスト。 */
    private static List<List<String>> liveAndFresh(StandaloneView v) {
        UiView ui = v.ui();
        ui.flushInvalidation();
        List<String> live = new ArrayList<>(), fresh = new ArrayList<>();
        for (DisplayItem i : ui.renderCache()) live.add(sig(i));
        for (DisplayItem i : new DisplayListBuilder(320, 240, ui.document().sheet.keyframes, ui.scrollState()).render(ui.layoutRoot(), false)) fresh.add(sig(i));
        return List.of(live, fresh);
    }

    @Test
    void randomChangesMatchAFreshBuildAtEveryStepWithTheLedgerAsTheOnlyInlet() {
        for (long seed = 1; seed <= 12; seed++) {
            Random rnd = new Random(seed);
            try (StandaloneView v = new StandaloneView(HTML, 320, 240, FontResolver.FALLBACK); var be = new MutationScenario.NoDraw()) {
                ResizeSettle.freezeClock(v); // 止めた時計: リサイズの間引きが実時間に依らない
                v.frame(1000, be, true);
                for (int step = 0; step < 40; step++) {
                    List<HtmlNode> all = new ArrayList<>();
                    walk(v.root(), all);
                    HtmlNode t = all.get(rnd.nextInt(all.size()));
                    String what;
                    switch (rnd.nextInt(8)) {
                        case 0 -> { if (t instanceof HtmlElement e) e.setAttribute("class", rnd.nextBoolean() ? "row hot" : "row"); what = "class"; }
                        case 1 -> { if (t instanceof HtmlElement e) e.setAttribute("style", "padding-left:" + rnd.nextInt(20) + "px"); what = "style"; }
                        case 2, 3 -> { if (t instanceof HtmlText x) x.setData("t" + rnd.nextInt(1000) + (rnd.nextBoolean() ? " longer text to wrap" : "")); what = "text"; }
                        case 4 -> {
                            if (t instanceof HtmlElement e) {
                                HtmlElement add = (HtmlElement) v.root().ownerDocument.createElement("div");
                                add.setAttribute("class", "row");
                                add.setTextContent("n" + step);
                                e.appendChild(add);
                            }
                            what = "append";
                        }
                        case 5 -> { if (!t.children.isEmpty() && t != v.root()) t.removeChild(t.children.get(rnd.nextInt(t.children.size()))); what = "remove"; }
                        case 6 -> { HtmlNode pg = v.root().getNodeById("pg"); if (pg instanceof HtmlElement e) e.setAttribute("value", String.valueOf(rnd.nextInt(10))); what = "attr"; }
                        default -> { v.mouseMove(rnd.nextInt(320), rnd.nextInt(240), be.sessionId()); what = "hover"; }
                    }
                    List<List<String>> r = liveAndFresh(v);
                    assertEquals(r.get(1), r.get(0), "seed " + seed + " step " + step + " " + what);
                }
            }
        }
    }

    /**
     * 文字の測り直し〔{@code tryRelayoutInPlace(textBoxes)}〕の入口は台帳だけ: 追記が取りこぼされると、測り直しの経路に乗らない
     * 〔表示は再スタイルの汚れ印の別経路が拾うので壊れないが、それは L3 で台帳へ寄せる対象〕。
     */
    @Test
    void theLedgerIsTheOnlyInletOfTheTextRelayout() {
        for (boolean drop : new boolean[]{false, true}) {
            try (StandaloneView v = new StandaloneView(HTML, 320, 240, FontResolver.FALLBACK); var be = new MutationScenario.NoDraw()) {
                ResizeSettle.freezeClock(v);
                v.frame(1000, be, true);
                long hits = UiView.TEXT_PATCH_HITS.get();
                HtmlText text = (HtmlText) v.root().getNodeById("tail").children.get(0);
                text.setData("changed text that is rather longer");
                if (drop) {
                    ChangeLedger l = v.ui().interactionState().changeLedger();
                    l.freeze();
                    l.freeze(); // 追記が失われた状態（世代が 1 つ進んで捨てられる）
                }
                List<List<String>> r = liveAndFresh(v);
                assertEquals(r.get(1), r.get(0), "どちらでも結果は最初から作った結果と同じ");
                assertEquals(drop ? 0 : 1, UiView.TEXT_PATCH_HITS.get() - hits, drop ? "台帳に載らなければ文字の測り直しの経路に乗らない" : "台帳に載れば文字の測り直しの経路に乗る");
            }
        }
    }

    @Test
    void hoverAndResizeReachTheLedger() {
        try (StandaloneView v = new StandaloneView(HTML, 320, 240, FontResolver.FALLBACK); var be = new MutationScenario.NoDraw()) {
            ChangeLedger l = v.ui().interactionState().changeLedger();
            v.frame(1000, be, true);
            assertEquals(0, l.pendingCount());
            v.mouseMove(10, 5, be.sessionId());
            assertTrue(l.pendingCount() > 0, "ホバーで台帳に載る（対話状態の入口）");
            v.frame(1016, be, true);
            v.resize(300, 240);
            assertTrue(l.pendingHas(null, Kind.ENV), "リサイズは全体に効く（ENV）");
        }
    }
}
