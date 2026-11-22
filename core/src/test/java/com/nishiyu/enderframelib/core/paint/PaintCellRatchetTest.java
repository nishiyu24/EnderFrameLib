package com.nishiyu.enderframelib.core.paint;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.model.PaintCell;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** B9 M2 + C6: 項目とセルは完全に不変（全欄 final・事後書き込み 0）。描画上の平行移動は外部表（UiView の PaintOffsets）だけに書く。 */
class PaintCellRatchetTest {

    private static final java.util.regex.Pattern ITEM_OFFSET = java.util.regex.Pattern.compile("\\.setPaintOffset\\([^,()]+,[^,()]+\\)");
    private static final java.util.regex.Pattern OFFSETS_WRITE = java.util.regex.Pattern.compile("(?:paintOffsets|offsets)\\.(?:set|clear|clearAll)\\s*\\(");

    private static DisplayItem item() {
        return new DisplayItem("a", "a", ElementType.RECT, 1, 2, 30, 40, "t", 0xFF112233, 0xFF445566, Map.of(), 4, 0.5f,
                3f, 4f, 1.5f, 2f, 10f, 0, null, null, null);
    }

    @Test
    void displayItemAndPaintCellAreCompletelyImmutable() {
        for (Class<?> cls : List.of(DisplayItem.class, PaintCell.class)) {
            for (Field f : cls.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                assertTrue(Modifier.isFinal(f.getModifiers()), cls.getSimpleName() + "." + f.getName() + " must be final");
            }
        }
    }

    @Test
    void everyItemHasACellAndDerivedGeometryGetsNewCell() {
        DisplayItem a = item();
        assertNotNull(a.cell());
        DisplayItem moved = a.withPosition(9, 9);
        DisplayItem text = a.withText("u");
        DisplayItem meta = a.withMeta(null).withPosition(1, 1);
        assertNotSame(a.cell(), moved.cell());
        assertSame(a.cell(), text.cell(), "セルは完全不変なので文字だけの派生は同じセルを共有できる");
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

    /** 死んだセル書き込み経路（repaint / repaintFrom / writePaint / writeOffset / 2 引数 setPaintOffset）は完全に削除済み。 */
    @Test
    void noLegacyCellWriteCallsExist() throws IOException {
        List<String> hits = new ArrayList<>();
        for (String root : List.of("core/src/main", "core-gl/src/main", "core-vulkan/src/main", "src/main")) {
            Path r = Path.of(root);
            if (!Files.isDirectory(r)) continue;
            try (Stream<Path> files = Files.walk(r)) {
                for (Path f : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                    List<String> lines = Files.readAllLines(f);
                    for (int i = 0; i < lines.size(); i++) {
                        String l = lines.get(i);
                        if (l.contains(".repaintFrom(") || l.contains(".writePaint(") || l.contains(".repaint(")
                                || l.contains(".writeOffset(") || ITEM_OFFSET.matcher(l).find()) {
                            hits.add(f + ":" + (i + 1));
                        }
                    }
                }
            }
        }
        assertTrue(hits.isEmpty(), "セルへの書き込み口は C6 で全廃済み: " + hits);
    }

    /** 外部の平行移動表（{@link com.nishiyu.enderframelib.core.paint.model.PaintOffsets}）への書き込み口は UiView 1 か所だけ。 */
    @Test
    void paintOffsetsWritesLiveOnlyInUiView() throws IOException {
        List<String> hits = new ArrayList<>();
        for (String root : List.of("core/src/main", "core-gl/src/main", "core-vulkan/src/main", "src/main")) {
            Path r = Path.of(root);
            if (!Files.isDirectory(r)) continue;
            try (Stream<Path> files = Files.walk(r)) {
                for (Path f : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                    String n = f.getFileName().toString();
                    if (n.equals("PaintOffsets.java") || n.equals("UiView.java")) continue;
                    List<String> lines = Files.readAllLines(f);
                    for (int i = 0; i < lines.size(); i++) {
                        String l = lines.get(i);
                        if (l.contains("new PaintOffsets(") || OFFSETS_WRITE.matcher(l).find()) hits.add(f + ":" + (i + 1));
                    }
                }
            }
        }
        assertTrue(hits.isEmpty(), "PaintOffsets への書き込みは UiView だけ: " + hits);
    }
}
