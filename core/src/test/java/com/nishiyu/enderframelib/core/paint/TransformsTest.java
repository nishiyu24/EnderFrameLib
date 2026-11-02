package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.paint.Transforms.Affine;
import com.nishiyu.enderframelib.core.paint.Transforms.Context;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** {@code transform} を宣言順に合成する {@link Transforms}（CSS Transforms 2 §6・§13）。 */
public class TransformsTest {
    private static final Context CTX = new Context(200f, 40f, 800f, 600f, 16f, 16f);
    private static final float EPS = 0.001f;

    private static float[] trs(String transform) {
        return Transforms.toTrs(Transforms.parse(transform, CTX));
    }

    private static void assertTrs(float[] actual, float tx, float ty, float sx, float sy, float rot) {
        assertEquals(tx, actual[0], EPS, "tx");
        assertEquals(ty, actual[1], EPS, "ty");
        assertEquals(sx, actual[2], EPS, "sx");
        assertEquals(sy, actual[3], EPS, "sy");
        assertEquals(rot, actual[4], EPS, "rot");
    }

    @Test
    @DisplayName("none・空・null は恒等")
    void identity() {
        assertEquals(Affine.IDENTITY, Transforms.parse("none", CTX));
        assertEquals(Affine.IDENTITY, Transforms.parse("", CTX));
        assertEquals(Affine.IDENTITY, Transforms.parse(null, CTX));
    }

    @Test
    @DisplayName("scale してから translate: 平行移動が拡大される（宣言順）")
    void scaleThenTranslateScalesTheOffset() {
        assertTrs(trs("scale(2) translate(10px, 5px)"), 20f, 10f, 2f, 2f, 0f);
        assertTrs(trs("translate(10px, 5px) scale(2)"), 10f, 5f, 2f, 2f, 0f);
    }

    @Test
    @DisplayName("rotate してから translate: 平行移動が回る（宣言順）")
    void rotateThenTranslateRotatesTheOffset() {
        // 90° 回してから +x へ 10px は、画面では +y へ 10px
        assertTrs(trs("rotate(90deg) translate(10px, 0)"), 0f, 10f, 1f, 1f, 90f);
        assertTrs(trs("translate(10px, 0) rotate(90deg)"), 10f, 0f, 1f, 1f, 90f);
    }

    @Test
    @DisplayName("% は軸ごとに箱の幅・高さが基準")
    void percentagesUseTheBoxAxes() {
        assertTrs(trs("translate(-50%, -50%)"), -100f, -20f, 1f, 1f, 0f);
        assertTrs(trs("translateX(25%) translateY(25%)"), 50f, 10f, 1f, 1f, 0f);
        assertTrs(trs("translate(-50%, 0)"), -100f, 0f, 1f, 1f, 0f);
    }

    @Test
    @DisplayName("em・rem・calc() の長さ")
    void relativeLengths() {
        assertTrs(trs("translate(2em, 1rem)"), 32f, 16f, 1f, 1f, 0f);
        assertTrs(trs("translateX(calc(10px + 5px))"), 15f, 0f, 1f, 1f, 0f);
    }

    @Test
    @DisplayName("角度の単位 deg・rad・grad・turn")
    void angleUnits() {
        assertEquals(90f, trs("rotate(90deg)")[4], EPS);
        assertEquals(90f, trs("rotate(" + (Math.PI / 2) + "rad)")[4], 0.01f);
        assertEquals(90f, trs("rotate(100grad)")[4], EPS);
        assertEquals(90f, trs("rotate(0.25turn)")[4], EPS);
        assertEquals(0f, trs("rotate(0)")[4], EPS);
    }

    @Test
    @DisplayName("scale の 1 引数・2 引数・scaleX/Y と、負の拡大は反転のまま")
    void scaleForms() {
        assertTrs(trs("scale(1.5)"), 0f, 0f, 1.5f, 1.5f, 0f);
        assertTrs(trs("scale(2, 3)"), 0f, 0f, 2f, 3f, 0f);
        assertTrs(trs("scaleX(2) scaleY(0.5)"), 0f, 0f, 2f, 0.5f, 0f);
        assertTrs(trs("scale(-1, 1)"), 0f, 0f, -1f, 1f, 0f);
    }

    @Test
    @DisplayName("matrix() と matrix3d() の 2D の成分")
    void matrices() {
        assertTrs(trs("matrix(2, 0, 0, 3, 10, 20)"), 10f, 20f, 2f, 3f, 0f);
        assertTrs(trs("matrix3d(2,0,0,0, 0,3,0,0, 0,0,1,0, 10,20,0,1)"), 10f, 20f, 2f, 3f, 0f);
        assertTrs(trs("matrix(0, 1, -1, 0, 0, 0)"), 0f, 0f, 1f, 1f, 90f);
    }

    @Test
    @DisplayName("関数の途中の空白・大文字小文字・未知の関数")
    void tolerantSyntax() {
        assertTrs(trs("  TRANSLATE( 4px ,  6px )   SCALE( 2 ) "), 4f, 6f, 2f, 2f, 0f);
        assertTrs(trs("perspective(100px) translateX(3px)"), 3f, 0f, 1f, 1f, 0f);
    }

    @Test
    @DisplayName("3D の translateZ・rotateX は 2D に効かない")
    void threeDimensionalPartsAreIgnored() {
        assertTrs(trs("translateZ(10px) rotateX(45deg) translateX(3px)"), 3f, 0f, 1f, 1f, 0f);
        assertTrs(trs("translate3d(1px, 2px, 3px)"), 1f, 2f, 1f, 1f, 0f);
    }

    @Test
    @DisplayName("個別プロパティは translate → rotate → scale → transform の順に合成し、transform はその座標系の中")
    void individualPropertiesComeBeforeTransform() {
        Affine m = Transforms.compose("10px 20px", "90deg", "2", "translateX(5px)", CTX);
        // 原点は transform の (5,0) → scale で (10,0) → rotate(90°) で (0,10) → translate で (10,30)
        assertEquals(10.0, m.e(), 1e-6);
        assertEquals(30.0, m.f(), 1e-6);
        float[] t = Transforms.toTrs(m);
        assertEquals(2f, t[2], EPS);
        assertEquals(2f, t[3], EPS);
        assertEquals(90f, t[4], EPS);
    }

    @Test
    @DisplayName("rotate プロパティの軸つきの書き方と scale プロパティ")
    void individualPropertyForms() {
        assertEquals(30f, Transforms.toTrs(Transforms.compose(null, "z 30deg", null, null, CTX))[4], EPS);
        assertEquals(0f, Transforms.toTrs(Transforms.compose(null, "x 30deg", null, null, CTX))[4], EPS);
        assertEquals(3f, Transforms.toTrs(Transforms.compose(null, null, "3", null, CTX))[3], EPS);
        assertEquals(2f, Transforms.toTrs(Transforms.compose(null, null, "3 2", null, CTX))[3], EPS);
    }

    @Test
    @DisplayName("単位のない角度は度として読む（互換）")
    void unitlessAngleIsDegrees() {
        assertEquals(45f, trs("rotate(45)")[4], EPS);
    }
}
