package com.nishiyu.enderframelib.core.paint.scene;

import com.nishiyu.enderframelib.core.css.model.AnimationSpec;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.model.ImageFitMode;
import com.nishiyu.enderframelib.core.paint.model.RenderHints;
import com.nishiyu.enderframelib.core.paint.model.RenderMeta;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 層の描画の中身を詰めた列（M3b-4c-1b。{@link DrawOps}・{@link DrawOpsBuilder}）。
 *
 * <ul>
 *   <li>全ての欄が既定値でも既定でない値でも、詰めてカーソルで読むと同じ値に戻る（無い欄は既定値）。</li>
 *   <li>描画 ID は持たず、持ち主の節の ID と部品の綴り（接尾辞・通し番号）から同じ文字列に組み立て直す。</li>
 *   <li>断片と同じ文字の命令は断片の番号だけを持ち、違う命令（影のずれ・-0）は自分の値を持つ。</li>
 *   <li>錨に後から載せるメタは語を差し込み、後ろの命令を壊さない。</li>
 *   <li>同じ列かは語の列と参照の表の値で答える。</li>
 *   <li>メタは列の末尾の表に語で持ち（M3b-4c-1c）、読むと値の等しいメタに戻る。持ち主の ID・鍵・書体だけの付帯情報はオブジェクトを持たない。</li>
 *   <li>層の持ち主と同じ持ち主の ID は語を持たず、持ち主の節の番号の鍵は部品の番号の 1 語で持つ。違う持ち主・違う節・文字列の鍵はそのまま戻る。</li>
 * </ul>
 */
class DrawOpsTest {
    private static final RenderMeta META = new RenderMeta(null, null, null, 13f, 0f, 0f, 2f, 3f, 1, false, null);
    /** 付帯情報も参照も持たないメタ 1 つの表: 個数 1・位置 1・固定の 7 語。 */
    private static final int META_TABLE = 1 + 1 + 7;

    private static void add(DrawOpsBuilder b, ElementType type, String base, String id, float x, String text, int color,
                            RenderMeta meta, boolean line, float baseline, float lineH, int run) {
        b.add(type, 0, ImageFitMode.FILL, base, id, 0L, x, 2f, 30f, 12f, text, color, 0, Map.of(), 0, 1f, 0f, 0f, 1f, 1f, 0f, 0,
                AnimationSpec.NONE, meta, line, baseline, lineH, run);
    }

    @Test
    void everyFieldRoundTripsAndMissingFieldsReadAsDefaults() {
        DrawOpsBuilder b = new DrawOpsBuilder();
        Map<String, String> events = Map.of("onclick", "go()");
        AnimationSpec anim = new AnimationSpec(1, 0, 1.0f, AnimationSpec.Direction.NORMAL, AnimationSpec.FillMode.NONE, "ease", 0, List.of());
        b.add(ElementType.IMAGE, DrawOps.ORIGIN_SELF | DrawOps.BASE_ORIGIN_PARENT, ImageFitMode.COVER, "n1", "n1_img", 0x1234_0000_5678L,
                1.5f, -2.25f, 30f, 40f, "a.png", 0xFF112233, 0x80FFFFFF, events, 4, 0.5f, 3f, -4f, 1.5f, -1f, 45f, 250, anim, META,
                false, 0f, 0f, -1);
        b.add(ElementType.RECT, 0, ImageFitMode.FILL, null, null, 0L, 0f, 0f, 0f, 0f, "", 0, 0, Map.of(), 0, 1f, 0f, 0f, 1f, 1f, 0f, 0,
                AnimationSpec.NONE, null, false, 0f, 0f, -1);
        DrawOps ops = b.freeze();
        assertEquals(2, ops.count());

        DrawOps.Cursor c = new DrawOps.Cursor().reset(ops);
        assertTrue(c.next());
        assertEquals(ElementType.IMAGE, c.type());
        assertEquals(DrawOps.ORIGIN_SELF, c.origin());
        assertEquals(DrawOps.ORIGIN_SELF | DrawOps.BASE_ORIGIN_PARENT, c.flags());
        assertEquals(ImageFitMode.COVER, c.fitMode());
        assertEquals("n1", c.baseNodeId());
        assertEquals("n1_img", c.id());
        assertEquals(0x1234_0000_5678L, c.key());
        assertEquals(1.5f, c.x());
        assertEquals(-2.25f, c.y());
        assertEquals(30f, c.w());
        assertEquals(40f, c.h());
        assertEquals("a.png", c.text());
        assertEquals(0xFF112233, c.color());
        assertEquals(0x80FFFFFF, c.bgColor());
        assertSame(events, c.events());
        assertEquals(4, c.radius());
        assertEquals(0.5f, c.opacity());
        assertEquals(3f, c.tx());
        assertEquals(-4f, c.ty());
        assertEquals(1.5f, c.sx());
        assertEquals(-1f, c.sy());
        assertEquals(45f, c.rot());
        assertEquals(250, c.transitionDuration());
        assertSame(anim, c.animDef());
        assertEquals(META, c.meta());
        assertFalse(c.hasLine());

        assertTrue(c.next());
        assertEquals(ElementType.RECT, c.type());
        assertNull(c.id());
        assertNull(c.baseNodeId());
        assertEquals(0L, c.key());
        assertEquals("", c.text());
        assertEquals(Map.of(), c.events());
        assertEquals(1f, c.opacity());
        assertEquals(1f, c.sx());
        assertEquals(1f, c.sy());
        assertSame(AnimationSpec.NONE, c.animDef());
        assertNull(c.meta());
        assertFalse(c.next());
        assertEquals(2 + 4, ops.words() - wordsOfFirst(ops), "既定値だけの命令は頭の 2 語と座標の 4 語だけ");
    }

    private static int wordsOfFirst(DrawOps ops) {
        DrawOpsBuilder b = new DrawOpsBuilder();
        DrawOps.Cursor c = new DrawOps.Cursor().reset(ops);
        c.next();
        b.add(c.type(), c.flags(), c.fitMode(), c.baseNodeId(), c.id(), c.key(), c.x(), c.y(), c.w(), c.h(), c.text(), c.color(), c.bgColor(),
                c.events(), c.radius(), c.opacity(), c.tx(), c.ty(), c.sx(), c.sy(), c.rot(), c.transitionDuration(), c.animDef(), c.meta(),
                false, 0f, 0f, -1);
        return b.freeze().words();
    }

    @Test
    void idsAreRebuiltFromTheOwnerAndThePartSpelling() {
        String base = "item-7";
        List<String> ids = java.util.Arrays.asList(base, base + "_txt_0", base + "_txt_12", base + "_bgl3_0", base + "_x007", base + "_cb",
                base + "12", base + "_n12345678901", "bg_root", null);
        DrawOpsBuilder b = new DrawOpsBuilder();
        for (String id : ids) add(b, ElementType.RECT, base, id, 0f, "", 0, null, false, 0f, 0f, -1);
        DrawOps ops = b.freeze();
        DrawOps.Cursor c = new DrawOps.Cursor().reset(ops);
        for (String id : ids) {
            assertTrue(c.next());
            assertEquals(id, c.id(), "ID " + id + " を組み立て直す");
            assertEquals(base, c.baseNodeId());
        }
        for (int i = 0; i < ops.refCount(); i++) {
            Object r = ops.ref(i);
            assertFalse(r instanceof String s && s.startsWith(base + "_"), "接尾辞のある ID の文字列そのものは持たない: " + r);
        }

        DrawOpsBuilder many = new DrawOpsBuilder();
        for (int i = 0; i < 50; i++) add(many, ElementType.TEXT, base, base + "_txt_" + i, i, "w" + (i % 3), 0, null, false, 0f, 0f, -1);
        assertEquals(1 + 1 + 3, many.freeze().refCount(), "持ち主・接尾辞「_txt_」・語 3 つだけ（同じ値の文字列は 1 欄を共有する）");
    }

    @Test
    void textOpsPointAtTheirRunWhenPositionTextAndLineMatch() {
        String word = "hello";
        SceneRun[] runs = {
                new SceneRun(5f, 6f, 30f, 12f, word, 9.5f, true, true),
                new SceneRun(-0.0f, 6f, 10f, 12f, "x", 9.5f, true, true),
                new SceneRun(40f, 6f, 20f, 12f, null, 0f, true, false)
        };
        DrawOps runOnly = DrawOps.ofRuns(runs);
        assertEquals(3, runOnly.runCount());
        assertTrue(runOnly.isEmpty(), "断片だけで命令は無い");

        DrawOpsBuilder same = new DrawOpsBuilder();
        same.beginRuns(runOnly);
        same.add(ElementType.TEXT, 0, ImageFitMode.FILL, "t", "t_txt_0", 0L, 5f, 6f, 30f, 12f, word, 1, 0, Map.of(), 0, 1f, 0f, 0f, 1f, 1f,
                0f, 0, AnimationSpec.NONE, META, true, 9.5f, 12f, 0);
        DrawOpsBuilder own = new DrawOpsBuilder();
        own.beginRuns(runOnly);
        // 影（ずれた位置）・-0 と +0（ビットが違う）・行の高さ・ベースラインの違いは断片を指さない
        own.add(ElementType.TEXT, 0, ImageFitMode.FILL, "t", "t_txt_0", 0L, 6f, 7f, 30f, 12f, word, 1, 0, Map.of(), 0, 1f, 0f, 0f, 1f, 1f,
                0f, 0, AnimationSpec.NONE, META, true, 9.5f, 12f, 0);
        own.add(ElementType.TEXT, 0, ImageFitMode.FILL, "t", "t_txt_1", 0L, 0.0f, 6f, 10f, 12f, "x", 1, 0, Map.of(), 0, 1f, 0f, 0f, 1f, 1f,
                0f, 0, AnimationSpec.NONE, META, true, 9.5f, 12f, 1);
        own.add(ElementType.TEXT, 0, ImageFitMode.FILL, "t", "t_txt_2", 0L, 5f, 6f, 30f, 12f, word, 1, 0, Map.of(), 0, 1f, 0f, 0f, 1f, 1f,
                0f, 0, AnimationSpec.NONE, META, true, 9.5f, 14f, 0);
        own.add(ElementType.TEXT, 0, ImageFitMode.FILL, "t", "t_txt_3", 0L, 5f, 6f, 30f, 12f, word, 1, 0, Map.of(), 0, 1f, 0f, 0f, 1f, 1f,
                0f, 0, AnimationSpec.NONE, META, true, 8f, 12f, 0);
        DrawOps a = same.freeze(), b = own.freeze();
        assertEquals(3 * DrawOps.RUN_WORDS + 2 + 1 + 1 + 1 + 2 + META_TABLE, a.words(),
                "断片と同じ文字の命令は、頭 2・断片の番号 1・色 1・メタ 1・接尾辞と番号 2 語（持ち主は層の持ち主。とメタの表）");

        DrawOps.Cursor c = new DrawOps.Cursor().reset(a);
        assertTrue(c.next());
        assertEquals(5f, c.x());
        assertEquals(6f, c.y());
        assertEquals(30f, c.w());
        assertEquals(12f, c.h());
        assertEquals(word, c.text());
        assertTrue(c.hasLine());
        assertEquals(9.5f, c.baseline());
        assertEquals(12f, c.lineHeight());
        assertEquals(9.5f, c.lineMeta(c.meta()).baseline());
        assertEquals(12f, c.lineMeta(c.meta()).lineHeight());
        assertEquals(13f, c.lineMeta(c.meta()).fontHeight(), "行の値のほかは共有のメタのまま");
        assertFalse(c.next());

        c.reset(b);
        float[][] expect = {{6f, 7f, 9.5f, 12f}, {0.0f, 6f, 9.5f, 12f}, {5f, 6f, 9.5f, 14f}, {5f, 6f, 8f, 12f}};
        for (float[] e : expect) {
            assertTrue(c.next());
            assertEquals(Float.floatToRawIntBits(e[0]), Float.floatToRawIntBits(c.x()));
            assertEquals(e[1], c.y());
            assertEquals(e[2], c.baseline());
            assertEquals(e[3], c.lineHeight());
        }
        assertEquals(3 * DrawOps.RUN_WORDS + 4 * (2 + 4 + 1 + 1 + 1 + 2 + 2) + META_TABLE, b.words(),
                "断片を指さない命令は座標・中身・行の値を持つ（4 つの命令のメタは 1 つの表の欄を共有する）");

        assertEquals(List.of(word, "x"), b.runList().stream().filter(SceneRun::isText).map(SceneRun::text).toList(), "断片は写した列にも残る");
        assertFalse(b.runIsText(2));
        assertTrue(b.runStartEdge(2));
        assertFalse(b.runEndEdge(2));
        assertEquals(Float.floatToRawIntBits(-0.0f), Float.floatToRawIntBits(b.runX(1)));
    }

    @Test
    void metaAddedLaterIsInsertedWithoutDisturbingLaterOps() {
        DrawOpsBuilder b = new DrawOpsBuilder();
        b.beginRuns(DrawOps.ofRuns(new SceneRun[]{new SceneRun(1f, 2f, 3f, 4f, "w", 3f, true, true)}));
        add(b, ElementType.NODE, "n", "n_tsh0", 1f, "", 0, null, false, 0f, 0f, -1);
        b.add(ElementType.TEXT, 0, ImageFitMode.FILL, "n", "n_txt_1", 0L, 1f, 2f, 3f, 4f, "w", 5, 0, Map.of(), 0, 1f, 0f, 0f, 1f, 1f,
                0f, 0, AnimationSpec.NONE, META, true, 3f, 4f, 0);
        add(b, ElementType.RECT, "n", "n_bg", 7f, "", 9, META, false, 0f, 0f, -1);
        assertNull(b.meta(0));
        RenderMeta withLayer = new RenderMeta(null, null, null, 5f, 0f, 0f, 1, false);
        b.setMeta(0, withLayer);
        b.setMeta(1, RenderMeta.EMPTY); // 断片を指す命令のメタは置き換える（語は増えない）

        DrawOps.Cursor c = new DrawOps.Cursor().reset(b);
        assertTrue(c.next());
        assertSame(withLayer, c.meta());
        assertEquals("n_tsh0", c.id());
        assertTrue(c.next());
        assertSame(RenderMeta.EMPTY, c.meta());
        assertEquals("w", c.text());
        assertEquals(3f, c.baseline());
        assertEquals("n_txt_1", c.id());
        assertTrue(c.next());
        assertEquals(7f, c.x());
        assertEquals(9, c.color());
        assertSame(META, c.meta());
        assertEquals("n_bg", c.id());
        assertFalse(c.next());

        b.addFlags(2, DrawOps.ANCHOR);
        c.reset(b);
        c.next();
        c.next();
        c.next();
        assertEquals(DrawOps.ANCHOR, c.flags() & DrawOps.ANCHOR);
    }

    @Test
    void metasAreStoredAsWordsAndReadBackAsEqualValues() {
        String base = "card-3";
        long ownKey = com.nishiyu.enderframelib.core.paint.model.DisplayItem.keyOf(base);
        com.nishiyu.enderframelib.core.paint.model.ClipRect clip = new com.nishiyu.enderframelib.core.paint.model.ClipRect(1f, 2f, 30f, 40f, 0f, 0f, 0f, 0f);
        com.nishiyu.enderframelib.core.paint.model.Corners radii = new com.nishiyu.enderframelib.core.paint.model.Corners(4, 4, 4, 4);
        RenderHints derived = new RenderHints(base, 0L, null, false, 0f, 0f, 0f, null, null, false, 0, null, null, null, 0f, false, "Serif");
        RenderHints explicit = new RenderHints(base, 0x77L, null, false, 0f, 0f, 0f, null, null, false, 0, null, null, null, 0f, true, null);
        RenderHints titled = new RenderHints(base, 0L, "tip", false, 0f, 0f, 0f, null, null, false, 0, null, null, null, 0f, false, null);
        RenderHints negZero = new RenderHints(base, 0L, null, false, -0.0f, 0f, 0f, null, null, false, 0, null, null, null, 0f, false, null);
        RenderMeta[] metas = {
                new RenderMeta(clip, radii, null, 14f, 11f, 18f, 0.25f, 0.75f, 3, true, null, derived),
                new RenderMeta(null, null, null, 12f, 0f, 0f, 0.5f, 0.5f, 1, false, null, explicit),
                new RenderMeta(null, null, null, 12f, 0f, 0f, 0.5f, 0.5f, 1, false, null, titled),
                new RenderMeta(null, null, null, -0.0f, 0f, 0f, 0.5f, 0.5f, 1, false, null, negZero),
                RenderMeta.EMPTY
        };
        assertEquals(ownKey, derived.parentKey(), "鍵 0 で作った付帯情報は ID から鍵を求める");
        DrawOpsBuilder b = new DrawOpsBuilder();
        for (RenderMeta m : metas) {
            add(b, ElementType.RECT, base, base + "_bg", 0f, "", 1, m, false, 0f, 0f, -1);
            add(b, ElementType.TEXT, base, base + "_txt_0", 0f, "w", 1, m, false, 0f, 0f, -1);
        }
        DrawOps ops = b.freeze();
        assertEquals(metas.length, ops.metaCount(), "同じメタを使う命令は表の 1 つの欄を共有する");
        DrawOps.Cursor c = new DrawOps.Cursor().reset(ops);
        for (RenderMeta m : metas) {
            assertTrue(c.next());
            RenderMeta first = c.meta();
            assertEquals(m, first, "メタは値の等しいものに戻る");
            assertEquals(Float.floatToRawIntBits(m.fontHeight()), Float.floatToRawIntBits(first.fontHeight()));
            assertEquals(m.hints().parentKey(), first.hints().parentKey());
            assertTrue(c.next());
            assertSame(first, c.meta(), "同じ列を読む間は 1 つのメタを 1 度だけ作る");
        }
        int hintObjects = 0;
        for (int i = 0; i < ops.refCount(); i++) {
            Object r = ops.ref(i);
            assertFalse(r instanceof RenderMeta, "メタのオブジェクトは持たない");
            if (r instanceof RenderHints) hintObjects++;
        }
        assertEquals(2, hintObjects, "持ち主の ID・鍵・書体のほかを持つ付帯情報（title・-0 の幅）だけはオブジェクトのまま");
        assertTrue(ops.sameAs(b.freeze()), "同じ中身を詰め直した列は同じ");
    }

    @Test
    void ownerAndNodeKeysAreStoredOnceForTheLayer() {
        long nodeKey = com.nishiyu.enderframelib.core.paint.model.DisplayItem.keyOf(77, 0x7001);
        long otherNode = com.nishiyu.enderframelib.core.paint.model.DisplayItem.keyOf(78, 0x7001);
        long stringKey = com.nishiyu.enderframelib.core.paint.model.DisplayItem.STRING_KEY_BIT | 12345L;
        long[] keys = {0L, nodeKey, com.nishiyu.enderframelib.core.paint.model.DisplayItem.keyOf(77, 0xC003), otherNode, stringKey, -1L,
                com.nishiyu.enderframelib.core.paint.model.DisplayItem.keyOf(77, 0)};
        String[] bases = {null, "own", "own", "child", "own", new String("own"), "child"};
        DrawOpsBuilder b = new DrawOpsBuilder();
        for (int i = 0; i < keys.length; i++) {
            b.add(ElementType.RECT, 0, ImageFitMode.FILL, bases[i], bases[i] == null ? null : bases[i] + "_p" + i, keys[i],
                    i, 0f, 1f, 1f, "", 0, 0, Map.of(), 0, 1f, 0f, 0f, 1f, 1f, 0f, 0, AnimationSpec.NONE, null, false, 0f, 0f, -1);
        }
        DrawOps ops = b.freeze();
        DrawOps.Cursor c = new DrawOps.Cursor().reset(ops);
        for (int i = 0; i < keys.length; i++) {
            assertTrue(c.next());
            assertEquals(keys[i], c.key(), "鍵 #" + i);
            assertEquals(bases[i], c.baseNodeId(), "持ち主 #" + i);
            assertEquals(bases[i] == null ? null : bases[i] + "_p" + i, c.id(), "ID #" + i);
        }
        DrawOpsBuilder one = new DrawOpsBuilder();
        one.add(ElementType.RECT, 0, ImageFitMode.FILL, "own", "own_bg", nodeKey, 0f, 0f, 1f, 1f, "", 0, 0, Map.of(), 0, 1f, 0f, 0f, 1f, 1f,
                0f, 0, AnimationSpec.NONE, null, false, 0f, 0f, -1);
        assertEquals(2 + 4 + 1 + 1, one.freeze().words(), "層の持ち主の命令は 頭 2・座標 4・部品の番号 1・接尾辞 1 語（持ち主の語・鍵の上位を持たない）");
    }

    @Test
    void sameAsComparesTheWordsAndTheReferenceValues() {
        DrawOps a = one(new HashMap<>(Map.of("k", "v")), 1, "word");
        assertTrue(a.sameAs(one(new HashMap<>(Map.of("k", "v")), 1, "word")), "値の等しい別の参照は同じ列");
        assertTrue(a.sameAs(one(Map.of("k", "v"), 1, new String("word"))));
        assertFalse(a.sameAs(one(Map.of("k", "w"), 1, "word")), "参照の表の値が違う");
        assertFalse(a.sameAs(one(Map.of("k", "v"), 2, "word")), "語が違う");
        assertFalse(a.sameAs(one(Map.of("k", "v"), 1, "other")), "文字の中身が違う");
        assertFalse(DrawOps.ofRuns(new SceneRun[]{new SceneRun(0f, 0f, 1f, 1f, "a", 0f, true, true)})
                .sameAs(DrawOps.ofRuns(new SceneRun[]{new SceneRun(0f, 0f, 1f, 1f, "b", 0f, true, true)})), "断片の中身が違う");
        assertTrue(DrawOps.EMPTY.sameAs(new DrawOpsBuilder().freeze()));
    }

    private static DrawOps one(Map<String, String> events, int color, String text) {
        DrawOpsBuilder b = new DrawOpsBuilder();
        b.add(ElementType.TEXT, 0, ImageFitMode.FILL, "n", "n_txt_0", 0L, 0f, 0f, 5f, 5f, text, color, 0, events, 0, 1f, 0f, 0f, 1f, 1f,
                0f, 0, AnimationSpec.NONE, null, false, 0f, 0f, -1);
        return b.freeze();
    }

    @Test
    void builderIsReusableAcrossLayers() {
        DrawOpsBuilder b = new DrawOpsBuilder();
        for (int round = 0; round < 3; round++) {
            b.clear();
            for (int i = 0; i < 300; i++) add(b, ElementType.TEXT, "p" + round, "p" + round + "_txt_" + i, i, "w" + i, i, META, false, 0f, 0f, -1);
            DrawOps ops = b.freeze();
            assertEquals(300, ops.count());
            DrawOps.Cursor c = new DrawOps.Cursor().reset(ops);
            for (int i = 0; i < 300; i++) {
                assertTrue(c.next());
                assertEquals("p" + round + "_txt_" + i, c.id());
                assertEquals("w" + i, c.text());
                assertEquals(i, c.color());
                assertEquals((float) i, c.x());
            }
            assertEquals(1 + 1 + 300, ops.refCount(), "持ち主・接尾辞・語 300（メタは語の表。前の層の参照は残らない）");
            assertEquals(1, ops.metaCount());
        }
    }
}
