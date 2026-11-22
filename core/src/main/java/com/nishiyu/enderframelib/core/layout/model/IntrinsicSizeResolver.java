package com.nishiyu.enderframelib.core.layout.model;


/**
 * 置換要素の固有サイズ（画像やエンティティなど）を解決するためのインターフェース。
 * core パッケージが Minecraft やサーバー側のリソース状態に直接依存することを防ぎます。
 */
public interface IntrinsicSizeResolver {
    int[] getSize(String src);

    /** 固有サイズ解決の結果が変わりうるたびに進む世代。同じ世代の間は同じ src に同じサイズを返す。 */
    default long resolveGeneration() {
        return 0L;
    }
}
