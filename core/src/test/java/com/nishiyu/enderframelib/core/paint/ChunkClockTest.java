package com.nishiyu.enderframelib.core.paint;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** B9 M6: セルへの書き込みはブロックごとの時計に印が付き、スナップショットはブロックを複製せずに変化を伝える。 */
class ChunkClockTest {

    private static DisplayItem item(int i) {
        return new DisplayItem("i" + i, "i" + i, ElementType.RECT, i, 0, 10, 10, "", 0xFF000000, 0xFFFFFFFF, Map.of(), 0, 1f,
                0f, 0f, 1f, 1f, 0f, 0, null, null, null);
    }

    private static List<DisplayItem> items(int n) {
        List<DisplayItem> l = new ArrayList<>();
        for (int i = 0; i < n; i++) l.add(item(i));
        return l;
    }

    @Test
    void aWriteMarksOnlyItsOwnChunk() {
        List<DisplayItem> src = items(100);
        ImmutableItemList list = ImmutableItemList.from(null, src);
        int before = list.clock().maxEpoch();
        assertTrue(src.get(70).repaint(0xFF112233, 0xFF000000, 1f)); // 位置 70 = ブロック 2
        int e = list.clock().chunkEpoch(2);
        assertTrue(e > before, "書いたブロックに印が付く");
        assertEquals(0, list.clock().chunkEpoch(0));
        assertEquals(0, list.clock().chunkEpoch(1));
        assertEquals(0, list.clock().chunkEpoch(3));
        src.get(5).setPaintOffset(3f, 0f); // 移動量
        assertTrue(list.clock().chunkEpoch(0) > e);
        assertEquals(list.clock().chunkEpoch(0), list.clock().maxEpoch());
        assertFalse(src.get(5).repaint(0xFF000000, 0xFFFFFFFF, 1f), "値が同じなら書き込みも印も無い");
    }

    @Test
    void paintWriteGivesANewSnapshotThatSharesTheChunks() {
        List<DisplayItem> src = items(100);
        ImmutableItemList a = ImmutableItemList.from(null, src);
        assertSame(a, ImmutableItemList.from(a, src), "何も変わらなければ同じリスト");
        src.get(40).repaint(0xFF010203, 0xFF000000, 1f);
        ImmutableItemList b = ImmutableItemList.from(a, src);
        assertNotSame(a, b, "塗りが書かれたら新しいスナップショット（変化を同一性で知る消費者のため）");
        for (int c = 0; c < a.chunkCount(); c++) assertSame(a.chunkAt(c), b.chunkAt(c), "ブロックは複製しない: " + c);
        assertSame(a.clock(), b.clock());
        assertNotSame(a.token(), b.token(), "塗りの書き込みでは中身の印が新しくなる");
    }

    @Test
    void offsetWriteKeepsTheContentTokenAndSharesChunks() {
        List<DisplayItem> src = items(100);
        ImmutableItemList a = ImmutableItemList.from(null, src);
        src.get(3).setPaintOffset(0f, 5f);
        ImmutableItemList b = a.touched();
        assertNotSame(a, b);
        assertSame(a.token(), b.token());
        for (int c = 0; c < a.chunkCount(); c++) assertSame(a.chunkAt(c), b.chunkAt(c));
        assertTrue(b.clock().chunkEpoch(0) > 0, "動いた項目のブロックに印が付く");
        assertEquals(5f, b.get(3).translateY());
    }

    @Test
    void replacedItemsGetTheirPositionAndLaterWritesMarkTheClock() {
        List<DisplayItem> src = items(70);
        ImmutableItemList a = ImmutableItemList.from(null, src);
        DisplayItem repl = item(999);
        src.set(35, repl);
        ImmutableItemList b = ImmutableItemList.from(a, src);
        assertNotSame(a.chunkAt(1), b.chunkAt(1), "差し替わったブロックは複製される");
        assertSame(a.chunkAt(0), b.chunkAt(0));
        int before = b.clock().chunkEpoch(1);
        repl.repaint(0xFF999999, 0xFF000000, 1f);
        assertTrue(b.clock().chunkEpoch(1) > before, "差し替えた項目も位置を覚えていて、書くと時計に印が付く");
        // 作り直した（写しの）項目は、どのスナップショットにも入っていないので時計に印を付けない
        DisplayItem frozen = repl.frozen();
        int e = b.clock().maxEpoch();
        frozen.repaint(0xFF000001, 0xFF000000, 1f);
        assertEquals(e, b.clock().maxEpoch());
    }

    @Test
    void lengthChangeStartsANewClock() {
        List<DisplayItem> src = items(50);
        ImmutableItemList a = ImmutableItemList.from(null, src);
        src.add(item(50));
        ImmutableItemList b = ImmutableItemList.from(a, src);
        assertNotSame(a.clock(), b.clock());
        int e = b.clock().maxEpoch();
        src.get(10).repaint(0xFF0A0B0C, 0xFF000000, 1f);
        assertTrue(b.clock().maxEpoch() > e, "新しい時計へ付け直されている");
    }

    @Test
    void attachIsCalledOnlyFromTheSnapshot() throws IOException {
        List<String> hits = new ArrayList<>();
        for (String root : List.of("core/src/main", "core-gl/src/main", "core-vulkan/src/main", "src/main")) {
            Path r = Path.of(root);
            if (!Files.isDirectory(r)) continue;
            try (Stream<Path> files = Files.walk(r)) {
                for (Path f : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                    String n = f.getFileName().toString();
                    if (n.equals("DisplayItem.java") || n.equals("ImmutableItemList.java")) continue;
                    List<String> lines = Files.readAllLines(f);
                    for (int i = 0; i < lines.size(); i++) if (lines.get(i).matches(".*\\.attach\\([^,()]*[Cc]lock[^,()]*,.*")) hits.add(f + ":" + (i + 1));
                }
            }
        }
        assertTrue(hits.isEmpty(), "時計への登録は ImmutableItemList だけ: " + hits);
    }
}
