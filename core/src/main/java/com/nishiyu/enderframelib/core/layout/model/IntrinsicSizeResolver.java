package com.nishiyu.enderframelib.core.layout.model;

import com.nishiyu.enderframelib.core.layout.*;
import com.nishiyu.enderframelib.core.layout.model.*;

/**
 * 置換要素の固有サイズ（画像やエンティティなど）を解決するためのインターフェース。
 * core パッケージが Minecraft やサーバー側のリソース状態に直接依存することを防ぎます。
 */
public interface IntrinsicSizeResolver {
    int[] getSize(String src);
}
