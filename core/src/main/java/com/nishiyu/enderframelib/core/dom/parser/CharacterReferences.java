package com.nishiyu.enderframelib.core.dom.parser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 文字参照（§13.2.5.72〜80）。名前付きは WHATWG の表（{@code entities.txt}、2,231 項目）を最長一致で引く。
 *
 * 何が壊れていたか: 以前は名前付きが {@code &lt;} / {@code &gt;} / {@code &quot;} / {@code &apos;} /
 * {@code &nbsp;} / {@code &amp;} の 6 つだけで（{@code &nbsp;} は普通の空白になっていた）、
 * {@code &copy;} や {@code &hellip;} は生のまま表示され、{@code ;} の無い数値参照も解かなかった。
 * 1 回の走査で解くので {@code &amp;lt;} は {@code &lt;} という文字列のまま残る。
 */
public final class CharacterReferences {

    private static final String TABLE_RESOURCE = "/com/nishiyu/enderframelib/core/dom/entities.txt";
    private static final int LONGEST_NAME = 32;

    /** 0x80〜0x9F の数値参照は Windows-1252 の文字として読む（§13.2.5.80）。0 は対応なし。 */
    private static final int[] C1_REPLACEMENTS = {
            0x20AC, 0, 0x201A, 0x0192, 0x201E, 0x2026, 0x2020, 0x2021, 0x02C6, 0x2030, 0x0160, 0x2039, 0x0152, 0, 0x017D, 0,
            0, 0x2018, 0x2019, 0x201C, 0x201D, 0x2022, 0x2013, 0x2014, 0x02DC, 0x2122, 0x0161, 0x203A, 0x0153, 0, 0x017E, 0x0178};

    private static volatile Map<String, String> table;

    private CharacterReferences() {
    }

    /**
     * @param inAttribute 属性値の中か。属性値では {@code ;} の無い名前の直後が {@code =} か英数字なら解かない
     *                    （{@code href="?a=1&copy=2"} の {@code &copy} を著作権記号にしないため）
     */
    public static String decode(String s, boolean inAttribute) {
        if (s == null || s.indexOf('&') < 0) return s;

        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        int len = s.length();
        while (i < len) {
            char c = s.charAt(i);
            if (c != '&' || i + 1 >= len) {
                out.append(c);
                i++;
                continue;
            }
            int consumed = s.charAt(i + 1) == '#' ? decodeNumeric(s, i, out) : decodeNamed(s, i, out, inAttribute);
            if (consumed == 0) {
                out.append('&');
                i++;
            } else {
                i += consumed;
            }
        }
        return out.toString();
    }

    /** 数値参照を解いて消費した文字数を返す。桁が 1 つも無ければ 0（参照ではない）。 */
    private static int decodeNumeric(String s, int start, StringBuilder out) {
        int i = start + 2;
        int radix = 10;
        if (i < s.length() && (s.charAt(i) == 'x' || s.charAt(i) == 'X')) {
            radix = 16;
            i++;
        }
        int digitsStart = i;
        long value = 0;
        while (i < s.length() && Character.digit(s.charAt(i), radix) >= 0) {
            if (value <= 0x10FFFF) value = value * radix + Character.digit(s.charAt(i), radix);
            i++;
        }
        if (i == digitsStart) return 0;
        if (i < s.length() && s.charAt(i) == ';') i++;

        int codePoint;
        if (value == 0 || value > 0x10FFFF || (value >= 0xD800 && value <= 0xDFFF)) {
            codePoint = 0xFFFD;
        } else if (value >= 0x80 && value <= 0x9F && C1_REPLACEMENTS[(int) value - 0x80] != 0) {
            codePoint = C1_REPLACEMENTS[(int) value - 0x80];
        } else {
            codePoint = (int) value;
        }
        out.appendCodePoint(codePoint);
        return i - start;
    }

    private static int decodeNamed(String s, int start, StringBuilder out, boolean inAttribute) {
        Map<String, String> names = table();
        int nameStart = start + 1;
        int maxEnd = Math.min(s.length(), nameStart + LONGEST_NAME);
        for (int end = maxEnd; end > nameStart; end--) {
            String candidate = s.substring(nameStart, end);
            String chars = names.get(candidate);
            if (chars == null) continue;
            if (inAttribute && !candidate.endsWith(";") && end < s.length()) {
                char next = s.charAt(end);
                if (next == '=' || Character.isLetterOrDigit(next)) return 0;
            }
            out.append(chars);
            return end - start;
        }
        return 0;
    }

    private static Map<String, String> table() {
        Map<String, String> loaded = table;
        if (loaded == null) {
            synchronized (CharacterReferences.class) {
                loaded = table;
                if (loaded == null) {
                    loaded = load();
                    table = loaded;
                }
            }
        }
        return loaded;
    }

    private static Map<String, String> load() {
        Map<String, String> names = new HashMap<>(4096);
        try (InputStream is = CharacterReferences.class.getResourceAsStream(TABLE_RESOURCE)) {
            if (is == null) {
                HtmlParser.LOGGER.error("HTML の文字参照の表が見つかりません: {}", TABLE_RESOURCE);
                return names;
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.US_ASCII));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                int space = line.indexOf(' ');
                StringBuilder chars = new StringBuilder(2);
                for (String hex : line.substring(space + 1).split(",")) {
                    chars.appendCodePoint(Integer.parseInt(hex, 16));
                }
                names.put(line.substring(0, space), chars.toString());
            }
        } catch (IOException e) {
            HtmlParser.LOGGER.error("HTML の文字参照の表を読めませんでした", e);
        }
        return names;
    }
}
