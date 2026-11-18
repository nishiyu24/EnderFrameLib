package com.nishiyu.enderframelib.core.paint.software;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * B12: パスとして読めない画像の名前（ホストの描き手が無い面の {@code item:minecraft:beacon}。Windows の {@code Path.of} は
 * {@code ':'} で投げる。NUL はどの OS でも投げる）は「無い」と覚える。以前は覚えずに、描くたび（毎フレーム）例外を作って読み直していた。
 */
class UnreadableImageSourceCacheTest {

    @Test
    void sourceThatCannotBeAPathIsRememberedAsMissing() {
        ImageProvider.Standard p = ImageProvider.Standard.INSTANCE;
        String src = "item:minecraft:beacon\u0000" + System.nanoTime(); // NUL で、OS に依らずパスにできない
        assertNull(p.load(src, 16, 16), "読めない名前は画像にならない");
        assertTrue(p.remembers(src), "読めない名前の結果を覚える（次のフレームで読み直さない）");
        assertNull(p.load(src, 16, 16), "2 回目も同じ答え");
    }
}
