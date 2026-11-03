package com.nishiyu.enderframelib.core.css;

import com.nishiyu.enderframelib.core.css.model.*;

import com.nishiyu.enderframelib.core.text.model.*;

import java.util.Locale;

/**
 * 算出スタイルでキーワードを値に持つプロパティの、解決後の値を表す型。
 *
 * enum の定数名は CSS のキーワードを大文字にして {@code -} を {@code _} にしたもの（{@code flex-start} は
 * {@code FLEX_START}）。
 */
public final class CssKeywords {

    private CssKeywords() {}

    /** CSS のキーワード 1 つ。 */
    public interface Keyword {
        /** CSS の書き方（小文字、{@code -} 区切り）。 */
        default String cssName() {
            return ((Enum<?>) this).name().toLowerCase(Locale.ROOT).replace('_', '-');
        }
    }

    /** 型ごとの「CSS の書き方（小文字・{@code -} 区切り）→ 定数」の表。型ごとに 1 回だけ作る（読み取り専用）。 */
    private static final ClassValue<java.util.Map<String, Enum<?>>> BY_CSS_NAME = new ClassValue<>() {
        @Override
        protected java.util.Map<String, Enum<?>> computeValue(Class<?> type) {
            java.util.Map<String, Enum<?>> m = new java.util.HashMap<>();
            for (Object c : type.getEnumConstants()) {
                Enum<?> e = (Enum<?>) c;
                m.put(e.name().toLowerCase(Locale.ROOT).replace('_', '-'), e);
            }
            return java.util.Collections.unmodifiableMap(m);
        }
    };

    /** 値の文字列を {@code type} の定数にする。前後の空白と大文字小文字は無視する。知らない値は null。 */
    @SuppressWarnings("unchecked")
    public static <E extends Enum<E> & Keyword> E parse(Class<E> type, String value) {
        if (value == null) return null;
        // 近道: すでに「小文字・英数字・区切りは単独の -」だけの普通の書き方なら、表を引くだけ（確保なし）。
        // 当たらなければ、空白・大文字・safe/unsafe などを含む書き方かもしれないので、従来の正規化で調べ直す
        if (isPlainKeyword(value)) {
            Enum<?> hit = BY_CSS_NAME.get(type).get(value);
            if (hit != null) return (E) hit;
        }
        return parseNormalized(type, value);
    }

    /** 小文字の英数字と、単独の {@code -}（先頭・末尾・連続でない）だけか。 */
    private static boolean isPlainKeyword(String v) {
        int n = v.length();
        if (n == 0) return false;
        char prev = '-';
        for (int i = 0; i < n; i++) {
            char c = v.charAt(i);
            boolean alnum = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
            if (c == '-') {
                if (prev == '-') return false; // 先頭か連続
            } else if (!alnum) {
                return false;
            }
            prev = c;
        }
        return prev != '-';
    }

    private static final java.util.regex.Pattern SEPARATORS = java.util.regex.Pattern.compile("[-\\s]+");

    private static <E extends Enum<E> & Keyword> E parseNormalized(Class<E> type, String value) {
        String text = value.trim().toLowerCase(Locale.ROOT);
        // 揃えの safe / unsafe は位置の扱いの違いで、レイアウトは区別しない（safe center は center）
        if (text.startsWith("safe ")) text = text.substring(5).trim();
        else if (text.startsWith("unsafe ")) text = text.substring(7).trim();
        String name = SEPARATORS.matcher(text.toUpperCase(Locale.ROOT)).replaceAll("_");
        for (E constant : type.getEnumConstants()) {
            if (constant.name().equals(name)) return constant;
        }
        return null;
    }

    public enum Position implements Keyword { STATIC, RELATIVE, ABSOLUTE, FIXED, STICKY }

    /** {@code float}。 */
    public enum FloatSide implements Keyword { NONE, LEFT, RIGHT, INLINE_START, INLINE_END }

    public enum Clear implements Keyword { NONE, LEFT, RIGHT, BOTH, INLINE_START, INLINE_END }

    public enum BoxSizing implements Keyword { CONTENT_BOX, BORDER_BOX }


    public enum Visibility implements Keyword { VISIBLE, HIDDEN, COLLAPSE }

    public enum TextTransform implements Keyword { NONE, CAPITALIZE, UPPERCASE, LOWERCASE }

    public enum TextOverflow implements Keyword { CLIP, ELLIPSIS }

    public enum OverflowWrap implements Keyword { NORMAL, ANYWHERE, BREAK_WORD }

    public enum FontStyle implements Keyword { NORMAL, ITALIC, OBLIQUE }

    public enum FlexDirection implements Keyword {
        ROW, ROW_REVERSE, COLUMN, COLUMN_REVERSE;

        public boolean isRow() {
            return this == ROW || this == ROW_REVERSE;
        }

        public boolean isReverse() {
            return this == ROW_REVERSE || this == COLUMN_REVERSE;
        }
    }

    public enum FlexWrap implements Keyword { NOWRAP, WRAP, WRAP_REVERSE }

    /**
     * 揃え（{@code justify-content} / {@code align-content} / {@code align-items} / {@code align-self} と
     * グリッドの {@code justify-*}）。軸ごとに使えない値は読み手が既定に倒す。
     */
    public enum Alignment implements Keyword {
        NORMAL, AUTO, STRETCH, CENTER, START, END, FLEX_START, FLEX_END, SELF_START, SELF_END, LEFT, RIGHT,
        BASELINE, FIRST_BASELINE, LAST_BASELINE, SPACE_BETWEEN, SPACE_AROUND, SPACE_EVENLY, LEGACY
    }


    public enum BorderCollapse implements Keyword { SEPARATE, COLLAPSE }

    public enum TableLayout implements Keyword { AUTO, FIXED }

    public enum CaptionSide implements Keyword { TOP, BOTTOM }

    public enum ListStylePosition implements Keyword { OUTSIDE, INSIDE }

    public enum PointerEvents implements Keyword { AUTO, NONE }

    public enum Isolation implements Keyword { AUTO, ISOLATE }

    public enum ScrollbarWidth implements Keyword { AUTO, THIN, NONE }

    /** {@code content-visibility}。{@code hidden} は中身を描かず測らない（size / layout / paint containment を伴う）。 */
    public enum ContentVisibility implements Keyword { VISIBLE, AUTO, HIDDEN }

    /** {@code container-type}。{@code size} は両軸、{@code inline-size} は横軸の containment を伴う。 */
    public enum ContainerType implements Keyword {
        NORMAL, SIZE, INLINE_SIZE;

        /** 横軸の寸法が内容で決まらない（containment がある）か。 */
        public boolean containsInlineSize() {
            return this != NORMAL;
        }

        /** 縦軸の寸法が内容で決まらないか。 */
        public boolean containsBlockSize() {
            return this == SIZE;
        }
    }
}
