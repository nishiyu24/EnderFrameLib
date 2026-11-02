package com.nishiyu.enderframelib.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * jar に同梱した CSS リソースの読み込み。
 *
 * UA スタイルシート・組み込みテーマ・開発キットのプレビュー CSS は、以前
 * Java の文字列リテラルとして UiDocumentCompiler と WebDevKitExporter に重複して埋め込まれていた
 * （focus-visible の宣言は 4 箇所にコピーがあった）。現在はリソースが単一の出所で、
 * ここが唯一の読み込み口。
 */
public final class CssResources {

    private static final Logger LOGGER = LoggerFactory.getLogger(CssResources.class);

    /** UA スタイルシート。 */
    public static final String USER_AGENT = "/assets/enderframelib/css/ua.css";
    /** 開発キットのプレビュー用スタイルシート（ブラウザで開く HTML が参照する）。 */
    public static final String DEV_KIT = "/assets/enderframelib/preview/enderframe-devkit.css";

    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

    private CssResources() {}

    /** 組み込みテーマのリソースパス。 */
    public static String themePath(String name) {
        return "/assets/enderframelib/css/theme/" + name + ".css";
    }

    /**
     * リソースを読み込む（結果はキャッシュされる）。
     * 見つからない場合は空文字列を返し、警告を 1 度だけ出す。
     */
    public static String load(String resourcePath) {
        return CACHE.computeIfAbsent(resourcePath, CssResources::read);
    }

    private static String read(String resourcePath) {
        try (InputStream is = CssResources.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                LOGGER.warn("CSS リソースが見つかりません: {}", resourcePath);
                return "";
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("CSS リソースの読み込みに失敗しました: {}", resourcePath, e);
            return "";
        }
    }
}
