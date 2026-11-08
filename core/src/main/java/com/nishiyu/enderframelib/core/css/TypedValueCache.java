package com.nishiyu.enderframelib.core.css;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 宣言の文字列 → 型付きの値（長さ・色・数値）の、<b>全スタイルで共有する</b>解析結果の表。
 *
 * 以前は {@code ComputedStyle} ごとに「添字 → 値」の小さな配列（{@code Memo}）を持ち、プロパティを初めて引くたびに
 * 配列を作り直して（確保）、同じ {@code 16px} を要素の数だけ解析していた。値の解析結果は文字列だけで決まり不変なので、
 * 文字列をキーに 1 回だけ解析して全要素で使い回す。ロックは使わず {@link ConcurrentHashMap} の読み取りだけで引ける
 * （ヒット時の確保なし）。競って同じ値を 2 回解析しても結果は同じ。
 */
final class TypedValueCache {

    private TypedValueCache() {}

    /** これを超えたら全消去（宣言の文字列は有限だが、{@code calc()} の埋め込みなどで増えうる）。 */
    static final int LIMIT = 4096;

    private static final Object NOT_A_NUMBER = new Object();

    private static final ConcurrentHashMap<String, LengthResolver.CompiledLength> LENGTHS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Integer> COLORS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Object> NUMBERS = new ConcurrentHashMap<>();

    static LengthResolver.CompiledLength length(String raw) {
        LengthResolver.CompiledLength hit = LENGTHS.get(raw);
        if (hit != null) return hit;
        LengthResolver.CompiledLength compiled = LengthResolver.compile(raw);
        if (LENGTHS.size() >= LIMIT) LENGTHS.clear();
        LENGTHS.put(raw, compiled);
        return compiled;
    }

    static int color(String raw) {
        Integer hit = COLORS.get(raw);
        if (hit != null) return hit;
        int parsed = ColorParser.parseColor(raw);
        if (COLORS.size() >= LIMIT) COLORS.clear();
        COLORS.put(raw, parsed);
        return parsed;
    }

    /** 単位なしの数値。解けない値は {@code defaultValue}（解けないことも覚える）。 */
    static float number(String raw, float defaultValue) {
        Object hit = NUMBERS.get(raw);
        if (hit == null) {
            Object parsed;
            try {
                parsed = Float.parseFloat(raw.trim());
            } catch (NumberFormatException e) {
                parsed = NOT_A_NUMBER;
            }
            if (NUMBERS.size() >= LIMIT) NUMBERS.clear();
            NUMBERS.put(raw, parsed);
            hit = parsed;
        }
        return hit == NOT_A_NUMBER ? defaultValue : (Float) hit;
    }

    /** テスト用: 表の大きさ。 */
    static int sizeForTest() {
        return LENGTHS.size() + COLORS.size() + NUMBERS.size();
    }

    /** テスト用: 全消去。 */
    static void clearForTest() {
        LENGTHS.clear();
        COLORS.clear();
        NUMBERS.clear();
    }
}
