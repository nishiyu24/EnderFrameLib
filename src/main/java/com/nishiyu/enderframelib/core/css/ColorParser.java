package com.nishiyu.enderframelib.core.css;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ColorParser {
    private static final Pattern GRADIENT_COLOR_PATTERN = Pattern.compile("(#([0-9a-f]{3,8})|rgba?\\([^)]+\\)|hsla?\\([^)]+\\))");
    private static final Pattern RGBA_PATTERN = Pattern.compile("rgba?\\s*\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)(?:\\s*,\\s*([\\d.]+))?\\s*\\)");
    private static final Pattern HSLA_PATTERN = Pattern.compile("hsla?\\s*\\(\\s*([-\\d.]+)(?:deg)?\\s*,\\s*([\\d.]+)%\\s*,\\s*([\\d.]+)%(?:\\s*,\\s*([\\d.]+))?\\s*\\)");

    public static int parseColor(String c) {
        if (c == null || c.isEmpty()) return 0x00000000;
        if (c.equalsIgnoreCase("transparent")) return 0x00000000;
        c = c.toLowerCase().trim();

        if (c.startsWith("linear-gradient") || c.startsWith("radial-gradient")) {
            Matcher m = GRADIENT_COLOR_PATTERN.matcher(c);
            if (m.find()) return parseColor(m.group(1));
            return 0xFF555555; // デフォルトのグラデーションフォールバック
        }

        if (c.startsWith("rgba") || c.startsWith("rgb")) {
            Matcher m = RGBA_PATTERN.matcher(c);
            if (m.find()) {
                int r = Math.clamp(Integer.parseInt(m.group(1)), 0, 255);
                int g = Math.clamp(Integer.parseInt(m.group(2)), 0, 255);
                int b = Math.clamp(Integer.parseInt(m.group(3)), 0, 255);
                float a = m.group(4) != null ? Math.clamp(Float.parseFloat(m.group(4)), 0f, 1f) : 1.0f;
                int alpha = (int) (a * 255);
                return (alpha << 24) | (r << 16) | (g << 8) | b;
            }
        }

        if (c.startsWith("hsla") || c.startsWith("hsl")) {
            Matcher m = HSLA_PATTERN.matcher(c);
            if (m.find()) {
                float h = Float.parseFloat(m.group(1));
                float s = Math.clamp(Float.parseFloat(m.group(2)) / 100.0f, 0f, 1f);
                float l = Math.clamp(Float.parseFloat(m.group(3)) / 100.0f, 0f, 1f);
                float a = m.group(4) != null ? Math.clamp(Float.parseFloat(m.group(4)), 0f, 1f) : 1.0f;
                return hslToArgb(h, s, l, a);
            }
        }

        int rgb = switch (c) {
            case "red" -> 0xFF5555;
            case "green", "lime" -> 0x55FF55;
            case "blue" -> 0x5555FF;
            case "yellow" -> 0xFFFF55;
            case "aqua", "cyan" -> 0x55FFFF;
            case "magenta", "purple" -> 0xFF55FF;
            case "orange", "gold" -> 0xFFAA00;
            case "black" -> 0x000000;
            case "white" -> 0xFFFFFF;
            case "gray", "grey" -> 0xAAAAAA;
            case "darkgray", "darkgrey" -> 0x555555;
            case "lightgray", "lightgrey", "silver" -> 0xD3D3D3;
            case "coral" -> 0xFF7F50;
            case "crimson" -> 0xDC143C;
            case "dodgerblue" -> 0x1E90FF;
            case "skyblue" -> 0x87CEEB;
            case "teal" -> 0x008080;
            case "indigo" -> 0x4B0082;
            case "violet" -> 0xEE82EE;
            case "pink" -> 0xFFC0CB;
            case "salmon" -> 0xFA8072;
            case "tomato" -> 0xFF6347;
            case "turquoise" -> 0x40E0D0;
            case "wheat" -> 0xF5DEB3;
            case "rebeccapurple" -> 0x663399;
            default -> {
                if (c.startsWith("#")) {
                    try {
                        if (c.length() == 9) yield (int) Long.parseLong(c.substring(1), 16);
                        if (c.length() == 7) yield Integer.parseInt(c.substring(1), 16);
                        if (c.length() == 4) {
                            int r = Integer.parseInt(c.substring(1, 2), 16);
                            int g = Integer.parseInt(c.substring(2, 3), 16);
                            int b = Integer.parseInt(c.substring(3, 4), 16);
                            r = (r << 4) | r;
                            g = (g << 4) | g;
                            b = (b << 4) | b;
                            yield (r << 16) | (g << 8) | b;
                        }
                    } catch (Exception ignored) {
                    }
                }
                yield 0xFFFFFF;
            }
        };
        return (c.length() == 9 && c.startsWith("#")) ? rgb : (0xFF000000 | rgb);
    }

    private static int hslToArgb(float h, float s, float l, float a) {
        h = ((h % 360.0f) + 360.0f) % 360.0f;
        float c = (1.0f - Math.abs(2.0f * l - 1.0f)) * s;
        float x = c * (1.0f - Math.abs(((h / 60.0f) % 2.0f) - 1.0f));
        float m = l - c / 2.0f;
        float r1 = 0, g1 = 0, b1 = 0;
        if (h < 60) { r1 = c; g1 = x; }
        else if (h < 120) { r1 = x; g1 = c; }
        else if (h < 180) { g1 = c; b1 = x; }
        else if (h < 240) { g1 = x; b1 = c; }
        else if (h < 300) { r1 = x; b1 = c; }
        else { r1 = c; b1 = x; }
        int r = Math.clamp(Math.round((r1 + m) * 255.0f), 0, 255);
        int g = Math.clamp(Math.round((g1 + m) * 255.0f), 0, 255);
        int b = Math.clamp(Math.round((b1 + m) * 255.0f), 0, 255);
        int alpha = Math.clamp(Math.round(a * 255.0f), 0, 255);
        return (alpha << 24) | (r << 16) | (g << 8) | b;
    }
}