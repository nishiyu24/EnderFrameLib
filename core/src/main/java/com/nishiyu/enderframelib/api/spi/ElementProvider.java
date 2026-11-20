package com.nishiyu.enderframelib.api.spi;

import java.util.Map;
import java.util.Set;

/**
 * ホストが独自タグ・テンプレート構文をエンジンへ教える SPI。{@link java.util.ServiceLoader} で探されるので、
 * ホストは {@code META-INF/services/com.nishiyu.enderframelib.api.spi.ElementProvider} に実装クラス名を書く。
 *
 * <p>実装は値を返すだけにする（エンジンの静的初期化から呼ばれるため、他のエンジン API を呼んではならない）。
 * 署名は JDK と {@code api.*} の型だけで、エンジンの内部パッケージには触れない。
 */
public interface ElementProvider {

    /** 置換要素として登録するタグ（小文字）と、その採寸・描画の差し込み。 */
    default Map<String, ElementPainter> replacedElements() {
        return Map.of();
    }

    /** 展開するタグ（小文字）と、その展開規則。 */
    default Map<String, TagTemplate> tagTemplates() {
        return Map.of();
    }

    /** 属性名の別名（別名 → 正規名。例: {@code x-if} → {@code data-if}）。小文字で返す。 */
    default Map<String, String> attributeAliases() {
        return Map.of();
    }

    /** Markdown ブロックとして展開する独自タグ。小文字で返す。 */
    default Set<String> markdownTags() {
        return Set.of();
    }

    /** 閉じタグを持たない（空要素として解析する）独自タグ。小文字で返す。 */
    default Set<String> voidElements() {
        return Set.of();
    }
}
