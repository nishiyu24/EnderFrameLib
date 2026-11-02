package com.nishiyu.enderframelib.core.text.model;

import com.nishiyu.enderframelib.core.text.*;
import com.nishiyu.enderframelib.core.text.model.*;

/**
 * 整形結果の 1 行。
 *
 * @param text       実際に描く文字列（省略記号も含めて確定済み）
 * @param width      {@code text} の送り幅（px）
 * @param baseline   行の上端からベースラインまでの距離（px）
 * @param height     行が占める高さ（px）
 * @param start      正規化後テキストにおける開始位置（char 単位）
 * @param end        同じく終了位置。末尾の空白は含まない
 * @param ellipsized 省略記号で切られたか
 */
public record ShapedLine(String text, float width, float baseline, float height,
                         int start, int end, boolean ellipsized) {}
