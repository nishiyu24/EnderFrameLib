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
 * @param svgSource              画像としての SVG の取得（{@code PaintServices.svg}）。null なら {@code data:} の SVG だけ
 * @param fontResolver           SVG 内の文字の書体解決。null ならシステムフォント
 * @param pixelSizeFallback      {@code border-image} 用の画素数の既定。null なら無し
 * @param svgResourceReader      SVG のリソース（{@code ns:path/icon.svg}）の読み手。null なら読まない
 * @param svgHttpSource          {@code http(s)} の SVG の非同期な取得元。null なら無し
 * @param fontReader             {@code @font-face} の出所の読み手。null なら読まない
 * @param animationStateStore    CSS アニメーションの補間状態の置き場。null ならプロセス内の既定
 * @param parseLimits            HTML 解析の既定の上限。null なら {@code ParseLimits.DEFAULT}
 * @param glyphFontResolver      {@code ch} / {@code ex} の測定に使う書体の解決。null なら代替の 0.5em
 * @param replacedElements       ホストが登録した置換要素（タグ名 → 採寸・描画の差し込み）。null なら無し
 * @param localEnvBase           ローカル環境変数（{@code @lang} 等）の基本の供給元。null なら既定値
 * @param localEnvVariables      自前のローカル環境変数（キーは {@code @} 無し）。null なら無し
 * @param syntaxExtras           属性の別名・Markdown ブロックのタグの追加（SPI の宣言に足す。{@code HostSyntax}）。null なら追加なし
 * @param tagMacros              タグマクロの追加（キーは小文字。SPI の宣言に足す。{@code CustomTagExpander}）。null なら無し
 * @param imageDecoder           圧縮画像（PNG / JPEG / GIF など）の復号器。null なら標準（JDK の ImageIO 版）
 */
public record HostServices(
        Function<String, String> i18nResolver,
        BiFunction<String, Object[], String> i18nFormatter,
        Function<String, List<Map<String, String>>> jsonListParser,
        IntrinsicSizeResolver intrinsicSizeResolver,
        ILayoutBoxRenderer boxRenderer,
        Function<String, String> resourceTextResolver,
        Function<String, String> clientResourceFallback,
        Function<String, String> datapackResourceFallback,
        Function<String, com.nishiyu.enderframelib.core.raster.svg.SvgImage> svgSource,
        com.nishiyu.enderframelib.core.text.model.FontResolver fontResolver,
        IntrinsicSizeResolver pixelSizeFallback,
        com.nishiyu.enderframelib.core.raster.svg.SvgSources.ResourceReader svgResourceReader,
        Function<String, com.nishiyu.enderframelib.core.raster.svg.SvgImage> svgHttpSource,
        com.nishiyu.enderframelib.core.text.font.FontFaceRules.FontReader fontReader,
        com.nishiyu.enderframelib.core.paint.CssAnimator.StateStore animationStateStore,
        com.nishiyu.enderframelib.core.dom.parser.ParseLimits parseLimits,
        com.nishiyu.enderframelib.core.text.model.FontResolver glyphFontResolver,
        Map<String, com.nishiyu.enderframelib.core.dom.CustomElementPainter> replacedElements,
        java.util.function.Supplier<Map<String, String>> localEnvBase,
        Map<String, java.util.function.Supplier<String>> localEnvVariables,
        com.nishiyu.enderframelib.core.dom.HostSyntax.Extras syntaxExtras,
        Map<String, com.nishiyu.enderframelib.core.dom.node.TagMacro> tagMacros,
        com.nishiyu.enderframelib.core.raster.ImageDecoder imageDecoder)
        implements com.nishiyu.enderframelib.core.paint.PaintServices.Source,
                com.nishiyu.enderframelib.core.raster.svg.SvgSources.Source,
                com.nishiyu.enderframelib.core.text.font.FontFaceRules.Source,
                com.nishiyu.enderframelib.core.paint.CssAnimator.Source,
                com.nishiyu.enderframelib.core.dom.parser.HtmlParser.Source,
                com.nishiyu.enderframelib.core.css.GlyphUnits.Source,
                com.nishiyu.enderframelib.core.layout.ReplacedElements.Source,
                com.nishiyu.enderframelib.core.dom.HostSyntax.Source,
                com.nishiyu.enderframelib.core.raster.ImageDecoder.Source {

    /** 何も差し込んでいない既定（翻訳はキーのまま・描画は {@link ILayoutBoxRenderer#DEFAULT}・ほかは無し）。 */
    public static final HostServices DEFAULTS = new HostServices(key -> key, (key, args) -> key, null, null, ILayoutBoxRenderer.DEFAULT, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

    public HostServices withI18nResolver(Function<String, String> v) {
        return new HostServices(v, i18nFormatter, jsonListParser, intrinsicSizeResolver, boxRenderer, resourceTextResolver, clientResourceFallback, datapackResourceFallback, svgSource, fontResolver, pixelSizeFallback, svgResourceReader, svgHttpSource, fontReader, animationStateStore, parseLimits, glyphFontResolver, replacedElements, localEnvBase, localEnvVariables, syntaxExtras, tagMacros, imageDecoder);
    }

    public HostServices withI18nFormatter(BiFunction<String, Object[], String> v) {
        return new HostServices(i18nResolver, v, jsonListParser, intrinsicSizeResolver, boxRenderer, resourceTextResolver, clientResourceFallback, datapackResourceFallback, svgSource, fontResolver, pixelSizeFallback, svgResourceReader, svgHttpSource, fontReader, animationStateStore, parseLimits, glyphFontResolver, replacedElements, localEnvBase, localEnvVariables, syntaxExtras, tagMacros, imageDecoder);
    }

    public HostServices withJsonListParser(Function<String, List<Map<String, String>>> v) {
        return new HostServices(i18nResolver, i18nFormatter, v, intrinsicSizeResolver, boxRenderer, resourceTextResolver, clientResourceFallback, datapackResourceFallback, svgSource, fontResolver, pixelSizeFallback, svgResourceReader, svgHttpSource, fontReader, animationStateStore, parseLimits, glyphFontResolver, replacedElements, localEnvBase, localEnvVariables, syntaxExtras, tagMacros, imageDecoder);
    }

    public HostServices withIntrinsicSizeResolver(IntrinsicSizeResolver v) {
        return new HostServices(i18nResolver, i18nFormatter, jsonListParser, v, boxRenderer, resourceTextResolver, clientResourceFallback, datapackResourceFallback, svgSource, fontResolver, pixelSizeFallback, svgResourceReader, svgHttpSource, fontReader, animationStateStore, parseLimits, glyphFontResolver, replacedElements, localEnvBase, localEnvVariables, syntaxExtras, tagMacros, imageDecoder);
    }

    public HostServices withBoxRenderer(ILayoutBoxRenderer v) {
        return new HostServices(i18nResolver, i18nFormatter, jsonListParser, intrinsicSizeResolver, v, resourceTextResolver, clientResourceFallback, datapackResourceFallback, svgSource, fontResolver, pixelSizeFallback, svgResourceReader, svgHttpSource, fontReader, animationStateStore, parseLimits, glyphFontResolver, replacedElements, localEnvBase, localEnvVariables, syntaxExtras, tagMacros, imageDecoder);
    }

    public HostServices withResourceTextResolver(Function<String, String> v) {
        return new HostServices(i18nResolver, i18nFormatter, jsonListParser, intrinsicSizeResolver, boxRenderer, v, clientResourceFallback, datapackResourceFallback, svgSource, fontResolver, pixelSizeFallback, svgResourceReader, svgHttpSource, fontReader, animationStateStore, parseLimits, glyphFontResolver, replacedElements, localEnvBase, localEnvVariables, syntaxExtras, tagMacros, imageDecoder);
    }

    public HostServices withClientResourceFallback(Function<String, String> v) {
        return new HostServices(i18nResolver, i18nFormatter, jsonListParser, intrinsicSizeResolver, boxRenderer, resourceTextResolver, v, datapackResourceFallback, svgSource, fontResolver, pixelSizeFallback, svgResourceReader, svgHttpSource, fontReader, animationStateStore, parseLimits, glyphFontResolver, replacedElements, localEnvBase, localEnvVariables, syntaxExtras, tagMacros, imageDecoder);
    }

    public HostServices withDatapackResourceFallback(Function<String, String> v) {
        return new HostServices(i18nResolver, i18nFormatter, jsonListParser, intrinsicSizeResolver, boxRenderer, resourceTextResolver, clientResourceFallback, v, svgSource, fontResolver, pixelSizeFallback, svgResourceReader, svgHttpSource, fontReader, animationStateStore, parseLimits, glyphFontResolver, replacedElements, localEnvBase, localEnvVariables, syntaxExtras, tagMacros, imageDecoder);
    }

    public HostServices withSvgSource(Function<String, com.nishiyu.enderframelib.core.raster.svg.SvgImage> v) {
        return new HostServices(i18nResolver, i18nFormatter, jsonListParser, intrinsicSizeResolver, boxRenderer, resourceTextResolver, clientResourceFallback, datapackResourceFallback, v, fontResolver, pixelSizeFallback, svgResourceReader, svgHttpSource, fontReader, animationStateStore, parseLimits, glyphFontResolver, replacedElements, localEnvBase, localEnvVariables, syntaxExtras, tagMacros, imageDecoder);
    }

    public HostServices withFontResolver(com.nishiyu.enderframelib.core.text.model.FontResolver v) {
        return new HostServices(i18nResolver, i18nFormatter, jsonListParser, intrinsicSizeResolver, boxRenderer, resourceTextResolver, clientResourceFallback, datapackResourceFallback, svgSource, v, pixelSizeFallback, svgResourceReader, svgHttpSource, fontReader, animationStateStore, parseLimits, glyphFontResolver, replacedElements, localEnvBase, localEnvVariables, syntaxExtras, tagMacros, imageDecoder);
    }

    public HostServices withPixelSizeFallback(IntrinsicSizeResolver v) {
        return new HostServices(i18nResolver, i18nFormatter, jsonListParser, intrinsicSizeResolver, boxRenderer, resourceTextResolver, clientResourceFallback, datapackResourceFallback, svgSource, fontResolver, v, svgResourceReader, svgHttpSource, fontReader, animationStateStore, parseLimits, glyphFontResolver, replacedElements, localEnvBase, localEnvVariables, syntaxExtras, tagMacros, imageDecoder);
    }

    public HostServices withSvgResourceReader(com.nishiyu.enderframelib.core.raster.svg.SvgSources.ResourceReader v) {
        return new HostServices(i18nResolver, i18nFormatter, jsonListParser, intrinsicSizeResolver, boxRenderer, resourceTextResolver, clientResourceFallback, datapackResourceFallback, svgSource, fontResolver, pixelSizeFallback, v, svgHttpSource, fontReader, animationStateStore, parseLimits, glyphFontResolver, replacedElements, localEnvBase, localEnvVariables, syntaxExtras, tagMacros, imageDecoder);
    }

    public HostServices withSvgHttpSource(Function<String, com.nishiyu.enderframelib.core.raster.svg.SvgImage> v) {
        return new HostServices(i18nResolver, i18nFormatter, jsonListParser, intrinsicSizeResolver, boxRenderer, resourceTextResolver, clientResourceFallback, datapackResourceFallback, svgSource, fontResolver, pixelSizeFallback, svgResourceReader, v, fontReader, animationStateStore, parseLimits, glyphFontResolver, replacedElements, localEnvBase, localEnvVariables, syntaxExtras, tagMacros, imageDecoder);
    }

    public HostServices withFontReader(com.nishiyu.enderframelib.core.text.font.FontFaceRules.FontReader v) {
        return new HostServices(i18nResolver, i18nFormatter, jsonListParser, intrinsicSizeResolver, boxRenderer, resourceTextResolver, clientResourceFallback, datapackResourceFallback, svgSource, fontResolver, pixelSizeFallback, svgResourceReader, svgHttpSource, v, animationStateStore, parseLimits, glyphFontResolver, replacedElements, localEnvBase, localEnvVariables, syntaxExtras, tagMacros, imageDecoder);
    }

    public HostServices withAnimationStateStore(com.nishiyu.enderframelib.core.paint.CssAnimator.StateStore v) {
        return new HostServices(i18nResolver, i18nFormatter, jsonListParser, intrinsicSizeResolver, boxRenderer, resourceTextResolver, clientResourceFallback, datapackResourceFallback, svgSource, fontResolver, pixelSizeFallback, svgResourceReader, svgHttpSource, fontReader, v, parseLimits, glyphFontResolver, replacedElements, localEnvBase, localEnvVariables, syntaxExtras, tagMacros, imageDecoder);
    }

    public HostServices withParseLimits(com.nishiyu.enderframelib.core.dom.parser.ParseLimits v) {
        return new HostServices(i18nResolver, i18nFormatter, jsonListParser, intrinsicSizeResolver, boxRenderer, resourceTextResolver, clientResourceFallback, datapackResourceFallback, svgSource, fontResolver, pixelSizeFallback, svgResourceReader, svgHttpSource, fontReader, animationStateStore, v, glyphFontResolver, replacedElements, localEnvBase, localEnvVariables, syntaxExtras, tagMacros, imageDecoder);
    }

    public HostServices withGlyphFontResolver(com.nishiyu.enderframelib.core.text.model.FontResolver v) {
        return new HostServices(i18nResolver, i18nFormatter, jsonListParser, intrinsicSizeResolver, boxRenderer, resourceTextResolver, clientResourceFallback, datapackResourceFallback, svgSource, fontResolver, pixelSizeFallback, svgResourceReader, svgHttpSource, fontReader, animationStateStore, parseLimits, v, replacedElements, localEnvBase, localEnvVariables, syntaxExtras, tagMacros, imageDecoder);
    }

    public HostServices withReplacedElements(Map<String, com.nishiyu.enderframelib.core.dom.CustomElementPainter> v) {
        return new HostServices(i18nResolver, i18nFormatter, jsonListParser, intrinsicSizeResolver, boxRenderer, resourceTextResolver, clientResourceFallback, datapackResourceFallback, svgSource, fontResolver, pixelSizeFallback, svgResourceReader, svgHttpSource, fontReader, animationStateStore, parseLimits, glyphFontResolver, v, localEnvBase, localEnvVariables, syntaxExtras, tagMacros, imageDecoder);
    }

    public HostServices withLocalEnvBase(java.util.function.Supplier<Map<String, String>> v) {
        return new HostServices(i18nResolver, i18nFormatter, jsonListParser, intrinsicSizeResolver, boxRenderer, resourceTextResolver, clientResourceFallback, datapackResourceFallback, svgSource, fontResolver, pixelSizeFallback, svgResourceReader, svgHttpSource, fontReader, animationStateStore, parseLimits, glyphFontResolver, replacedElements, v, localEnvVariables, syntaxExtras, tagMacros, imageDecoder);
    }

    public HostServices withLocalEnvVariables(Map<String, java.util.function.Supplier<String>> v) {
        return new HostServices(i18nResolver, i18nFormatter, jsonListParser, intrinsicSizeResolver, boxRenderer, resourceTextResolver, clientResourceFallback, datapackResourceFallback, svgSource, fontResolver, pixelSizeFallback, svgResourceReader, svgHttpSource, fontReader, animationStateStore, parseLimits, glyphFontResolver, replacedElements, localEnvBase, v, syntaxExtras, tagMacros, imageDecoder);
    }

    /** ホスト置換要素を 1 つ足す（同名は上書き。他の面の登録は変えない）。 */
    public HostServices withReplacedElement(String tag, com.nishiyu.enderframelib.core.dom.CustomElementPainter painter) {
        if (tag == null || tag.isEmpty() || painter == null || painter.kind() == null) {
            throw new IllegalArgumentException("tag/painter");
        }
        Map<String, com.nishiyu.enderframelib.core.dom.CustomElementPainter> m = new java.util.HashMap<>(replacedElements == null ? Map.of() : replacedElements);
        m.put(tag, painter);
        return withReplacedElements(Map.copyOf(m));
    }

    /** 自前のローカル環境変数を 1 つ足す（キーの先頭の {@code @} は有っても無くてもよい）。 */
    public HostServices withLocalEnvVariable(String key, java.util.function.Supplier<String> supplier) {
        String clean = key.startsWith("@") ? key.substring(1) : key;
        Map<String, java.util.function.Supplier<String>> m = new java.util.HashMap<>(localEnvVariables == null ? Map.of() : localEnvVariables);
        m.put(clean, supplier);
        return withLocalEnvVariables(Map.copyOf(m));
    }

    public HostServices withSyntaxExtras(com.nishiyu.enderframelib.core.dom.HostSyntax.Extras v) {
        return new HostServices(i18nResolver, i18nFormatter, jsonListParser, intrinsicSizeResolver, boxRenderer, resourceTextResolver, clientResourceFallback, datapackResourceFallback, svgSource, fontResolver, pixelSizeFallback, svgResourceReader, svgHttpSource, fontReader, animationStateStore, parseLimits, glyphFontResolver, replacedElements, localEnvBase, localEnvVariables, v, tagMacros, imageDecoder);
    }

    public HostServices withTagMacros(Map<String, com.nishiyu.enderframelib.core.dom.node.TagMacro> v) {
        return new HostServices(i18nResolver, i18nFormatter, jsonListParser, intrinsicSizeResolver, boxRenderer, resourceTextResolver, clientResourceFallback, datapackResourceFallback, svgSource, fontResolver, pixelSizeFallback, svgResourceReader, svgHttpSource, fontReader, animationStateStore, parseLimits, glyphFontResolver, replacedElements, localEnvBase, localEnvVariables, syntaxExtras, v, imageDecoder);
    }

    /** タグマクロを 1 つ足す（同名は上書き。タグ名は小文字へ寄せる。他の面の登録は変えない）。 */
    public HostServices withTagMacro(String tag, com.nishiyu.enderframelib.core.dom.node.TagMacro macro) {
        if (tag == null || tag.isEmpty() || macro == null) throw new IllegalArgumentException("tag/macro");
        Map<String, com.nishiyu.enderframelib.core.dom.node.TagMacro> m = new java.util.HashMap<>(tagMacros == null ? Map.of() : tagMacros);
        m.put(tag.toLowerCase(java.util.Locale.ROOT), macro);
        return withTagMacros(Map.copyOf(m));
    }

    /** 属性の別名を 1 つ足す（他の面の登録は変えない）。 */
    public HostServices withAttributeAlias(String alias, String canonical) {
        return withSyntaxExtras((syntaxExtras == null ? com.nishiyu.enderframelib.core.dom.HostSyntax.Extras.NONE : syntaxExtras).withAttributeAlias(alias, canonical));
    }

    /** Markdown ブロックのタグを 1 つ足す。 */
    public HostServices withMarkdownTag(String tag) {
        return withSyntaxExtras((syntaxExtras == null ? com.nishiyu.enderframelib.core.dom.HostSyntax.Extras.NONE : syntaxExtras).withMarkdownTag(tag));
    }

    public HostServices withImageDecoder(com.nishiyu.enderframelib.core.raster.ImageDecoder v) {
        return new HostServices(i18nResolver, i18nFormatter, jsonListParser, intrinsicSizeResolver, boxRenderer, resourceTextResolver, clientResourceFallback, datapackResourceFallback, svgSource, fontResolver, pixelSizeFallback, svgResourceReader, svgHttpSource, fontReader, animationStateStore, parseLimits, glyphFontResolver, replacedElements, localEnvBase, localEnvVariables, syntaxExtras, tagMacros, v);
    }
}
