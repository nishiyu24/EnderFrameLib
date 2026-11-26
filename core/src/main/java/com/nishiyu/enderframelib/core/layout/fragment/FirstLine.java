package com.nishiyu.enderframelib.core.layout.fragment;

import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.Style;

/**
 * {@code ::first-line}（M2c ④）: ブロックコンテナの 1 行目に載った文字の節に効くスタイル。持ち主のスタイルから受け継いだまま
 * 上書きしていないプロパティだけを {@code ::first-line} の値に替える（旧 {@code ComputedStyle.derivedFirstLineChild} と同じ規則）。
 * レイアウト（1 行目の語を測る）と描画用の木（1 行目の語を描く）が同じ規則を使う。
 */
public final class FirstLine {
    private FirstLine() { }

    /** 1 行目の文字の節（スタイル {@code text}）に効くスタイル。{@code owner} は {@code ::first-line} の持ち主、{@code firstLine} はその値。 */
    public static Style of(Style text, Style owner, Style firstLine) {
        return new ComputedStyle(text).derivedFirstLineChild(new ComputedStyle(owner), new ComputedStyle(firstLine)).typed();
    }

    /**
     * 1 行目の {@code text-transform}（{@code lineStyle} は {@link #of} のスタイル）を、節の中身 {@code text}（節自身のスタイル
     * {@code textStyle} の変換は済んでいる）に掛ける。行の片の位置と揃うよう長さを変えない 1 文字ごとの写像（{@code ß} は大文字にしない）。
     * {@code capitalize} の語の頭は空白の後ろ（前の節の語の続きは見ない）。レイアウトが語を測るときと描画用の木が語を描くときに使う。
     */
    public static String transform(String text, Style textStyle, Style lineStyle) {
        var tt = lineStyle.inherited.textTransform;
        if (tt == null || tt == textStyle.inherited.textTransform) return text;
        int mode = switch (tt) {
            case UPPERCASE -> 1;
            case LOWERCASE -> 2;
            case CAPITALIZE -> 3;
            default -> 0;
        };
        if (mode == 0) return text;
        char[] out = text.toCharArray();
        for (int i = 0; i < out.length; i++) {
            char c = out[i];
            out[i] = mode == 1 ? Character.toUpperCase(c) : mode == 2 ? Character.toLowerCase(c)
                    : i == 0 || Character.isWhitespace(out[i - 1]) ? Character.toUpperCase(c) : c;
        }
        return new String(out);
    }
}
