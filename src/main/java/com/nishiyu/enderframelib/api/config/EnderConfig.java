package com.nishiyu.enderframelib.api.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 設定クラス（POJOまたはstaticフィールドを持つクラス）に付与し、
 * Web標準フォーム要素（HTML5/CSS3）で構成される設定画面とJSON永続化を自動構成するアノテーション。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface EnderConfig {
    /**
     * 設定ファイル名（省略時は modId 名の .json）
     */
    String fileName() default "";

    /**
     * 設定画面のタイトル（翻訳キーまたはリテラル文字列）
     */
    String title() default "";

    /**
     * 適用するテーマCSS（"enderframelib:dark.css", "enderframelib:vanilla.css", "enderframelib:brass.css" または任意のResourceLocation）
     */
    String theme() default "enderframelib:dark.css";

    /**
     * Web標準で完全にレイアウトを自作したい場合のカスタムHTMLテンプレートパス（ResourceLocation文字列）
     */
    String customTemplate() default "";
}