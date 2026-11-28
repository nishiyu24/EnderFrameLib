package com.nishiyu.enderframelib.core.layout.fragment;

import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.paint.LayerOps;
import com.nishiyu.enderframelib.core.paint.SceneDisplayListAdapter;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.scene.DisplayScene;
import com.nishiyu.enderframelib.core.paint.scene.DynamicSceneState;
import com.nishiyu.enderframelib.core.paint.scene.SceneChunk;
import com.nishiyu.enderframelib.core.paint.scene.SceneEmitter;
import com.nishiyu.enderframelib.core.paint.scene.SceneLayer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 層を使い回す鍵は {@code (断片 ==, 最新の箱 ==)} だけ（M3b-4c-1c。計画書 §1.6 決定 U・§2.3 L4a）。親の文脈で決まる値が、鍵の当たった層で
 * 古いまま残らないこと:
 *
 * <ul>
 *   <li>描画される {@code <legend>} は親（fieldset）の値で、前に別の legend が入って流れの中の legend に替わっても正しい。</li>
 *   <li>親の {@code display} が block・flex・grid・table の間で替わると、子の重なりの種類（浮動・原子・重なりの文脈）・原点の印・
 *       伝える描画層が親の文脈に合う（子を引く表は親の {@code display} が同じときだけ使う）。</li>
 * </ul>
 * どちらも、増分で作ったシーンの全ての層の値と組み立てた表示項目が、同じ断片から作り直したシーンと一致することで確かめる。
 */
class SceneReuseKeyTest {
    private static final int W = 640, H = 480;

    @Test
    void renderedLegendIsAParentValue() {
        SpikeView v = new SpikeView("<html><head><style>body{margin:0} fieldset{border:3px solid #333;width:300px;margin:8px}"
                + " legend{background:#ff0}</style></head><body><fieldset id=fs><legend id=l1>First</legend><p id=p>body text</p>"
                + "<legend id=l2>Second</legend></fieldset></body></html>", W, H);
        Run run = new Run(v);
        run.check("初め");
        assertTrue(run.legendKey().endsWith("l1"), "描画される legend は最初の legend: " + run.legendKey());

        HtmlElement l0 = new HtmlElement("legend");
        l0.updateTextContent("Zero");
        v.byId("fs").insertBefore(l0, v.byId("l1"));
        run.check("前に legend を入れた");
        assertTrue(!run.legendKey().endsWith("l1"), "前に入れた legend が描画される legend になる: " + run.legendKey());

        v.byId("fs").removeChild(l0);
        run.check("入れた legend を消した");
        v.byId("l1").setAttribute("style", "position:absolute;left:0;top:0");
        run.check("最初の legend を絶対配置にした");
        assertTrue(!run.legendKey().endsWith("l1"), "絶対配置の legend は描画される legend でない: " + run.legendKey());
        v.byId("l1").setAttribute("style", "");
        run.check("絶対配置を外した");
    }

    @Test
    void childContextFollowsTheParentDisplay() {
        String kids = "<div id=fl style='float:left;width:40px;height:20px;background:#f00'>f</div>"
                + "<div id=z style='z-index:3;width:50px;height:20px;background:#0f0'>z</div>"
                + "<span id=ib style='display:inline-block;width:30px;height:10px;background:#00f'>ib</span>"
                + "<div id=ab style='position:absolute;left:5px;top:5px;width:10px;height:10px;background:#000'></div>"
                + "<div id=op style='opacity:.5;width:20px;height:20px;background:#0ff'></div>text";
        SpikeView v = new SpikeView("<html><head><style>body{margin:0}</style></head><body><div id=c style='position:relative'>" + kids
                + "</div></body></html>", W, H);
        Run run = new Run(v);
        run.check("block");
        for (String d : new String[]{"flex", "grid", "table", "inline-block", "flex", "block", "grid", "block"}) {
            v.byId("c").setAttribute("style", "position:relative;display:" + d);
            run.check("display:" + d);
        }
    }

    @Test
    void untouchedLayersAreReusedByTheKeyAlone() {
        SpikeView v = new SpikeView("<html><head><style>body{margin:0} .x{width:50px;height:12px;background:#09f;float:left}"
                + "</style></head><body><div id=c style='display:flex'><div id=a class=x>a</div><div id=b class=x>b</div></div>"
                + "</body></html>", W, H);
        Run run = new Run(v);
        SceneLayer a0 = find(run.scene.root, "a");
        assertNotNull(a0);
        v.byId("b").setAttribute("style", "background:#f00");
        run.check("隣の背景");
        assertSame(a0, find(run.scene.root, "a"), "flex の子（浮動の指定は効かない）は鍵が当たれば前回の層");
    }

    /** 増分の構築と、毎回同じ断片から作り直したシーンを比べる。 */
    private static final class Run {
        final SpikeView v;
        final SceneEmitter emitter;
        final SceneDisplayListAdapter adapter;
        final DynamicSceneState dyn = DynamicSceneState.of(null, W, H);
        DisplayScene scene;

        Run(SpikeView v) {
            this.v = v;
            this.emitter = new SceneEmitter(v.layout.session(), v.doc.sheet.keyframes);
            emitter.opsSource(new LayerOps(W, H, v.doc.sheet.keyframes));
            this.adapter = new SceneDisplayListAdapter(W, H, v.doc.sheet.keyframes);
            this.scene = emitter.emit(v.frame(), v.boxRoot, W, H);
        }

        void check(String what) {
            scene = emitter.emit(v.frame(), v.boxRoot, W, H);
            SceneEmitter freshEmitter = new SceneEmitter(v.layout.session(), v.doc.sheet.keyframes);
            freshEmitter.opsSource(new LayerOps(W, H, v.doc.sheet.keyframes));
            DisplayScene fresh = freshEmitter.emit(v.layout.root(), v.boxRoot, W, H);
            List<String> want = new ArrayList<>(), got = new ArrayList<>();
            walk(fresh.root, 0, want::add);
            walk(scene.root, 0, got::add);
            for (int i = 0; i < Math.min(want.size(), got.size()); i++) {
                assertEquals(want.get(i), got.get(i), what + ": 層 #" + i + " の値が作り直したシーンと違う");
            }
            assertEquals(want.size(), got.size(), what + ": 層の数");
            List<DisplayItem> wi = new SceneDisplayListAdapter(W, H, v.doc.sheet.keyframes).emit(fresh, dyn);
            List<DisplayItem> gi = adapter.emit(scene, dyn);
            for (int i = 0; i < Math.min(wi.size(), gi.size()); i++) {
                assertEquals(wi.get(i), gi.get(i), what + ": 項目 #" + i);
            }
            assertEquals(wi.size(), gi.size(), what + ": 項目の数");
        }

        String legendKey() {
            SceneLayer fs = find(scene.root, "fs");
            assertNotNull(fs);
            return fs.children.legend != null ? fs.children.legend.node.key() : "";
        }
    }

    /** 層の値のうち親の文脈で決まりうるもの（重なりの種類・印・伝える描画層・効果・legend）と位置・寸法。 */
    private static void walk(SceneLayer l, int depth, Consumer<String> out) {
        out.accept("  ".repeat(depth) + (l.node != null ? l.node.key() : "?") + " kind=" + l.stackingKind + " z=" + l.zIndex
                + " flags=" + Integer.toHexString(l.flags) + " layers=" + Integer.toHexString(l.propagatedLayers)
                + " fx=" + (l.effects != null) + " clip=" + (l.clip != null) + " @" + l.x + "," + l.y + " " + l.w + "x" + l.h
                + " legend=" + (l.children.legend != null ? l.children.legend.node.key() : "-") + " ops=" + l.ops().count());
        for (SceneChunk c : l.children.chunks) for (SceneLayer k : c.layers) walk(k, depth + 1, out);
    }

    private static SceneLayer find(SceneLayer l, String id) {
        if (l.node != null && id.equals(l.node.getId())) return l;
        for (SceneChunk c : l.children.chunks) {
            for (SceneLayer k : c.layers) {
                SceneLayer hit = find(k, id);
                if (hit != null) return hit;
            }
        }
        return null;
    }
}
