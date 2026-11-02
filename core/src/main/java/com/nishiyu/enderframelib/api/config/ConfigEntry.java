package com.nishiyu.enderframelib.api.config;


import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a configuration field to map it to HTML5 standard form elements
 * ({@code <input>}, {@code <select>}, {@code <fieldset>}, etc.) and validation attributes
 * ({@code min}, {@code max}, {@code step}, {@code pattern}, {@code placeholder}).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface ConfigEntry {

    /**
     * Category name for grouping (rendered as HTML {@code <details><summary>} or tabs).
     */
    String category() default "General";

    /**
     * Display label (auto-generated from field name if empty, or a translation key).
     */
    String label() default "";

    /**
     * Description comment (emitted as HTML {@code title} tooltip and explanatory text).
     */
    String description() default "";

    /**
     * Minimum value for numeric types (HTML5 {@code min} attribute).
     */
    double min() default -Double.MAX_VALUE;

    /**
     * Maximum value for numeric types (HTML5 {@code max} attribute).
     */
    double max() default Double.MAX_VALUE;

    /**
     * Step value for numeric types (HTML5 {@code step} attribute).
     */
    double step() default 1.0;

    /**
     * When {@code true}, numeric fields are rendered as an {@code <input type="range">} slider.
     */
    boolean slider() default false;

    /**
     * When {@code true}, string or integer color fields are rendered as an {@code <input type="color">} color picker.
     */
    boolean colorPicker() default false;

    /**
     * Placeholder text for text inputs (HTML5 {@code placeholder} attribute).
     */
    String placeholder() default "";

    /**
     * Regular expression pattern for input validation (HTML5 {@code pattern} attribute).
     */
    String pattern() default "";

    /**
     * Allowed options for String fields (generates a {@code <select>} dropdown with {@code <option>} entries).
     */
    String[] options() default {};

    /**
     * Flag indicating whether changing this value requires restarting Minecraft.
     */
    boolean requiresRestart() default false;
}