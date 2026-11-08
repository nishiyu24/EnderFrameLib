package com.nishiyu.enderframelib.core.text;

/**
 * 幅に依存しない段落の下ごしらえ: 折り返し候補の断片・段落内の開始位置・断片ごとの幅と、境目の詰め。
 * 幅が変わる（ウィンドウのリサイズ・flex の再測定）たびに UAX #14 の判定と語ごとの測定をやり直さず、
 * 行の分割はこの数値だけで行う（{@link TextShaper#segmented}）。配列は共有されるので書き換えない。
 *
 * @param segs      折り返し候補の断片（末尾の空白は直前の断片に付く）
 * @param offsets   段落内での断片の開始位置
 * @param fullW     断片の幅（末尾の空白を含む）
 * @param trimmedW  末尾の空白（半角スペース・タブ）を除いた幅
 * @param joinW     直前の断片との境目の詰め（先頭は 0）
 * @param strippedTrailingW  {@link String#stripTrailing} で末尾の空白を除いた幅（インライン整形が行末の空白を数えない幅）
 * @param strippedW          {@link String#strip} で前後の空白を除いた幅（min-content の語の幅）
 */
public record ParagraphSegments(String[] segs, int[] offsets, float[] fullW, float[] trimmedW, float[] joinW,
                                float[] strippedTrailingW, float[] strippedW) {}
