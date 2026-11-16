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

/** B9 M2: 変わりやすい欄はセル経由。セルは項目ごとに 1 つで、項目の外で作ったり共有したりしない。 */
class PaintCellRatchetTest {

    /** 項目へ移動量を書く呼び出し（引数 2 つ）。{@code UiView.setPaintOffset(id, dx, dy)}（引数 3 つ）は別の API。 */
    private static final java.util.regex.Pattern ITEM_OFFSET = java.util.regex.Pattern.compile("\\.setPaintOffset\\([^,()]+,[^,()]+\\)");

    private static DisplayItem item() {
        return new DisplayItem("a", "a", ElementType.RECT, 1, 2, 30, 40, "t", 0xFF112233, 0xFF445566, Map.of(), 4, 0.5f,
                3f, 4f, 1.5f, 2f, 10f, 0, null, null, null);
    }

    @Test
    void everyItemHasItsOwnCell() {
        DisplayItem a = item();
        assertNotNull(a.cell());
        DisplayItem moved = a.withPosition(9, 9);
        DisplayItem text = a.withText("u");
        DisplayItem meta = a.withMeta(null).withPosition(1, 1);
        assertNotSame(a.cell(), moved.cell());
        assertNotSame(a.cell(), text.cell());
        assertNotSame(a.cell(), meta.cell());
        assertThrows(NullPointerException.class,
                () -> new DisplayItem("a", "a", ElementType.RECT, "t", Map.of(), 0, null, null, null, 1L, null));
    }

    @Test
    void accessorsReadTheCellAndEqualityIsByValue() {
        DisplayItem a = item(), b = item();
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotSame(a.cell(), b.cell());
        assertEquals(1, a.x());
        assertEquals(2, a.y());
        assertEquals(30, a.width());
        assertEquals(40, a.height());
        assertEquals(0xFF112233, a.color());
        assertEquals(0xFF445566, a.bgColor());
        assertEquals(4, a.radius());
        assertEquals(0.5f, a.opacity());
        assertEquals(3f, a.translateX());
        assertEquals(4f, a.translateY());
        assertEquals(1.5f, a.scaleX());
        assertEquals(2f, a.scaleY());
        assertEquals(10f, a.rotate());
        assertNotEquals(a, a.withPosition(2, 2));
        assertEquals(a, a.withPosition(1, 2));
        DisplayItem moved = item();
        moved.setPaintOffset(2f, 0f);
        assertNotEquals(a, moved, "オフセットも値の等価性に入る");
        assertEquals(a.translateX() + 2f, moved.translateX());
        assertEquals(a.baseTranslateX(), moved.baseTranslateX());
        assertEquals(a.translateX(), a.baseTranslateX());
    }

    @Test
    void cellsAreCreatedOnlyByDisplayItem() throws IOException {
        List<String> hits = new ArrayList<>();
        for (String root : List.of("core/src/main", "core-gl/src/main", "core-vulkan/src/main", "src/main")) {
            Path r = Path.of(root);
            if (!Files.isDirectory(r)) continue;
            try (Stream<Path> files = Files.walk(r)) {
                for (Path f : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                    String n = f.getFileName().toString();
                    if (n.equals("DisplayItem.java") || n.equals("PaintCell.java")) continue;
                    List<String> lines = Files.readAllLines(f);
                    for (int i = 0; i < lines.size(); i++) {
                        if (lines.get(i).contains("new PaintCell(") || lines.get(i).contains(".cell()")) hits.add(f + ":" + (i + 1));
                    }
                }
            }
        }
        assertTrue(hits.isEmpty(), "セルは DisplayItem の内側だけで作り、外から取り出さない: " + hits);
    }

    /** 塗りをセルへ書く（項目の同一性を保ったまま値を変える）のは、通し番号を保証する DisplayListBuilder の差分パッチだけ。 */
    @Test
    void paintWritesOnlyFromTheDiffPatch() throws IOException {
        List<String> hits = new ArrayList<>();
        for (String root : List.of("core/src/main", "core-gl/src/main", "core-vulkan/src/main", "src/main")) {
            Path r = Path.of(root);
            if (!Files.isDirectory(r)) continue;
            try (Stream<Path> files = Files.walk(r)) {
                for (Path f : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                    String n = f.getFileName().toString();
                    if (n.equals("DisplayItem.java") || n.equals("DisplayListBuilder.java") || n.equals("PaintSupport.java")) continue;
                    List<String> lines = Files.readAllLines(f);
                    for (int i = 0; i < lines.size(); i++) {
                        if (lines.get(i).contains(".repaintFrom(") || lines.get(i).contains(".writePaint(") || lines.get(i).contains(".repaint(") || lines.get(i).contains(".writeOffset(")) hits.add(f + ":" + (i + 1));
                    }
                }
            }
        }
        assertTrue(hits.isEmpty(), "塗りの書き込みは差分パッチの再構築（DisplayListBuilder・PaintSupport.addElement）だけ: " + hits);
    }

    /** 描画の上だけの移動量を書くのは、発行（UiView）と GPU 用のスクロール丸め（PaintSceneDriver）だけ。 */
    @Test
    void offsetWritesOnlyFromPublishAndSnap() throws IOException {
        List<String> hits = new ArrayList<>();
        for (String root : List.of("core/src/main", "core-gl/src/main", "core-vulkan/src/main", "src/main")) {
            Path r = Path.of(root);
            if (!Files.isDirectory(r)) continue;
            try (Stream<Path> files = Files.walk(r)) {
                for (Path f : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                    String n = f.getFileName().toString();
                    if (n.equals("DisplayItem.java") || n.equals("UiView.java") || n.equals("PaintSceneDriver.java")) continue;
                    List<String> lines = Files.readAllLines(f);
                    for (int i = 0; i < lines.size(); i++) {
                        if (ITEM_OFFSET.matcher(lines.get(i)).find()) hits.add(f + ":" + (i + 1));
                    }
                }
            }
        }
        assertTrue(hits.isEmpty(), "移動量の書き込みは UiView の発行と PaintSceneDriver の丸めだけ: " + hits);
    }
}
