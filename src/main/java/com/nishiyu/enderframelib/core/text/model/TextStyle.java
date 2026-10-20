package com.nishiyu.enderframelib.core.text.model;

import com.nishiyu.enderframelib.core.text.*;
import com.nishiyu.enderframelib.core.text.model.*;

/**
 * 整形に必要なスタイル。
 *
 * @param breakLongWords 1 行に収まらない語を内側で割るか（{@code overflow-wrap: break-word}）
 * @param ellipsis       折り返さない行があふれたら省略記号で切るか（{@code text-overflow: ellipsis}）
 * @param lineHeightPx   1 行が占める高さ（px）。{@code normal} の解決は呼び出し側が済ませる
 */
public record TextStyle(FontStyle font, WhiteSpace whiteSpace, WordBreak wordBreak,
                        boolean breakLongWords, boolean ellipsis, float lineHeightPx) {}
