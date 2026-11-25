package com.nishiyu.enderframelib.core.layout.fragment;

import com.nishiyu.enderframelib.core.box.BoxNode;
import com.nishiyu.enderframelib.core.box.OrthogonalBoxes;
import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * M2b-5: 縦書き（{@link OrthogonalFlow}・{@link OrthogonalBoxes}。CSS Writing Modes §7 の直交フロー）。根の流れの中身は包む箱の中で
 * 論理（横書き）の軸で組まれ（子孫のスタイルは軸を入れ替えてある）、包む箱の {@code transform} が描画で物理の向きにする。期待値は
 * 断片の座標（論理の座標。包む箱は回転の原点に置く）で、手で求めた数。コメントの「物理」は回した後の矩形。
 */
class WritingModeTest {
    private static final float EPS = 0.02f;
    private static final String BOX = "<div id=c style='width:20px;height:30px'></div>";

    private static LayoutFragment root(String body) {
        return new SpikeView("<html><body style='margin:0'>" + body + "</body></html>", 800, 600).frame();
    }

    private static float[] at(LayoutFragment r, String id) {
        float[] b = SpikeView.find(r, id);
        assertNotNull(b, id);
        return b;
    }

    private static void rect(float[] expected, float[] actual) {
        assertArrayEquals(expected, actual, EPS);
    }

    /**
     * {@code vertical-rl}: 根の幅（auto）は論理のブロック寸法（子の論理の高さ＝物理の幅 20）、高さは行の長さ 100。子は軸を入れ替えて
     * 論理 30 × 20。90° の回転は左へ張り出すので包む箱を右へ 20 ずらす（物理: 子は x 0〜20・y 0〜30）。
     */
    @Test
    void verticalRlRootSizesFromTheBlockExtentAndShiftsTheWrapperRight() {
        LayoutFragment r = root("<div id=r style='writing-mode:vertical-rl;height:100px'>" + BOX + "</div>");
        rect(new float[]{0, 0, 20, 100}, at(r, "r"));
        rect(new float[]{20, 0, 30, 20}, at(r, "c"));
    }

    /** {@code vertical-lr} は転置（ずらさない）、{@code sideways-lr} は -90° で下へ行の長さだけずらす。 */
    @Test
    void verticalLrIsTransposedAndSidewaysLrRotatesCounterClockwise() {
        LayoutFragment lr = root("<div id=r style='writing-mode:vertical-lr;height:100px'>" + BOX + "</div>");
        rect(new float[]{0, 0, 20, 100}, at(lr, "r"));
        rect(new float[]{0, 0, 30, 20}, at(lr, "c"));
        LayoutFragment ccw = root("<div id=r style='writing-mode:sideways-lr;height:100px'>" + BOX + "</div>");
        rect(new float[]{0, 100, 30, 20}, at(ccw, "c"));
    }

    /**
     * 行の長さ: 指定の高さが無ければ包含ブロックの確定した高さ（余白を除く）、それも無ければビューポートの高さ（§7.3）。根の幅が指定
     * されていれば包む箱の論理の高さはその幅で、右寄せのずらしもその幅。
     */
    @Test
    void inlineSizeComesFromTheContainerOrTheViewportAndADefiniteWidthFixesTheBlockSize() {
        assertEquals(70, at(root("<div style='height:80px'><div id=r style='writing-mode:vertical-rl;margin:5px 0'>" + BOX + "</div></div>"), "r")[3], EPS);
        assertEquals(600, at(root("<div id=r style='writing-mode:vertical-rl'>" + BOX + "</div>"), "r")[3], EPS);
        LayoutFragment w = root("<div id=r style='writing-mode:vertical-rl;height:100px;width:50px'>" + BOX + "</div>");
        rect(new float[]{0, 0, 50, 100}, at(w, "r"));
        rect(new float[]{50, 0, 30, 20}, at(w, "c"));
    }

    /** 根の直下の位置指定の子は包む箱の外で、軸を入れ替えずに物理の座標で置く（旧 {@code clearAxisFlowOutsideWrapper}）。 */
    @Test
    void absolutelyPositionedChildOfTheRootStaysPhysical() {
        LayoutFragment r = root("<div id=r style='writing-mode:vertical-rl;height:100px;position:relative'>" + BOX
                + "<div id=a style='position:absolute;top:5px;left:7px;width:20px;height:30px'><div id=g style='width:4px;height:9px'></div></div></div>");
        rect(new float[]{7, 5, 20, 30}, at(r, "a"));
        rect(new float[]{7, 5, 4, 9}, at(r, "g")); // 子孫も入れ替えない
    }

    /**
     * 縦書きの中の {@code horizontal-tb}（横書きへ戻す根）: 根の {@code width} / {@code height}（40 × 10）は包む箱が持ち、根の論理の大きさは
     * その縦横を入れ替えた 10 × 40。外の縦書きの根の幅は 40。{@code vertical-rl} の中では -90° なので包む箱を下へ 40 ずらす
     * （物理: 横書きの箱は x 0〜40・y 0〜10）。
     */
    @Test
    void horizontalReturnRootSwapsItsSizeAndCounterRotates() {
        LayoutFragment r = root("<div id=r style='writing-mode:vertical-rl;height:100px'><div id=h style='writing-mode:horizontal-tb;width:40px;height:10px'>"
                + "<div id=g style='width:15px;height:5px'></div></div></div>");
        rect(new float[]{0, 0, 40, 100}, at(r, "r"));
        rect(new float[]{40, 0, 10, 40}, at(r, "h"));
        rect(new float[]{40, 40, 15, 5}, at(r, "g"));
    }

    /** 横書きの flex の項目になった縦書きの根の内在の幅は、論理のブロック寸法（子の物理の幅 20）。 */
    @Test
    void verticalRootIntrinsicWidthIsItsBlockExtent() {
        LayoutFragment r = root("<div style='display:flex'><div id=r style='writing-mode:vertical-rl;height:100px'>" + BOX + "</div>"
                + "<div id=n style='width:10px;height:10px'></div></div>");
        assertEquals(20, at(r, "r")[2], EPS);
        assertEquals(20, at(r, "n")[0], EPS);
    }

    /**
     * 縦書きの根の直下の子が位置指定になると、その子孫は軸を入れ替えない部分木になる。継承値に現れない文脈の変化でも、増分の結果は
     * 全再計算と一致し、孫は物理の大きさ（4 × 9）になる。無関係な変化では根と包む箱は前回と同じ参照。
     */
    @Test
    void contextChangeWithoutInheritedChangeRecompilesTheSubtree() {
        SpikeView v = new SpikeView("<html><body style='margin:0'><div id=r style='writing-mode:vertical-rl;height:100px;position:relative'>"
                + "<div id=a style='width:20px'><div id=g style='width:4px;height:9px'></div></div></div><p id=p>x</p></body></html>", 800, 600);
        LayoutFragment first = v.frame();
        assertEquals(9, at(first, "g")[2], EPS); // 縦書きの文脈: 孫は軸を入れ替えて論理 9 × 4
        assertEquals(4, at(first, "g")[3], EPS);
        BoxNode r0 = rootBox(v);
        ((HtmlElement) v.byId("p")).updateTextContent("y");
        v.frame();
        assertSame(r0, rootBox(v), "無関係な変化で根の箱を作り直さない");
        assertSame(OrthogonalBoxes.wrapperOf(r0), OrthogonalBoxes.wrapperOf(rootBox(v)));
        ((HtmlElement) v.byId("a")).setAttribute("style", "position:absolute;top:0;left:0;width:20px");
        LayoutFragment inc = v.frame();
        assertEquals(SpikeView.dump(v.scratch()), SpikeView.dump(inc));
        rect(new float[]{0, 0, 4, 9}, at(inc, "g"));
    }

    private static BoxNode rootBox(SpikeView v) {
        BoxNode b = v.compiler.root();
        return find(b, "r");
    }

    private static BoxNode find(BoxNode b, String id) {
        if (b.node != null && b.pseudo == BoxNode.Pseudo.NONE && id.equals(b.node.attrs.get("id"))) return b;
        for (int i = 0; i < b.children.size(); i++) {
            BoxNode r = find(b.children.get(i), id);
            if (r != null) return r;
        }
        return null;
    }
}
