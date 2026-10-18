package com.nishiyu.enderframelib.core.text;

/**
 * {@code font-family} から書体を引く。
 *
 * 何が壊れていたか: レイアウトは常に Minecraft の既定フォントで測るのに、
 * 描画は {@code font-family} で別のフォントを選んでいたため、
 * {@code @font-face} を当てたテキストは折り返し・中央寄せ・省略が全部ずれていた。
 * 測定も描画もこの 1 本を通す。
 */
public interface FontResolver {

    /** {@code null} や未知のファミリ名には既定の書体を返す（例外にしない）。 */
    FontFace resolve(String fontFamily);

    /** ヘッドレステスト用。常に {@link FontFace#FALLBACK}。 */
    FontResolver FALLBACK = fontFamily -> FontFace.FALLBACK;
}
