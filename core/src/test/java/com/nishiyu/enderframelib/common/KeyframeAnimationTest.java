package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.paint.software.SoftwareRenderBackend;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * {@code @keyframes} / {@code transition} が、書かれたプロパティを実際に動かすこと。
 *
 * <p>進行度 50%（イージングは linear）で止めた画面が、補間した値を静的に指定した画面と画素まで同じになることで確かめる。
 * 値を取り違えるとずれ、動かなければ 0% の画面と同じになる（それも検査する）。ショートハンド（{@code border-radius}・
 * {@code margin}・{@code outline}・{@code inset} など）、背景の位置・大きさ、影、フィルター、{@code clip-path} を含む。</p>
 */
public class KeyframeAnimationTest {

    private static final long T0 = 5_000;
    private static final int W = 200, H = 160;
    private static final String BASE = "position:absolute;left:60px;top:60px;width:50px;height:40px;color:#fff;font-size:14px;";
    private static final String TILE = "background:linear-gradient(90deg,#fa0 50%,#0af 50%) 0 0/20px 20px";
    private static final String TILES2 = "background:linear-gradient(90deg,#fa0 50%,transparent 50%) 0 0/20px 20px,"
            + "linear-gradient(#0af 50%,#333 50%) 0 0/20px 20px";

    private static String html(String css, String elementStyle, String attrs) {
        // css（.go など）は .e より後に置く: 同じ詳細度なら後勝ちなので、.go が .e の値を上書きできる
        return "<html><body style='margin:0;background:#222'><style>.e{" + BASE + elementStyle + "} " + css + "</style>"
                + "<div " + attrs + ">AB</div></body></html>";
    }

    private static int[] shot(String html, boolean toggleClass, long dt) {
        StandaloneView view = new StandaloneView(html, W, H, FontResolver.FALLBACK);
        try (SoftwareRenderBackend backend = new SoftwareRenderBackend(W, H, 1f)) {
            // 変更の前の絵を先に描く（描画側の補間は、項目が変わるのを見て始まる。最初の絵が終わった後の状態だと補間されない）
            view.frame(T0 - 100, backend, true);
            if (toggleClass) {
                // 時刻を決めて始めるため、あとからクラスを付ける（アニメーション・トランジションの開始は、再スタイルの時刻）
                HtmlElement e = (HtmlElement) view.root().getNodeById("e");
                e.setAttribute("class", "e go");
            }
            view.frame(T0, backend, true);
            if (dt > 0) view.frame(T0 + dt, backend, true);
            return backend.pixmap().pixels().clone();
        } finally {
            view.close();
        }
    }

    private static int[] keyframesAt(String from, String to, String elementStyle, long dt) {
        return shot(html("@keyframes k{from{" + from + "}to{" + to + "}} .go{animation:k 1s linear forwards}", elementStyle,
                "class='e' id='e'"), true, dt);
    }

    private static int[] staticAt(String elementStyle, String inline) {
        return shot(html("", elementStyle, "class='e' id='e' style='" + inline + "'"), false, 0);
    }

    private static void check(String name, String from, String to, String elementStyle, String mid) {
        int[] start = keyframesAt(from, to, elementStyle, 0);
        int[] half = keyframesAt(from, to, elementStyle, 500);
        int[] expected = staticAt(elementStyle, mid);
        assertFalse(Arrays.equals(start, half), name + ": 動いていない（進行度 0% と 50% が同じ画面）");
        assertArrayEquals(expected, half, name + ": 50% の画面が、補間値 [" + mid + "] を静的に指定した画面と違う");
    }

    @Test
    public void lengthsAndColors() {
        check("width", "width:20px", "width:80px", "background:#456", "width:50px");
        check("height", "height:10px", "height:70px", "background:#456", "height:40px");
        check("font-size", "font-size:10px", "font-size:30px", "background:#456", "font-size:20px");
    }

    @Test
    public void shorthandsInKeyframes() {
        check("border-radius", "border-radius:0", "border-radius:30px", "background:#456", "border-radius:15px");
        check("border-radius (4 値)", "border-radius:0 0 0 0", "border-radius:20px 0 20px 0", "background:#456", "border-radius:10px 0 10px 0");
        check("margin", "margin:0", "margin:20px", "background:#456", "margin:10px");
        check("padding", "padding:0", "padding:10px", "background:#456", "padding:5px");
        check("inset", "inset:60px", "inset:20px", "background:#456", "inset:40px");
        check("border-color", "border-color:#f00", "border-color:#00f", "background:#456;border:6px solid #888", "border-color:#800080");
        check("border-width", "border-width:2px", "border-width:10px", "background:#456;border:6px solid #f80", "border-width:6px");
        check("outline", "outline:0px solid #f0f", "outline:6px solid #f0f", "background:#456", "outline:3px solid #f0f");
        check("background (単色)", "background:#f00", "background:#00f", "background:#456", "background:#800080");
    }

    @Test
    public void backgroundPositionAndSize() {
        check("background-position (px)", "background-position:0 0", "background-position:20px 0", TILE, "background-position:10px 0");
        check("background-position (%)", "background-position:0% 0", "background-position:100% 0", TILE, "background-position:50% 0");
        check("background-position (キーワード)", "background-position:left top", "background-position:right bottom", TILE, "background-position:50% 50%");
        check("background-position (2 層)", "background-position:0 0, 0 0", "background-position:20px 0, 0 20px", TILES2,
                "background-position:10px 0, 0 10px");
        check("background-size", "background-size:10px 10px", "background-size:30px 30px", "background:linear-gradient(90deg,#fa0 50%,#0af 50%) 0 0/10px 10px",
                "background-size:20px 20px");
    }

    @Test
    public void shadowsFiltersAndClipPath() {
        check("box-shadow", "box-shadow:0 0 0 #fff", "box-shadow:0 0 20px #fff", "background:#456", "box-shadow:0 0 10px 0 #fff");
        check("box-shadow (none から)", "box-shadow:none", "box-shadow:8px 8px 0 #f00", "background:#456", "box-shadow:4px 4px 0 0 rgba(255,0,0,.5)");
        check("text-shadow", "text-shadow:0 0 0 #f0f", "text-shadow:6px 6px 2px #f0f", "background:#456;font-size:20px", "text-shadow:3px 3px 1px #f0f");
        check("filter: blur", "filter:blur(0)", "filter:blur(6px)", "background:#456", "filter:blur(3px)");
        check("filter: 連鎖", "filter:none", "filter:blur(4px) brightness(.5)", "background:#f80", "filter:blur(2px) brightness(.75)");
        check("filter: hue-rotate", "filter:hue-rotate(0deg)", "filter:hue-rotate(180deg)", "background:#f80", "filter:hue-rotate(90deg)");
        check("clip-path: inset", "clip-path:inset(0)", "clip-path:inset(40%)", "background:#456", "clip-path:inset(20%)");
        check("clip-path: circle", "clip-path:circle(10px at 50% 50%)", "clip-path:circle(30px at 50% 50%)", "background:#456", "clip-path:circle(20px at 50% 50%)");
        check("transform-origin", "transform-origin:0 0", "transform-origin:100% 100%", "background:#456;transform:rotate(30deg)", "transform-origin:50% 50%");
    }

    // ------------------------------------------------------------------ 0% / 100% の省略

    private static void checkImplicit(String name, String keyframes, String elementStyle, String mid) {
        String css = "@keyframes k{" + keyframes + "} .go{animation:k 1s linear forwards}";
        int[] start = shot(html(css, elementStyle, "class='e' id='e'"), true, 0);
        int[] half = shot(html(css, elementStyle, "class='e' id='e'"), true, 500);
        int[] expected = staticAt(elementStyle, mid);
        assertFalse(Arrays.equals(start, half), name + ": 動いていない（省略した端が補われていない）");
        assertArrayEquals(expected, half, name + ": 50% の画面が、[" + mid + "] を静的に指定した画面と違う");
    }

    @Test
    public void omittedFromAndToUseTheElementsOwnValues() {
        String grad = "background:linear-gradient(90deg,#f00,#00f)";
        // spin: @keyframes spin { to { transform: rotate(360deg) } } — 元の姿勢（回転なし）から 360° まで
        checkImplicit("to のみ: rotate", "to{transform:rotate(360deg)}", grad, "transform:rotate(180deg)");
        checkImplicit("from のみ: rotate", "from{transform:rotate(90deg)}", grad, "transform:rotate(45deg)");
        checkImplicit("to のみ: 元の変形から", "to{transform:translateX(50px)}", "background:#456;transform:translateX(10px)", "transform:translateX(30px)");
        checkImplicit("to のみ: 元の不透明度から", "to{opacity:.2}", "background:#456;opacity:.8", "opacity:.5");
        checkImplicit("to のみ: 背景色", "to{background-color:#00f}", "background:#f00", "background:#800080");
        // 50% だけ: 両端は元の値
        checkImplicit("50% だけ", "50%{transform:rotate(100deg)}", grad, "transform:rotate(100deg)");
        // 元の変形（scale）を持つ要素の spin（showcase の aurora.b）: scale は 0.8 → 1 へ、同時に回る
        checkImplicit("元が scale の spin", "to{transform:rotate(360deg)}", grad + ";transform:scale(.8)", "transform:scale(.9) rotate(180deg)");
    }

    // ------------------------------------------------------------------ トランジション

    private static void checkTransition(String name, String transitionProperty, String from, String to, String elementStyle, String mid) {
        String css = ".e{transition:" + transitionProperty + " 1s linear} .go{" + to + "}"; // どちらも本体の .e のあと
        int[] start = shot(html(css, elementStyle + ";" + from, "class='e' id='e'"), true, 0);
        int[] half = shot(html(css, elementStyle + ";" + from, "class='e' id='e'"), true, 500);
        int[] expected = staticAt(elementStyle + ";" + from, mid);
        assertFalse(Arrays.equals(start, half), name + ": 動いていない");
        assertArrayEquals(expected, half, name + ": 50% の画面が違う");
    }

    @Test
    public void transitionsNamedByShorthand() {
        // transition-property にショートハンドの名前を書く（動くのはロングハンド）
        checkTransition("border-radius", "border-radius", "border-radius:0", "border-radius:30px", "background:#456", "border-radius:15px");
        checkTransition("margin", "margin", "margin:0", "margin:20px", "background:#456", "margin:10px");
        checkTransition("border-color", "border-color", "border-color:#f00", "border-color:#00f", "background:#456;border-width:6px;border-style:solid", "border-color:#800080");
        checkTransition("padding (all)", "all", "padding:0", "padding:10px", "background:#456", "padding:5px");
        checkTransition("background-position", "background-position", "background-position:0 0", "background-position:20px 0", TILE,
                "background-position:10px 0");
    }
}
