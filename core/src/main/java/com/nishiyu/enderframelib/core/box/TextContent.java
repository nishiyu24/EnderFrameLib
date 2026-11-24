package com.nishiyu.enderframelib.core.box;

import com.nishiyu.enderframelib.core.css.Style;
import com.nishiyu.enderframelib.core.css.model.CssKeywords;
import com.nishiyu.enderframelib.core.text.TextShaper;

import java.util.Locale;

/**
 * 文字の節の中身（Engine v3 M2b-1）。DOM の文字列から、レイアウトと描画が読む {@link BoxNode#text} を作る純粋な関数。
 *
 * <ol>
 *   <li>{@code white-space} による空白の正規化（{@link TextShaper#normalize}）。</li>
 *   <li>{@code text-transform}（{@code uppercase} / {@code lowercase} / {@code capitalize}）。大文字・小文字の写像は言語に依らない
 *       {@link Locale#ROOT}。</li>
 *   <li>描かれず幅も持たない書式文字（ゼロ幅の空白・双方向の制御文字・BOM。CSS Text 3 §4.1.1 の「描かない」制御文字）の除去。</li>
 * </ol>
 *
 * <p>入力は節自身の文字列と、親の継承群を共有した文字の節のスタイルだけ（局所）。要素の境目をまたぐ語の頭
 * （{@code p<span>ass</span>} の {@code capitalize}）は前の節の文字に依る非局所の依存なので扱わない（計画書 §3.4.1 M2b-1 の残り）。</p>
 */
public final class TextContent {
    private TextContent() { }

    /** 文字の節 {@code raw} の、スタイル {@code st}（文字の節のスタイル）での中身。 */
    public static String of(String raw, Style st) {
        String text = TextShaper.normalize(raw, st.facts.whiteSpace);
        if (text.isEmpty()) return text;
        CssKeywords.TextTransform tt = st.inherited.textTransform;
        if (tt != null) {
            text = switch (tt) {
                case UPPERCASE -> text.toUpperCase(Locale.ROOT);
                case LOWERCASE -> text.toLowerCase(Locale.ROOT);
                case CAPITALIZE -> capitalize(text);
                case NONE -> text;
            };
        }
        return stripIgnorable(text);
    }

    /** 語（空白で区切った並び）の頭の文字を大文字にする。 */
    static String capitalize(String text) {
        StringBuilder sb = null;
        boolean start = true;
        for (int i = 0, n = text.length(); i < n; i++) {
            char c = text.charAt(i);
            if (start && !Character.isWhitespace(c)) {
                char u = Character.toUpperCase(c);
                if (u != c) {
                    if (sb == null) sb = new StringBuilder(text);
                    sb.setCharAt(i, u);
                }
            }
            start = Character.isWhitespace(c);
        }
        return sb == null ? text : sb.toString();
    }

    /** 描かない書式文字を除く（無ければ同じ参照）。 */
    static String stripIgnorable(String s) {
        int n = s.length(), i = 0;
        while (i < n && !ignorable(s.charAt(i))) i++;
        if (i == n) return s;
        StringBuilder sb = new StringBuilder(n);
        sb.append(s, 0, i);
        for (; i < n; i++) {
            char c = s.charAt(i);
            if (!ignorable(c)) sb.append(c);
        }
        return sb.toString();
    }

    private static boolean ignorable(char c) {
        if (c < 0x200B) return false;
        return c <= 0x200F || (c >= 0x202A && c <= 0x202E) || (c >= 0x2060 && c <= 0x2064) || (c >= 0x2066 && c <= 0x2069) || c == 0xFEFF;
    }
}
