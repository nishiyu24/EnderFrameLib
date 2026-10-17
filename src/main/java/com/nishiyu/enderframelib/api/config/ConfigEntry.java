package com.nishiyu.enderframelib.api.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 設定フィールドに付与し、HTML5標準フォーム要素（{@code <input>}, {@code <select>}, {@code <fieldset>} 等）
 * およびバリデーション属性（{@code min}, {@code max}, {@code step}, {@code pattern}, {@code placeholder}）へマッピングするアノテーション。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface ConfigEntry {
    /**
     * カテゴリ（HTMLの {@code <details><summary>} またはタブとしてグループ化される）
     */
    String category() default "General";

    /**
     * 表示ラベル（空の場合はフィールド名から自動整形、または翻訳キー）
     */
    String label() default "";

    /**
     * 説明コメント（HTMLの {@code title} ツールチップおよび説明テキストとして出力）
     */
    String description() default "";

    /**
     * 数値型の最小値（HTML5 {@code min} 属性）
     */
    double min() default -Double.MAX_VALUE;

    /**
     * 数値型の最大値（HTML5 {@code max} 属性）
     */
    double max() default Double.MAX_VALUE;

    /**
     * 数値型の刻み幅（HTML5 {@code step} 属性）
     */
    double step() default 1.0;

    /**
     * true の場合、数値フィールドを {@code <input type="range">} スライダーとして描画する
     */
    boolean slider() default false;

    /**
     * true の場合、文字列や整数カラーを {@code <input type="color">} カラーピッカーとして描画する
     */
    boolean colorPicker() default false;

    /**
     * テキスト入力時の {@code placeholder} 属性
     */
    String placeholder() default "";

    /**
     * 正規表現による入力検証（HTML5 {@code pattern} 属性）
     */
    String pattern() default "";

    /**
     * Stringフィールドに対して選択肢を制限する場合のオプション一覧（{@code <select><option>} を生成）
     */
    String[] options() default {};

    /**
     * 変更時にMinecraft再起動が必要かどうかを表示するフラグ
     */
    boolean requiresRestart() default false;
}