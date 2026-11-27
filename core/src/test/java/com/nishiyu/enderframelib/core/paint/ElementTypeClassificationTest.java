package com.nishiyu.enderframelib.core.paint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nishiyu.enderframelib.common.SoftwareRendering;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.model.ElementType.RasterRoute;
import com.nishiyu.enderframelib.core.paint.software.SoftwareRenderBackend;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** S3: 表示項目の種類（{@link ElementType}）の分類が網羅され、全種類をソフトウェアが描けること。 */
class ElementTypeClassificationTest {

    @Test
    void everyTypeHasARouteAndEveryRouteIsUsed() {
        Set<RasterRoute> used = EnumSet.noneOf(RasterRoute.class);
        for (ElementType t : ElementType.values()) {
            RasterRoute r = t.rasterRoute();
            assertTrue(r != null, t.name());
            used.add(r);
        }
        assertEquals(EnumSet.allOf(RasterRoute.class), used, "使われない経路がある（分類が古い）");
    }

    @Test
    void classificationsMatchTheKnownSets() {
        Set<ElementType> external = EnumSet.of(ElementType.IMAGE, ElementType.VIDEO, ElementType.CANVAS, ElementType.SCENE);
        Set<ElementType> live = EnumSet.of(ElementType.INPUT, ElementType.TEXTAREA, ElementType.SELECT, ElementType.PROGRESS);
        for (ElementType t : ElementType.values()) {
            assertEquals(external.contains(t), t.isExternallyDriven(), "isExternallyDriven " + t);
            assertEquals(live.contains(t), t.hasLiveWidgetState(), "hasLiveWidgetState " + t);
        }
        Set<ElementType> buffer = EnumSet.of(ElementType.CANVAS, ElementType.SCENE, ElementType.TEXTAREA, ElementType.IMAGE,
                ElementType.VIDEO, ElementType.INPUT, ElementType.SELECT, ElementType.RANGE, ElementType.PROGRESS,
                ElementType.TOGGLE, ElementType.CHECKBOX, ElementType.RADIO);
        Set<ElementType> press = EnumSet.of(ElementType.SCENE, ElementType.TEXTAREA, ElementType.INPUT, ElementType.SELECT, ElementType.RANGE);
        for (ElementType t : ElementType.values()) {
            assertEquals(buffer.contains(t), t.drawsToBuffer(), "drawsToBuffer " + t);
            assertEquals(press.contains(t), t.capturesPress(), "capturesPress " + t);
        }
        assertEquals(RasterRoute.TOGGLE, ElementType.CHECKBOX.rasterRoute());
        assertEquals(RasterRoute.TEXT_ALWAYS, ElementType.INPUT.rasterRoute());
        assertEquals(RasterRoute.IMAGE, ElementType.VIDEO.rasterRoute());
        assertTrue(ElementType.BUTTON.isInput());
        assertFalse(ElementType.PROGRESS.isInput());
    }

    /** HTML から作れる全種類の部品を 1 枚に置き、ソフトウェアが例外なく描き、描いた部品が画素に出る。 */
    @Test
    void softwareBackendDrawsEveryHtmlProducibleType() {
        String body = "<div style='margin:4px'><input value='abc' style='width:80px'></div>"
                + "<div style='margin:4px'><textarea style='width:80px;height:30px'>xyz</textarea></div>"
                + "<div style='margin:4px'><select style='width:80px'><option>one</option></select></div>"
                + "<div style='margin:4px'><input type=range style='width:80px'></div>"
                + "<div style='margin:4px'><progress value=3 max=10 style='width:80px'></progress></div>"
                + "<div style='margin:4px'><input type=checkbox checked><input type=radio checked></div>"
                + "<div style='margin:4px'><button style='width:80px'>go</button> <a href='#'>link</a></div>"
                + "<div style='margin:4px'><canvas width=40 height=20 style='background:#f00'></canvas></div>"
                + "<div style='margin:4px;width:40px;height:20px;background:#00f;border-radius:6px'></div>";
        SoftwareRenderBackend b = SoftwareRendering.renderHtml("<html><body style='margin:0'>" + body + "</body></html>", 240, 360, 10_000L);
        long ink = 0;
        for (int y = 0; y < 360; y++) for (int x = 0; x < 240; x++) ink += b.pixmap().get(x, y) >>> 24;
        assertTrue(ink > 0, "何も描かれていない");
        boolean blue = false;
        for (int y = 0; y < 360 && !blue; y++) {
            for (int x = 0; x < 240; x++) {
                int p = b.pixmap().getStraight(x, y);
                if (p == 0xFF0000FF) blue = true;
            }
        }
        assertTrue(blue, "背景の箱が描かれていない");
    }

    /**
     * 種類ごとの分岐を {@code ElementType} の網羅形の分類へ寄せたので、{@code core} の他の場所が種類の定数で
     * {@code case} を書き直さない（書くと種類の追加で直し漏れが出る）。
     */
    @Test
    void noElementTypeCaseLabelsOutsideTheEnum() throws IOException {
        Path root = Path.of("core/src/main/java");
        if (!Files.isDirectory(root)) root = Path.of("src/main/java");
        List<Path> roots = new ArrayList<>(List.of(root));
        Path host = Path.of("mod/src/main/java"); // MOD（Minecraft 側）。リポジトリ直下から走るときだけ
        if (Files.isDirectory(Path.of("core/src/main/java")) && Files.isDirectory(host)) roots.add(host);
        List<String> offenders = new ArrayList<>();
        List<String> labels = List.of("CANVAS", "VIDEO", "CHECKBOX", "CIRCLE_BORDER", "ROUNDED_BORDER");
        // 経路（RasterRoute）と名前が重なるもの（TEXTAREA・PROGRESS・TOGGLE）は対象外;
        for (Path r : roots) try (Stream<Path> s = Files.walk(r)) {
            for (Path p : (Iterable<Path>) s.filter(f -> f.toString().endsWith(".java"))::iterator) {
                if (p.getFileName().toString().equals("ElementType.java")) continue;
                List<String> lines = Files.readAllLines(p);
                for (int i = 0; i < lines.size(); i++) {
                    String l = lines.get(i).trim();
                    if (!l.startsWith("case ")) continue;
                    for (String t : labels) {
                        if (l.startsWith("case " + t + " ") || l.startsWith("case " + t + ",") || l.contains(", " + t + ",") || l.contains(", " + t + " ->")) {
                            offenders.add(p + ":" + (i + 1));
                            break;
                        }
                    }
                }
            }
        }
        assertEquals(List.of(), offenders, "種類の分岐は ElementType の分類メソッドを使う");
    }
}
