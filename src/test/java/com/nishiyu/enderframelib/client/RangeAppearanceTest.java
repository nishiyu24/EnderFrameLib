package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.client.renderer.DisplayItemDrawer;
import com.nishiyu.enderframelib.client.renderer.DisplayListBuilder;
import com.nishiyu.enderframelib.common.Document;
import com.nishiyu.enderframelib.common.UiDocumentCompiler;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * レンジの見た目を Chrome（Windows・ライトテーマ）に合わせる。Chrome を 4 倍で撮って確かめた描き方:
 * 箱の幅いっぱい・縦の中央に高さ 8px の角丸の溝（#efefef）、値までは #0075ff、溝全体に 50% の #767676 の枠、つまみは直径 16px の #0075ff の円で、
 * 中心は両端から 8px の間を動く。作者の背景・枠線は描かない（大きさには効く）。{@code accent-color} が溝と見分けにくければ
 * 溝は暗い配色（#3b3b3b、枠は 50% の #cfcfcf）。{@code appearance: none} なら作者の CSS の箱に部品のつまみだけを重ねる。
 *
 * 何が壊れていたか: 以前は溝の太さとつまみの直径を箱の高さの 30% / 80% にしていて、溝が細く、作者の背景や枠線も描いていた。
 */
public class RangeAppearanceTest {

    private static List<DisplayItem> render(String body) {
        UiDocumentCompiler compiler = new UiDocumentCompiler();
        Document doc = compiler.parseDocument("<html><head><style>body { margin: 0; }</style></head><body>" + body + "</body></html>",
                "", 400, 200);
        LayoutBox root = compiler.layoutDocument(doc, 400, 200, Map.of(), new ScrollState(), FontResolver.FALLBACK);
        return new DisplayListBuilder(400, 200, doc.sheet.keyframes, new ScrollState()).render(root);
    }

    private static DisplayItem part(List<DisplayItem> items, String id) {
        return items.stream().filter(i -> i.id().equals(id)).findFirst().orElseThrow(() -> new AssertionError(id + " が無い"));
    }

    @Test
    void defaultSliderUsesChromeTrackAndThumbColours() {
        List<DisplayItem> items = render("<input id=\"s\" type=\"range\" value=\"50\" style=\"margin: 0\">");
        DisplayItem slider = part(items, "s_input");
        assertEquals(ElementType.RANGE, slider.type());
        assertEquals(129, slider.width(), "UA の既定の幅");
        assertEquals(16, slider.height());
        assertEquals("50:0:100", slider.text());
        assertEquals(0xFF0075FF, slider.color(), "つまみと値の側");
        assertEquals(0xFFEFEFEF, slider.bgColor(), "溝");
        assertEquals(0x80767676, slider.meta().roundedBorder().colorTop(), "溝の枠（50% の #767676。溝の上で #b2b2b2、値の上で #3b75ba）");
    }

    /** Chrome の実測: 作者の border / padding / background は描かれないが、箱は 139×26 に広がり、溝とつまみは border box を使う。 */
    @Test
    void authorBackgroundAndBorderAreNotPaintedButSizeTheBox() {
        List<DisplayItem> items = render("<input id=\"s\" type=\"range\" style=\"margin: 0; background: #ff0000; "
                + "border: 2px solid #ffff00; color: #008000; padding: 3px; border-radius: 0\">");
        DisplayItem slider = part(items, "s_input");
        assertEquals(139, slider.width(), "129 + padding 6 + border 4");
        assertEquals(26, slider.height());
        assertEquals(0xFF0075FF, slider.color(), "作者の color ではなく部品の強調色");
        assertTrue(items.stream().noneMatch(i -> i.baseId().equals("s") && i.type() != ElementType.NODE && (i.bgColor() == 0xFFFF0000 || i.bgColor() == 0xFFFFFF00)),
                "作者の背景・枠線を描かない（描かれない錨の NODE は除く）");
    }

    @Test
    void lightAccentColourSwitchesTheTrackToTheDarkScheme() {
        List<DisplayItem> items = render("<input id=\"m\" type=\"range\" style=\"accent-color: #ff00ff\">"
                + "<input id=\"n\" type=\"range\" style=\"accent-color: #004080\">");
        DisplayItem magenta = part(items, "m_input");
        assertEquals(0xFFFF00FF, magenta.color());
        assertEquals(0xFF3B3B3B, magenta.bgColor(), "#efefef とのコントラスト比が 3 未満なら暗い溝");
        assertEquals(0x80CFCFCF, magenta.meta().roundedBorder().colorTop(), "暗い溝の上で #858585");
        DisplayItem navy = part(items, "n_input");
        assertEquals(0xFF004080, navy.color());
        assertEquals(0xFFEFEFEF, navy.bgColor(), "見分けられる色なら明るい溝のまま");
    }

    @Test
    void appearanceNoneDrawsAuthorBoxWithOnlyTheThumb() {
        List<DisplayItem> items = render("<input id=\"s\" type=\"range\" style=\"appearance: none; background: #ff0000; height: 10px\">");
        assertEquals(0xFFFF0000, part(items, "s_bg").bgColor(), "作者の背景");
        DisplayItem thumb = part(items, "s_input");
        assertEquals(0, thumb.bgColor() >>> 24, "溝は描かない");
        assertNull(thumb.meta().roundedBorder());
        assertEquals(0xFF0075FF, thumb.color(), "つまみは部品のまま");
    }

    /** 押した所につまみの中心が来る（つまみの中心は両端から 8px の間を動く）。 */
    @Test
    void pointerPositionMapsToTheThumbCentre() {
        assertEquals(0f, DisplayItemDrawer.rangeRatio(8, 129), 1e-6);
        assertEquals(0f, DisplayItemDrawer.rangeRatio(2, 129), 1e-6);
        assertEquals(1f, DisplayItemDrawer.rangeRatio(121, 129), 1e-6);
        assertEquals(0.5f, DisplayItemDrawer.rangeRatio(64.5, 129), 1e-6);
    }
}
