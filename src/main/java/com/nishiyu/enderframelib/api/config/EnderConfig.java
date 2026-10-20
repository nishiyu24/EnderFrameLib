package com.nishiyu.enderframelib.api.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a configuration class (a POJO or a class with static fields) to automatically bind
 * it to an HTML5/CSS3 configuration screen and JSON persistence.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface EnderConfig {

    /**
     * Configuration file name (defaults to {@code <modId>.json} if omitted).
     */
    String fileName() default "";

    /**
     * Configuration screen title (translation key or literal text).
     */
    String title() default "";

    /**
     * Theme stylesheet to apply (e.g. {@code "enderframelib:dark.css"}, {@code "enderframelib:vanilla.css"},
     * {@code "enderframelib:brass.css"}, or any custom ResourceLocation).
     */
    String theme() default "enderframelib:dark.css";

    /**
     * Resource path to a custom HTML template for completely custom layouts (ResourceLocation string).
     */
    String customTemplate() default "";
}