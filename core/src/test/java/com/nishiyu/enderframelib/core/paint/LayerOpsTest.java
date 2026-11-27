package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.common.Document;
import com.nishiyu.enderframelib.common.UiDocumentCompiler;
import com.nishiyu.enderframelib.core.box.StyleSource;
import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.layout.fragment.FragmentPipeline;
import com.nishiyu.enderframelib.core.layout.fragment.LayoutFragment;
import com.nishiyu.enderframelib.core.layout.fragment.LayoutSession;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.scene.DisplayScene;
import com.nishiyu.enderframelib.core.paint.scene.DrawOps;
import com.nishiyu.enderframelib.core.paint.scene.DynamicSceneState;
import com.nishiyu.enderframelib.core.paint.scene.SceneEmitter;
import com.nishiyu.enderframelib.core.paint.scene.SceneLayer;
import com.nishiyu.enderframelib.core.paint.scene.SceneQuery;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 層ごとに記憶する描画命令（M3b-4a。{@link LayerOps}・{@link OpComposer}）。
 *
 * <ul>
 *   <li>命令は層の位置に依らない（位置だけ違う同じ箱から同じ命令ができる）。</li>
 *   <li>記憶した命令を組み立てた表示項目は、毎フレーム生成器を走らせた表示項目と、メタ・クリップの連鎖まで一致する。</li>
 *   <li>祖先の文字色のアニメーションは命令に入れず、組み立てで祖先から解く。</li>
 * </ul>
 * 作り直さなかった層が命令をそのまま持つことは {@code SceneEmitterIncrementalTest#hoverKeepsTheOpsOfUntouchedLayers}。
 */
class LayerOpsTest {
    private static final int W = 400, H = 300;

    private record Built(Document doc, DisplayScene scene) { }

    private static Built build(String html, boolean withOps) {
        Document doc = new UiDocumentCompiler().parseDocument("<style>body{margin:0}</style>" + html, "", W, H);
        LayoutSession session = new LayoutSession(FontResolver.FALLBACK);
        FragmentPipeline p = new FragmentPipeline(new StyleSource(doc.sheet, new InteractionState(), Map.of(), W, H), session);
        LayoutFragment f = p.frame(doc.root, null, W, H);
        SceneEmitter emitter = new SceneEmitter(session, doc.sheet.keyframes);
        if (withOps) emitter.opsSource(new LayerOps(W, H, doc.sheet.keyframes));
        return new Built(doc, emitter.emit(f, p.boxRoot(), W, H));
    }

    private static List<DisplayItem> items(Built b) {
        return new SceneDisplayListAdapter(W, H, b.doc.sheet.keyframes).emit(b.scene, DynamicSceneState.of(new ScrollState(), W, H));
    }

    private static SceneLayer layer(Built b, String id) {
        var loc = SceneQuery.locateById(b.scene, DynamicSceneState.of(new ScrollState(), W, H), id);
        assertNotNull(loc, id);
        return loc.layer();
    }

    /** 命令の中身から、ID・鍵（箱ごとに違う）を除いた要約。 */
    private static List<String> shape(DrawOps ops) {
        List<String> out = new ArrayList<>();
        DrawOps.Cursor it = new DrawOps.Cursor().reset(ops);
        while (it.next()) {
            StringBuilder sb = new StringBuilder().append(it.type()).append(' ')
                    .append(it.x()).append(',').append(it.y()).append(',').append(it.w()).append(',').append(it.h())
                    .append(" c=").append(it.color()).append(" bg=").append(it.bgColor()).append(" r=").append(it.radius())
                    .append(" f=").append(it.flags());
            if (it.meta() != null && it.meta().clipRect() != null) {
                for (var part : it.meta().clipRect().chain()) sb.append(" clip").append(java.util.Arrays.toString(part.rect()));
            }
            if (it.meta() != null && it.meta().hints().shadowShape() != null) sb.append(" shadow=").append(it.meta().hints().shadowShape());
            out.add(sb.toString());
        }
        return out;
    }

    @Test
    void opsDoNotDependOnTheLayerPosition() {
        String box = "position:absolute;width:40px;height:20px;background:#f00;border:2px solid #00f;border-radius:4px;"
                + "box-shadow:2px 3px 4px #000;outline:1px dashed #0f0;overflow:hidden";
        Built b = build("<div id='a' style='" + box + ";left:10.25px;top:5.5px'><i style='background:#0a0'>x</i></div>"
                + "<div id='b' style='" + box + ";left:123.75px;top:77.3px'><i style='background:#0a0'>x</i></div>", true);
        DrawOps a = layer(b, "a").ops(), c = layer(b, "b").ops();
        assertTrue(a.count() > 3, "背景・枠線・影・輪郭の命令がある");
        assertEquals(shape(a), shape(c), "位置だけ違う同じ箱は同じ命令になる（命令は層の位置に依らない）");
    }

    @Test
    void composedLayerOpsMatchPerFrameEmission() {
        String html = "<div style='padding:6.5px;border:1px solid #333;border-radius:6px;overflow:hidden;width:300.5px'>"
                + "<p style='margin:3.25px'>Hello <span style='background:#fe0;border:1px solid #c00;border-radius:3px'>inline"
                + " box that wraps across lines</span> and <b style='text-shadow:1px 1px 2px #000'>shadowed</b> text.</p>"
                + "<div style='margin-left:7.75px;width:120px;height:30px;background:#09f;box-shadow:inset 2px 2px 3px #000,4px 4px 0 #f00;"
                + "border-radius:8px 4px'></div>"
                + "<fieldset style='width:150px'><legend>Legend</legend><input value='abc'> <input type='checkbox' checked></fieldset>"
                + "<div style='position:relative;left:2.5px;top:1.5px;outline:2px solid #0f0;outline-offset:2px'>rel</div>"
                + "</div>";
        List<DisplayItem> memo = items(build(html, true));
        List<DisplayItem> perFrame = items(build(html, false));
        assertEquals(perFrame.size(), memo.size());
        for (int i = 0; i < memo.size(); i++) {
            assertEquals(perFrame.get(i), memo.get(i), "項目 " + i + " (" + memo.get(i).id() + ") がメタ・クリップの連鎖まで一致する");
        }
    }

    @Test
    void textColorAnimationIsResolvedFromAncestorsWhenComposing() {
        String html = "<style>@keyframes flash{0%{color:#f00}100%{color:#00f}}</style>"
                + "<span id='s' style='animation:flash 1s infinite'>blink</span>";
        Built memoScene = build(html, true);
        boolean flagged = false;
        for (SceneLayer l : allLayers(memoScene.scene.root, new ArrayList<>())) {
            DrawOps.Cursor o = new DrawOps.Cursor().reset(l.ops());
            while (o.next()) {
                if ((o.flags() & DrawOps.INHERIT_TEXT_COLOR_ANIM) != 0) flagged = true;
            }
        }
        assertTrue(flagged, "文字の命令は祖先の文字色のアニメーションを持たず、印だけを持つ");
        List<DisplayItem> memo = items(memoScene);
        List<DisplayItem> perFrame = items(build(html, false));
        DisplayItem text = memo.stream().filter(i -> "blink".equals(i.text())).findFirst().orElseThrow();
        assertFalse(text.animDef().isEmpty(), "組み立てで祖先の文字色のアニメーションが入る");
        assertEquals(perFrame, memo);
    }

    /**
     * 層の命令は詰めた列で、命令ごとのオブジェクトを持たない（M3b-4c-1b）: 文字の命令は層の断片を番号で指し、箱の文字のメタは 1 つを共有し、
     * 参照の表に描画 ID の文字列は無い。組み立てた項目は毎フレーム生成器を走らせた項目と一致する。
     */
    @Test
    void textOpsPointAtTheLayerRunsAndShareOneMetaPerBox() {
        StringBuilder words = new StringBuilder();
        for (int i = 0; i < 120; i++) words.append("w").append(i % 7).append(' ');
        String html = "<p id='p' style='width:200px;margin:0'>" + words + "</p>";
        Built b = build(html, true);
        SceneLayer text = null;
        for (SceneLayer l : allLayers(b.scene.root, new ArrayList<>())) {
            if (l.ops().runCount() > 100) text = l;
        }
        assertNotNull(text, "120 語の文字の層");
        DrawOps ops = text.ops();
        int textOps = 0, metas = 0;
        DrawOps.Cursor c = new DrawOps.Cursor().reset(ops);
        while (c.next()) {
            if (c.type() == com.nishiyu.enderframelib.core.paint.model.ElementType.TEXT) textOps++;
        }
        for (int i = 0; i < ops.refCount(); i++) {
            Object r = ops.ref(i);
            if (r instanceof com.nishiyu.enderframelib.core.paint.model.RenderMeta) metas++;
            assertFalse(r instanceof String s && s.contains("_txt_") && s.length() > "_txt_".length(), "描画 ID の文字列を持たない: " + r);
        }
        assertEquals(ops.runCount(), textOps, "語ごとに 1 つの文字の命令");
        assertEquals(2, metas, "メタは錨の 1 つと、文字の命令が共有する 1 つだけ（行の値は命令の語）");
        assertTrue(ops.refCount() < 20, "参照の表は持ち主・接尾辞・属性・メタ・7 種の語だけ: " + ops.refCount());
        int runWords = ops.runCount() * 7;
        // 文字の命令は 頭 2・断片の番号 1・鍵 2・色 1・属性 1・メタ 1・持ち主 1・接尾辞と番号 2 の 11 語（座標・中身・行の値は断片の語）
        assertTrue(ops.words() - runWords <= textOps * 11 + 24, "断片を指す文字の命令は 11 語以下: " + (ops.words() - runWords) + " / " + textOps);
        assertEquals(items(build(html, false)), items(b));
    }

    private static List<SceneLayer> allLayers(SceneLayer l, List<SceneLayer> out) {
        out.add(l);
        for (var c : l.children.chunks) for (SceneLayer k : c.layers) allLayers(k, out);
        return out;
    }
}
