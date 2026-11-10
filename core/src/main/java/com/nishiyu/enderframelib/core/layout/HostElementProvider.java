package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.dom.node.TagMacro;
import java.util.Map;
import java.util.Set;

/**
 * ホスト（Minecraft など）が持ち込む独自タグをエンジンへ教える SPI。{@link java.util.ServiceLoader} で探されるので、
 * ホストは {@code META-INF/services/com.nishiyu.enderframelib.core.layout.HostElementProvider} に実装クラス名を書く。
 *
 * <p>実装は値を返すだけにする（エンジンの静的初期化から呼ばれるため、他のエンジン API を呼んではならない）。
 * エンジン本体は具体的なタグ名（{@code mc-*}）を一切持たない。
 */
public interface HostElementProvider {

    /** 置換要素として登録するタグと、その採寸・描画の差し込み。 */
    default Map<String, CustomElementPainter> replacedElements() {
        return Map.of();
    }

    /** 展開マクロとして登録するタグ（小文字）と、その展開規則。{@code CustomTagExpander} が文書読み込み時に適用する。 */
    default Map<String, TagMacro> tagMacros() {
        return Map.of();
    }

    /**
     * ホストが持ち込む属性名の別名（別名 → 正規名。例: ホスト固有の {@code x-if} → {@code data-if}）。
     * テンプレート指令（{@code data-if} / {@code data-for}）の読み取りで正規名と同じに扱われる。小文字で返す。
     */
    default Map<String, String> attributeAliases() {
        return Map.of();
    }

    /** Markdown ブロックとして展開するホスト独自タグ（{@code <article>} に変換される）。小文字で返す。 */
    default Set<String> markdownTags() {
        return Set.of();
    }

    /** 閉じタグを持たない（空要素として解析する）ホスト独自タグ。小文字で返す。 */
    default Set<String> voidElements() {
        return Set.of();
    }
}
