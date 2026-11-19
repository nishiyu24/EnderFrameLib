package com.nishiyu.enderframelib.core;

import com.nishiyu.enderframelib.core.layout.model.IntrinsicSizeResolver;
import com.nishiyu.enderframelib.core.paint.ILayoutBoxRenderer;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * ホストがエンジンへ差し込む口（ENGINE_REARCHITECTURE_PLAN S1 ②）: 翻訳・JSON リストの解析・画像などの固有サイズ・箱の木の描画関数・リソースの本文。
 * 不変の値で、{@link EngineContext#updateHost} が丸ごと差し替える（読む側は 1 回読んだ値を使い続けてよく、途中で一部だけ変わった状態を見ない）。
 *
 * @param i18nResolver           {@code {{i18n:key}}} などの翻訳。既定はキーをそのまま返す
 * @param i18nFormatter          引数つきの翻訳（{@code {{i18n:key, arg…}}}）
 * @param jsonListParser         {@code data-for} の JSON 配列の解析。null なら解析しない
 * @param intrinsicSizeResolver  画像などの固有サイズの解決。null なら解決しない
 * @param boxRenderer            箱の木から表示リストを作る関数。null なら何も描かない。既定は {@link ILayoutBoxRenderer#DEFAULT}
 * @param resourceTextResolver   リソース（{@code <link>} / {@code @import} / テンプレート）の本文を引く関数。null なら既定の解決
 *                               （組み込みテーマ → データパック → クライアント → クラスパス）。ホストが全体を差し替えるときに使う
 * @param clientResourceFallback クライアント側リソース（リソースパックなど）の本文を引く。null なら引かない
 * @param datapackResourceFallback サーバー側リソース（データパック）の本文を引く。null なら引かない
 */
public record HostServices(
        Function<String, String> i18nResolver,
        BiFunction<String, Object[], String> i18nFormatter,
        Function<String, List<Map<String, String>>> jsonListParser,
        IntrinsicSizeResolver intrinsicSizeResolver,
        ILayoutBoxRenderer boxRenderer,
        Function<String, String> resourceTextResolver,
        Function<String, String> clientResourceFallback,
        Function<String, String> datapackResourceFallback) {

    /** 何も差し込んでいない既定（翻訳はキーのまま・描画は {@link ILayoutBoxRenderer#DEFAULT}・ほかは無し）。 */
    public static final HostServices DEFAULTS = new HostServices(key -> key, (key, args) -> key, null, null, ILayoutBoxRenderer.DEFAULT, null, null, null);

    public HostServices withI18nResolver(Function<String, String> v) {
        return new HostServices(v, i18nFormatter, jsonListParser, intrinsicSizeResolver, boxRenderer, resourceTextResolver, clientResourceFallback, datapackResourceFallback);
    }

    public HostServices withI18nFormatter(BiFunction<String, Object[], String> v) {
        return new HostServices(i18nResolver, v, jsonListParser, intrinsicSizeResolver, boxRenderer, resourceTextResolver, clientResourceFallback, datapackResourceFallback);
    }

    public HostServices withJsonListParser(Function<String, List<Map<String, String>>> v) {
        return new HostServices(i18nResolver, i18nFormatter, v, intrinsicSizeResolver, boxRenderer, resourceTextResolver, clientResourceFallback, datapackResourceFallback);
    }

    public HostServices withIntrinsicSizeResolver(IntrinsicSizeResolver v) {
        return new HostServices(i18nResolver, i18nFormatter, jsonListParser, v, boxRenderer, resourceTextResolver, clientResourceFallback, datapackResourceFallback);
    }

    public HostServices withBoxRenderer(ILayoutBoxRenderer v) {
        return new HostServices(i18nResolver, i18nFormatter, jsonListParser, intrinsicSizeResolver, v, resourceTextResolver, clientResourceFallback, datapackResourceFallback);
    }

    public HostServices withResourceTextResolver(Function<String, String> v) {
        return new HostServices(i18nResolver, i18nFormatter, jsonListParser, intrinsicSizeResolver, boxRenderer, v, clientResourceFallback, datapackResourceFallback);
    }

    public HostServices withClientResourceFallback(Function<String, String> v) {
        return new HostServices(i18nResolver, i18nFormatter, jsonListParser, intrinsicSizeResolver, boxRenderer, resourceTextResolver, v, datapackResourceFallback);
    }

    public HostServices withDatapackResourceFallback(Function<String, String> v) {
        return new HostServices(i18nResolver, i18nFormatter, jsonListParser, intrinsicSizeResolver, boxRenderer, resourceTextResolver, clientResourceFallback, v);
    }
}
