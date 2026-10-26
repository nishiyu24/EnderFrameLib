package com.nishiyu.enderframelib.core.dom;

import com.nishiyu.enderframelib.core.dom.node.*;
import com.nishiyu.enderframelib.core.dom.parser.*;

import com.nishiyu.enderframelib.core.dom.node.NodeType;
import com.nishiyu.enderframelib.core.dom.parser.HtmlParser.VoidElements;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import com.nishiyu.enderframelib.common.Document;
import com.nishiyu.enderframelib.common.RenderPipeline;
import com.nishiyu.enderframelib.core.paint.*;
import com.nishiyu.enderframelib.core.text.*;
import com.nishiyu.enderframelib.core.text.model.*;
import com.nishiyu.enderframelib.core.layout.LayoutContext;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.layout.model.Viewport;
import com.nishiyu.enderframelib.core.layout.model.FormattingContext;
import com.nishiyu.enderframelib.core.layout.model.ConstraintSpace;
import com.nishiyu.enderframelib.core.layout.model.LayoutMetrics;
import com.nishiyu.enderframelib.core.css.model.Overflow;
import com.nishiyu.enderframelib.core.css.model.TextAlign;
import com.nishiyu.enderframelib.core.css.model.BorderStyle;

/**
 * WHATWG のツリー構築（W7）。期待値はブラウザの実測（Edge 155 の {@code DOMParser} と innerHTML）。
 *
 * 文脈が {@code doc} のケースは文書として（{@code <!DOCTYPE html>} を前置した no-quirks）、
 * それ以外はその要素の innerHTML として解析した木を、属性を書いた順・空要素に終了タグなし・
 * テキストはそのまま（U+00A0 だけ {@code &nbsp;}）で書き出して比べる。
 * パーサが振る id（{@code gen_N} / {@code txt_N}）と textarea の {@code value} は比べない。
 *
 * 書式要素の再構築と adoption agency（R9）は {@code <p><b>x</p>y} などで固定する。コメントはノードにしないので、
 * ブラウザの木から取り除いた形を期待値にする。
 */
public class HtmlTreeConstructionTest {

    /** {文脈, 入力, ブラウザの木} */
    private static final String[][] CASES = {
            {"doc", "<p>hi", "<html><head></head><body><p>hi</p></body></html>"},
            {"doc", "<title>T &amp; U</title><link rel=stylesheet href=a.css><div>x</div>", "<html><head><title>T & U</title><link rel=\"stylesheet\" href=\"a.css\"></head><body><div>x</div></body></html>"},
            {"doc", "<html lang=ja><body class=b><div>x</div></body></html>", "<html lang=\"ja\"><head></head><body class=\"b\"><div>x</div></body></html>"},
            {"doc", "<div>a</div></body><p>after", "<html><head></head><body><div>a</div><p>after</p></body></html>"},
            {"doc", "", "<html><head></head><body></body></html>"},
            {"doc", "<div>x</div><body id=late>", "<html><head></head><body id=\"late\"><div>x</div></body></html>"},
            {"doc", "<p>a<table><tr><td>b</table>", "<html><head></head><body><p>a</p><table><tbody><tr><td>b</td></tr></tbody></table></body></html>"},
            {"body", "<ul><li>a<li>b</ul>", "<ul><li>a</li><li>b</li></ul>"},
            {"body", "<dl><dt>t<dd>d<dt>t2</dl>", "<dl><dt>t</dt><dd>d</dd><dt>t2</dt></dl>"},
            {"body", "<p>a<div>b</div>", "<p>a</p><div>b</div>"},
            {"body", "<p><span>a<div>b</div>", "<p><span>a</span></p><div>b</div>"},
            {"body", "<table><tr><td>a<td>b<tr><td>c</table>", "<table><tbody><tr><td>a</td><td>b</td></tr><tr><td>c</td></tr></tbody></table>"},
            {"body", "<table><td>x</td></table>", "<table><tbody><tr><td>x</td></tr></tbody></table>"},
            {"body", "<table>text<tr><td>a</td></tr></table>", "text<table><tbody><tr><td>a</td></tr></tbody></table>"},
            {"body", "<table><div>f</div><tr><td>a</table>", "<div>f</div><table><tbody><tr><td>a</td></tr></tbody></table>"},
            {"body", "<select><option>a<option>b<optgroup label=g><option>c</select>", "<select><option>a</option><option>b</option><optgroup label=\"g\"><option>c</option></optgroup></select>"},
            {"body", "</p>", "<p></p>"},
            {"body", "a</br>b", "a<br>b"},
            {"body", "<div><span>a</div>b", "<div><span>a</span></div>b"},
            {"body", "<h1>a<h2>b", "<h1>a</h1><h2>b</h2>"},
            {"body", "<table><caption>c<tr><td>a</table>", "<table><caption>c</caption><tbody><tr><td>a</td></tr></tbody></table>"},
            {"body", "<table><colgroup><col><col></colgroup><tr><td>a</table>", "<table><colgroup><col><col></colgroup><tbody><tr><td>a</td></tr></tbody></table>"},
            {"body", "<table><col><tr><td>a</table>", "<table><colgroup><col></colgroup><tbody><tr><td>a</td></tr></tbody></table>"},
            {"body", "<table><thead><tr><th>h<tbody><tr><td>d</table>", "<table><thead><tr><th>h</th></tr></thead><tbody><tr><td>d</td></tr></tbody></table>"},
            {"body", "<p>&copy; &hellip; &nbsp;x &notin; &amp;lt; &#x41; &#128; &#65 &#0; &AMP</p>", "<p>\u00A9 \u2026 &nbsp;x \u2209 &lt; A \u20AC A \uFFFD &</p>"},
            {"body", "<a href=\"?a=1&copy=2&lt=3\">l</a>", "<a href=\"?a=1&copy=2&lt=3\">l</a>"},
            {"body", "<a title=\"&copy;&notit &amp\">l</a>", "<a title=\"\u00A9&notit &\">l</a>"},
            {"body", "<div/>x", "<div>x</div>"},
            {"body", "<button>a<button>b", "<button>a</button><button>b</button>"},
            {"body", "<a href=1>a<a href=2>b", "<a href=\"1\">a</a><a href=\"2\">b</a>"},
            {"body", "<li>a<ul><li>b</ul><li>c", "<li>a<ul><li>b</li></ul></li><li>c</li>"},
            {"body", "<table><tr><td><table><tr><td>in</table>after</td></tr></table>", "<table><tbody><tr><td><table><tbody><tr><td>in</td></tr></tbody></table>after</td></tr></tbody></table>"},
            {"body", "<tr><td>x</td></tr>", "x"},
            {"tbody", "<tr><td>a</td></tr>", "<tr><td>a</td></tr>"},
            {"template", "<tr><td>a</td></tr>", "<tr><td>a</td></tr>"},
            {"tr", "<td>a<td>b", "<td>a</td><td>b</td>"},
            {"body", "<ruby>k<rt>a<rt>b</ruby>", "<ruby>k<rt>a</rt><rt>b</rt></ruby>"},
            {"body", "<p>a<p>b<ul><li>c</ul>", "<p>a</p><p>b</p><ul><li>c</li></ul>"},
            {"body", "<table><tr><td>a</tr><td>b</table>", "<table><tbody><tr><td>a</td></tr><tr><td>b</td></tr></tbody></table>"},
            {"body", "<option>a<option>b", "<option>a</option><option>b</option>"},
            {"body", "<div id=a id=b>x</div>", "<div id=\"a\">x</div>"},
            {"body", "a<3 b</>c", "a<3 bc"},
            {"body", "<form><form><input></form>", "<form><input></form>"},
            {"body", "<p><b>x</p>y", "<p><b>x</b></p><b>y</b>"},
            {"body", "<b><i>a</b>b</i>", "<b><i>a</i></b><i>b</i>"},
            {"body", "<a>1<div>2</a>3</div>", "<a>1</a><div><a>2</a>3</div>"},
            {"body", "<a href=1>x<a href=2>y", "<a href=\"1\">x</a><a href=\"2\">y</a>"},
            {"body", "<b>1<p>2</b>3</p>", "<b>1</b><p><b>2</b>3</p>"},
            {"body", "<p><b><i>x</p>y", "<p><b><i>x</i></b></p><b><i>y</i></b>"},
            {"body", "<b>a<table><td>x</table>b", "<b>a<table><tbody><tr><td>x</td></tr></tbody></table>b</b>"},
            {"body", "<b><b><b><b>x</b></b></b></b>", "<b><b><b><b>x</b></b></b></b>"},
            {"body", "<nobr>1<nobr>2", "<nobr>1</nobr><nobr>2</nobr>"},
            {"body", "<b><div><i>x</b>y</i>z</div>", "<b></b><div><b><i>x</i></b><i>y</i>z</div>"},
            {"body", "<a>1<p>2</a>3", "<a>1</a><p><a>2</a>3</p>"},
            {"body", "<b>a<p>b<a>c</b>d</a>e", "<b>a</b><p><b>b<a>c</a></b><a>d</a>e</p>"},
            {"body", "<b>a<button>x</button>b", "<b>a<button>x</button>b</b>"},
            {"body", "<i>a<ul><li>b</i>c</ul>", "<i>a</i><ul><i></i><li><i>b</i>c</li></ul>"},
            {"body", "<p>a<!-->b", "<p>ab</p>"},
            {"body", "<p>a<!--->b", "<p>ab</p>"},
            {"body", "<p>a<!-- x --!>b", "<p>ab</p>"},
            {"body", "<p>a<!-- never", "<p>a</p>"}
    };

    @TestFactory
    public Stream<DynamicTest> testMatchesBrowserTree() {
        return Arrays.stream(CASES).map(c -> DynamicTest.dynamicTest("[" + c[0] + "] " + c[1], () -> {
            HtmlNode root = "doc".equals(c[0])
                    ? HtmlParser.parseDocument(c[1])
                    : HtmlParser.parseFragment(c[1], c[0]);
            StringBuilder out = new StringBuilder();
            for (HtmlNode child : root.children) serialize(child, out);
            assertEquals(c[2], out.toString());
        }));
    }

    private static void serialize(HtmlNode node, StringBuilder out) {
        if (node.nodeType == NodeType.TEXT) {
            out.append(node.text.replace(" ", "&nbsp;"));
            return;
        }
        out.append('<').append(node.tag);
        for (Map.Entry<String, String> attr : node.attrs.entrySet()) {
            String value = attr.getValue();
            if (attr.getKey().equals("id") && (value.startsWith("gen_") || value.startsWith("txt_"))) continue;
            if (attr.getKey().equals("value") && node.tag.equals("textarea")) continue;
            out.append(' ').append(attr.getKey()).append("=\"").append(value).append('"');
        }
        out.append('>');
        if (VoidElements.isVoid(node.tag)) return;
        for (HtmlNode child : node.children) serialize(child, out);
        out.append("</").append(node.tag).append('>');
    }
}
