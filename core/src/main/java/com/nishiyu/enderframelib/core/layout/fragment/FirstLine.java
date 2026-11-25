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
}
