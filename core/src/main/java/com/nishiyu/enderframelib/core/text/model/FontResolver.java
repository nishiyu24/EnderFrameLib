package com.nishiyu.enderframelib.core.text.model;


/**
 * {@code font-family} から書体を引く。
 */
public interface FontResolver {

    /** {@code null} や未知のファミリ名には既定の書体を返す（例外にしない）。 */
    FontFace resolve(String fontFamily);

    /**
     * 解決の結果が変わりうるたびに変わる世代。同じ世代の間は、同じ {@code fontFamily} に同じ実体の {@link FontFace} を返すこと。
     * 負なら結果を覚えてはいけない（既定）。0 以上を返す解決器の結果は、算出スタイルが覚えて引き直しを省く
     * （{@code LayoutContext#faceFor}。文字を測るたびに {@code font-family} の文字列で表を引く費用が、全行の測り直しで効く）。
     */
    default long resolveGeneration() {
        return -1;
    }

    /** ヘッドレステスト用。常に {@link FontFace#FALLBACK}。 */
    FontResolver FALLBACK = fontFamily -> FontFace.FALLBACK;
}
