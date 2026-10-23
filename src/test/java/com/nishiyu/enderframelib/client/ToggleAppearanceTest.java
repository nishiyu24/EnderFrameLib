package com.nishiyu.enderframelib.client;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ラジオボタンとチェックボックスの見た目を Chrome（Windows・ライトテーマ）に合わせる。Chrome を撮って確かめた描き方:
 * <ul>
 *   <li>ラジオボタン: 13px の白い円、1px の輪（未選択 #767676・選択 #0075ff）、選択時は輪と同じ色の点（直径の 60%、中央）</li>
 *   <li>チェックボックス: 未選択は白い角丸（2px）の四角と #767676 の枠、選択・未確定は箱全体を #0075ff で塗り（枠なし）、
 *       白いチェックの印・横棒を重ねる</li>
 * </ul>
 *
 * 何が壊れていたか: 以前はどちらも選択の印が文字色（黒）の四角で、輪・箱の色も選択で変わらなかった。
 */
public class ToggleAppearanceTest {

    private static List<DisplayItem> render(String body) {
        UiDocumentCompiler compiler = new UiDocumentCompiler();
        Document doc = compiler.parseDocument("<html><head><style>body { margin: 0; }</style></head><body>" + body + "</body></html>",
                "", 200, 100);
        LayoutBox root = compiler.layoutDocument(doc, 200, 100, Map.of(), new ScrollState(), FontResolver.FALLBACK);
        return new DisplayListBuilder(200, 100, doc.sheet.keyframes, new ScrollState()).render(root);
    }

    private static DisplayItem part(List<DisplayItem> items, String id) {
        return items.stream().filter(i -> i.id().equals(id)).findFirst().orElseThrow(() -> new AssertionError(id + " が無い"));
    }

    @Test
    void uncheckedIsAWhiteCircleWithAGreyRing() {
        List<DisplayItem> items = render("<input id=\"r\" type=\"radio\">");
        DisplayItem mark = part(items, "r_input");
        assertEquals(ElementType.RADIO, mark.type());
        assertEquals("false", mark.text());
        assertEquals(13, mark.width(), "印は箱いっぱい（点の大きさは箱の直径から決まる）");
        assertEquals(13, mark.height());
        assertEquals(0xFFFFFFFF, mark.bgColor(), "白い円");
        DisplayItem ring = part(items, "r_b_round");
        assertEquals(0xFF767676, ring.meta().roundedBorder().colorTop());
        assertEquals(1f, ring.meta().roundedBorder().widthTop());
    }

    @Test
    void checkedUsesTheAccentColourForRingAndDot() {
        List<DisplayItem> items = render("<input id=\"r\" type=\"radio\" checked>");
        DisplayItem mark = part(items, "r_input");
        assertEquals("true", mark.text());
        assertEquals(0xFF0075FF, mark.color(), "点は Chrome の強調色");
        assertEquals(0xFF0075FF, part(items, "r_b_round").meta().roundedBorder().colorTop(), "選択時の輪も強調色");
    }

    @Test
    void accentColorOverridesTheCheckedColour() {
        List<DisplayItem> items = render("<input id=\"r\" type=\"radio\" checked style=\"accent-color: #ff0000\">"
                + "<input id=\"u\" type=\"radio\" style=\"accent-color: #ff0000\">");
        assertEquals(0xFFFF0000, part(items, "r_input").color());
        assertEquals(0xFFFF0000, part(items, "r_b_round").meta().roundedBorder().colorTop());
        assertEquals(0xFF767676, part(items, "u_b_round").meta().roundedBorder().colorTop(), "未選択の輪は accent-color によらない");
    }

    @Test
    void uncheckedCheckboxIsAWhiteBoxWithAGreyBorder() {
        List<DisplayItem> items = render("<input id=\"c\" type=\"checkbox\">");
        DisplayItem box = part(items, "c_input");
        assertEquals(ElementType.CHECKBOX, box.type());
        assertEquals("false", box.text());
        assertEquals(13, box.width());
        assertEquals(0xFFFFFFFF, box.bgColor());
        assertEquals(2, box.meta().borderRadii().topLeft(), "角丸 2px");
        assertEquals(0xFF767676, part(items, "c_b_round").meta().roundedBorder().colorTop());
    }

    @Test
    void checkedCheckboxIsFilledWithTheAccentColourAndHasNoBorder() {
        List<DisplayItem> items = render("<input id=\"c\" type=\"checkbox\" checked>");
        DisplayItem box = part(items, "c_input");
        assertEquals("true", box.text());
        assertEquals(0xFF0075FF, box.bgColor(), "箱全体を強調色で塗る");
        assertEquals(0xFFFFFFFF, box.color(), "印は白");
        assertTrue(items.stream().noneMatch(i -> i.id().startsWith("c_b")), "選択中は枠を描かない");
    }

    @Test
    void indeterminateCheckboxDrawsABarAndAccentColorApplies() {
        List<DisplayItem> items = render("<input id=\"c\" type=\"checkbox\" indeterminate>"
                + "<input id=\"d\" type=\"checkbox\" checked style=\"accent-color: #ff0000\">");
        DisplayItem box = part(items, "c_input");
        assertEquals("mixed", box.text(), "未確定は横棒");
        assertEquals(0xFF0075FF, box.bgColor());
        assertEquals(0xFFFF0000, part(items, "d_input").bgColor());
    }

    /**
     * accent-color が白と見分けにくい（コントラスト比 3 未満）なら選択時だけ暗い配色（Chrome を 4 倍で撮った実測）:
     * チェックの印とラジオの輪と点の間が #3b3b3b。未確定の横棒は白のまま、未選択の部品と、見分けられる色（マゼンタ 3.1）は変わらない。
     */
    @Test
    void lightAccentColourSwitchesCheckedTogglesToTheDarkScheme() {
        List<DisplayItem> items = render("<input id=\"c\" type=\"checkbox\" checked style=\"accent-color: #ffff00\">"
                + "<input id=\"i\" type=\"checkbox\" indeterminate style=\"accent-color: #ffff00\">"
                + "<input id=\"r\" type=\"radio\" checked style=\"accent-color: #ff8000\">"
                + "<input id=\"u\" type=\"radio\" style=\"accent-color: #ff8000\">"
                + "<input id=\"m\" type=\"checkbox\" checked style=\"accent-color: #ff00ff\">");
        assertEquals(0xFFFFFF00, part(items, "c_input").bgColor());
        assertEquals(0xFF3B3B3B, part(items, "c_input").color(), "暗い印");
        assertEquals(0xFFFFFFFF, part(items, "i_input").color(), "未確定の横棒は白のまま");
        assertEquals(0xFF3B3B3B, part(items, "r_input").bgColor(), "輪と点の間が暗い");
        assertEquals(0xFFFF8000, part(items, "r_input").color());
        assertEquals(0xFFFFFFFF, part(items, "u_input").bgColor(), "未選択は白のまま");
        assertEquals(0xFFFFFFFF, part(items, "m_input").color(), "白とのコントラスト比 3.1 のマゼンタは白い印");
    }

    /** Chrome は部品の見た目に作者の枠線・背景・padding・文字色・枠の色を使わない。大きさも変わらない（実測）。 */
    @Test
    void authorBorderBackgroundAndPaddingAreIgnored() {
        List<DisplayItem> items = render("<input id=\"c\" type=\"checkbox\" style=\"border: 3px solid red; background: green; "
                + "border-radius: 0; padding: 5px; color: red; box-sizing: content-box\">"
                + "<input id=\"r\" type=\"radio\" checked style=\"border: 3px solid red; background: green; padding: 5px; border-color: blue\">");
        DisplayItem box = part(items, "c_input");
        assertEquals(13, box.width(), "作者の border / padding で大きくならない（content-box でも。Chrome は 13px）");
        assertEquals(13, box.height());
        assertEquals(0xFFFFFFFF, box.bgColor(), "作者の背景は塗らない");
        assertEquals(2, box.meta().borderRadii().topLeft(), "作者の border-radius は効かない");
        assertEquals(0xFF767676, part(items, "c_b_round").meta().roundedBorder().colorTop(), "作者の枠の色ではなく部品の色");
        assertEquals(1f, part(items, "c_b_round").meta().roundedBorder().widthTop());
        assertTrue(items.stream().noneMatch(i -> i.id().equals("c_bg")), "作者の背景の部品を出さない");

        assertEquals(0xFF0075FF, part(items, "r_input").color(), "選択時の点は部品の強調色（作者の border-color ではない）");
        assertEquals(0xFF0075FF, part(items, "r_b_round").meta().roundedBorder().colorTop());
        assertEquals(13, part(items, "r_input").width());
    }

    /** width / height は効き、部品は収まる最大の正方形を中央に描く（Chrome の 30×20 の実測で 20px の正方形が中央）。 */
    @Test
    void sizeIsHonouredAndTheControlIsCentredSquare() {
        List<DisplayItem> items = render("<input id=\"c\" type=\"checkbox\" checked style=\"width: 30px; height: 20px; margin: 0\">");
        DisplayItem box = part(items, "c_input");
        assertEquals(20, box.width());
        assertEquals(20, box.height());
        assertEquals(5, box.x(), "横は (30 - 20) / 2 だけ内側");
        assertEquals(0, box.y());
    }

    /** appearance: none（-webkit-appearance も）なら作者の CSS だけで描き、部品も印も描かない。 */
    @Test
    void appearanceNoneUsesAuthorCss() {
        List<DisplayItem> items = render("<input id=\"c\" type=\"checkbox\" checked style=\"appearance: none; border: 3px solid #ff0000; "
                + "background: #008000; width: 20px; height: 20px; margin: 0\">"
                + "<input id=\"r\" type=\"radio\" style=\"-webkit-appearance: none; border: 2px solid #ffff00; width: 16px; height: 16px\">");
        assertTrue(items.stream().noneMatch(i -> i.id().equals("c_input") || i.id().equals("r_input")), "部品を描かない");
        assertEquals(0xFF008000, part(items, "c_bg").bgColor(), "作者の背景");
        assertEquals(20, part(items, "c_bg").width());
        assertTrue(items.stream().anyMatch(i -> i.baseId().equals("c") && i.id().startsWith("c_b_") && i.bgColor() == 0xFFFF0000),
                "作者の枠線（UA は角丸を持たないので直線の枠）");
        assertTrue(items.stream().noneMatch(i -> i.id().equals("r_bg") && i.bgColor() != 0), "UA の背景は透明");
        assertTrue(items.stream().anyMatch(i -> i.baseId().equals("r") && i.id().startsWith("r_b_") && i.bgColor() == 0xFFFFFF00),
                "-webkit-appearance: none も同じ");
    }
}
