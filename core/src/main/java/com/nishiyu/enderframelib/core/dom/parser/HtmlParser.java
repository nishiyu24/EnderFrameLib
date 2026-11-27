package com.nishiyu.enderframelib.core.dom.parser;

import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.node.VoidElements;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * WHATWG HTML のツリー構築（§13.2.6）の主要な規則で DOM を組む。
 *
 * 文書（{@link #parseDocument}）は暗黙の {@code html} / {@code head} / {@code body} を補い、
 * 断片（{@link #parseFragment}）は innerHTML と同じく文脈の要素の挿入モードから始める。
 * 挿入モードは状態として持たず、開いている要素のスタックから「挿入モードを適切に戻す」
 * 手順（§13.2.4.1）で毎回求める。表と本文のモードはスタックの形だけで決まるため。
 *
 * 何が壊れていたか: 以前は暗黙終了が {@code p} / {@code li} / {@code dt} / {@code dd} の
 * 「直上の要素が同じなら閉じる」だけで、暗黙の {@code tbody}・{@code tr} / {@code td} /
 * {@code option} の暗黙終了・表の中に誤って置かれた要素の扱いが無く、断片の文書には
 * {@code body} が無かった（ua.css の {@code body { margin: 8px }} が効かなかった）。
 *
 * 書式要素（{@code a b big code em font i nobr s small strike strong tt u}）は、仕様どおり
 * active formatting elements の一覧に入れる（§13.2.4.3）。{@code <p><b>a<p>b} の 2 つ目の段落は太字になり
 * （再構築）、{@code <b><i></b></i>} や {@code <a><div></a>} の誤った入れ子は adoption agency（§13.2.6.4.7）で
 * 木を組み替える。{@code td} / {@code th} / {@code caption} / {@code applet} / {@code object} / {@code marquee} /
 * {@code template} の開始は一覧にマーカーを置き、その要素が閉じたらマーカー以降の項目を捨てる。
 *
 * {@code <template>} の中身は要素の子ではなく、不活性な {@code DocumentFragment}（content）に置く。
 * パーサの挿入先・{@code innerHTML} / {@code outerHTML}・{@code cloneNode}・ネットワークの直列化が
 * 同じ置き場を読み、{@code Bindings} は content の子を雛形にする。
 *
 * LIMITATION(制約): SVG / MathML の外来要素は HTML の暗黙終了を受けず自己終了タグで閉じるが、
 * 名前空間は持たない（要素名と属性名は小文字のまま。{@code SvgRenderer} が小文字で読む）。
 * README の "Not supported" に対応。
 *
 * 1 回の解析の状態と挿入モードは {@link TreeBuilder}（書式要素は {@link ActiveFormatting}、表は {@link TableModes}）、
 * 字句の判定とテキストの整え方は {@link ParserText}、規則が引く要素名の集合は {@link TagSets} にある。
 */
public class HtmlParser {

    static final Logger LOGGER = LoggerFactory.getLogger(HtmlParser.class);

    /** 文脈が差し込む既定の上限（A13）。null なら {@link ParseLimits#DEFAULT}。 */
    public interface Source { ParseLimits parseLimits(); }
    private static volatile java.util.function.Supplier<Source> source = () -> () -> null;
    /** このスレッドの文脈の値を引く口を結ぶ（{@code EngineContext} が呼ぶ）。 */
    public static void bindSource(java.util.function.Supplier<Source> s) { source = s == null ? () -> () -> null : s; }

    public static ParseLimits defaultLimits() {
        ParseLimits l = source.get().parseLimits();
        return l != null ? l : ParseLimits.DEFAULT;
    }

    /**
     * 断片を {@code body} の文脈で解析する（innerHTML と同じ）。
     * ルートの子が断片の最上位のノードになる。
     */
    public static HtmlNode parse(String html) {
        return parseFragment(html, "body");
    }

    public static HtmlNode parse(String html, ParseLimits limits) {
        return parseFragment(html, "body", limits);
    }

    /**
     * XHTML（XML として読む文書。ブラウザなら {@code application/xhtml+xml}・拡張子 {@code .xht} / {@code .xhtml}）を、
     * HTML として読んで同じ木になる文字列へ直す。本パーサは HTML の構文しか持たないので、XML の書き方のうち意味が変わる 2 つを書き換える:
     * <ul>
     *   <li>void でない要素の自己終了 {@code <div/>} → {@code <div></div>}（HTML では {@code /} が無視され、後ろの兄弟が子になる）。</li>
     *   <li>CDATA 節 {@code <![CDATA[...]]>} → 中身（{@code style}/{@code script} の中ではそのまま、それ以外では文字として逃がす）。</li>
     * </ul>
     * 文書が XHTML かどうかは内容からは決められない（{@code xmlns} 付きの text/html も多い）ので、呼び出し側が種類を知っているときに使う。
     */
    public static String xhtmlToHtml(String xhtml) {
        int len = xhtml.length();
        StringBuilder out = new StringBuilder(len + 64);
        int i = 0;
        while (i < len) {
            char c = xhtml.charAt(i);
            if (c != '<') { out.append(c); i++; continue; }
            if (xhtml.startsWith("<!--", i)) {
                int end = xhtml.indexOf("-->", i + 4);
                int stop = end < 0 ? len : end + 3;
                out.append(xhtml, i, stop);
                i = stop;
                continue;
            }
            if (xhtml.startsWith("<![CDATA[", i)) {
                int end = xhtml.indexOf("]]>", i + 9);
                int stop = end < 0 ? len : end;
                for (int k = i + 9; k < stop; k++) {
                    char d = xhtml.charAt(k);
                    out.append(d == '&' ? "&amp;" : d == '<' ? "&lt;" : String.valueOf(d));
                }
                i = end < 0 ? len : end + 3;
                continue;
            }
            int nameStart = i + 1;
            int j = nameStart;
            while (j < len && HtmlTokenizer.isValidTagNameChar(xhtml.charAt(j))) j++;
            if (j == nameStart || !HtmlTokenizer.isAsciiAlpha(xhtml.charAt(nameStart))) { out.append(c); i++; continue; }
            String name = xhtml.substring(nameStart, j).toLowerCase();
            // タグの終わり（引用符の中の '>' は飛ばす）
            char q = 0;
            int k = j;
            for (; k < len; k++) {
                char d = xhtml.charAt(k);
                if (q != 0) { if (d == q) q = 0; }
                else if (d == '"' || d == '\'') q = d;
                else if (d == '>') break;
            }
            if (k >= len) { out.append(xhtml, i, len); break; }
            boolean selfClosing = xhtml.charAt(k - 1) == '/';
            if (selfClosing && !VoidElements.isVoid(name)) {
                out.append(xhtml, i, k - 1).append("></").append(name).append('>');
                i = k + 1;
                continue;
            }
            out.append(xhtml, i, k + 1);
            i = k + 1;
            if (!selfClosing && (name.equals("style") || name.equals("script"))) {
                // 中身は生の文字。CDATA の印だけ外す（XML では印は中身に入らない）
                int close = HtmlTokenizer.findClosingTagIgnoreCase(xhtml, i, name);
                int stop = close < 0 ? len : close;
                out.append(xhtml.substring(i, stop).replace("<![CDATA[", "").replace("]]>", ""));
                i = stop;
            }
        }
        return out.toString();
    }

    /**
     * 文書として解析する。ルートの子は {@code html} の 1 つだけで、その下に {@code head} と {@code body} がある。
     * 書かれていなければ補う。
     */
    public static HtmlNode parseDocument(String html) {
        return parseDocument(html, defaultLimits());
    }

    public static HtmlNode parseDocument(String html, ParseLimits limits) {
        TreeBuilder builder = new TreeBuilder(false, null, limits);
        builder.run(html);
        builder.ensureBody();
        builder.root.sealNodeIds(); // 節の鍵は文書順の番号から導く。文字列を属性へ振らない
        return builder.root;
    }

    /**
     * 断片を文脈の要素（{@code contextTag}）の中身として解析する（HTML §13.4 の断片の解析）。
     * {@code tbody} の文脈なら {@code <tr>} がそのまま行になり、{@code template} の文脈なら
     * 表の部品を最上位に置ける。
     */
    public static HtmlNode parseFragment(String html, String contextTag) {
        return parseFragment(html, contextTag, defaultLimits());
    }

    public static HtmlNode parseFragment(String html, String contextTag, ParseLimits limits) {
        String context = contextTag == null || contextTag.isEmpty() ? "body" : contextTag.toLowerCase();
        TreeBuilder builder = new TreeBuilder(true, context, limits);
        builder.run(html);
        return builder.root;
    }
}
