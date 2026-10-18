package com.nishiyu.enderframelib.core.text;

/**
 * 1 回の測定を決める書体の指定。
 *
 * 何が壊れていたか: 以前の測定インターフェースは {@code (String, float scale, boolean isBold)}
 * だけを取っていたため、字送り（letter-spacing）と斜体が測定に入らず、
 * 「倍率」で渡されるフォントサイズは基準 16px という暗黙の約束に依存していた。
 * ここでは px そのものを運ぶ。
 */
public record FontStyle(float fontSizePx, boolean bold, boolean italic, float letterSpacingPx) {

    public static final FontStyle DEFAULT = new FontStyle(16.0f, false, false, 0.0f);

    public FontStyle withSize(float px) {
        return new FontStyle(px, bold, italic, letterSpacingPx);
    }

    public FontStyle withBold(boolean isBold) {
        return new FontStyle(fontSizePx, isBold, italic, letterSpacingPx);
    }
}
