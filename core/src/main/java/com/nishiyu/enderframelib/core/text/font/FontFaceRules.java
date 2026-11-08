package com.nishiyu.enderframelib.core.text.font;

import com.nishiyu.enderframelib.core.layout.model.LayoutMetrics;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.text.model.FontStyle;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code @font-face} の解析・書体名の正規化・TTF / OTF の登録（{@link WebFonts} へ）をまとめた、
 * Minecraft に依存しないブラウザ共通の部分。書体データの読み出しだけを {@link FontReader} で差し込む
 * （Minecraft はリソースパック、窓はファイルや HTTP）。
 */
public final class FontFaceRules {

    /** パースした {@code @font-face} 1 件。{@code src} は {@code url()} の中身（無ければ生の値）。 */
    public record FaceDecl(String family, String src, int weight, boolean italic, float size, float oversample) {}

    /** 書体の出所の名前（{@code ns:path/font.ttf} など）→ バイト列。無ければ null。 */
    @FunctionalInterface
    public interface FontReader {
        byte[] read(String source) throws Exception;

        /**
         * 出所が OS のファイルとして直接開けるなら、そのパス。あれば {@link FontFaceRules} はバイト列を読まず、
         * FreeType にファイルをそのまま渡す（OS のメモリマップ。ヒープにも malloc にも写しを作らない）。
         * 既定は null（バイト列の経路）。
         */
        default java.nio.file.Path locate(String source) throws Exception {
            return null;
        }
    }

    private static final Pattern FONT_FACE_PATTERN = Pattern.compile("(?is)@font-face\\s*\\{([^}]+)}");
    private static final Pattern URL_PATTERN = Pattern.compile("(?i)url\\(\\s*[\"']?([^\"')]+)[\"']?\\s*\\)");
    private static final Pattern FAMILY_QUOTES = Pattern.compile("^[\"']+|[\"']+$");
    /** 要素ごと・フレームごとに引かれるので、結果を覚える（書体名の種類は少ない）。 */
    private static final Map<String, String> CLEAN_FAMILY_CACHE = new ConcurrentHashMap<>();

    /** Minecraft の書体を表す {@code font-family} の名前（小文字）。これらはビットマップの書体で描く。 */
    private static final Set<String> MINECRAFT_FAMILIES = Set.of(
            "minecraft:default", "minecraft:uniform", "minecraft:alt", "pixel", "uniform", "alt", "enchant", "minecraft");

    /** {@code @font-face} の TTF / OTF の出所。資源の再読み込みで読み直すために覚える。 */
    private record OutlineSource(String family, String source, int weight, boolean italic) {}

    private static final List<OutlineSource> OUTLINE_SOURCES = new CopyOnWriteArrayList<>();
    private static volatile FontReader reader = s -> null;

    private FontFaceRules() {}

    public static void setFontReader(FontReader r) {
        reader = r == null ? s -> null : r;
    }

    public static String cleanFamilyName(String raw) {
        if (raw == null) return "";
        String cached = CLEAN_FAMILY_CACHE.get(raw);
        if (cached != null) return cached;
        String cleaned = FAMILY_QUOTES.matcher(raw.trim()).replaceAll("").trim().toLowerCase(Locale.ROOT);
        if (CLEAN_FAMILY_CACHE.size() > 1024) CLEAN_FAMILY_CACHE.clear();
        CLEAN_FAMILY_CACHE.put(raw, cleaned);
        return cleaned;
    }

    /** 指定されたフォント名が Web 標準の Sans-Serif 系フォント群であるかを判定する。 */
    public static boolean isWebSansSerifFamily(String family) {
        if (family == null || family.isBlank()) return false;
        return switch (cleanFamilyName(family)) {
            case "sans-serif", "sans", "system-ui", "ui-sans-serif", "-apple-system", "blinkmacsystemfont",
                 "segoe ui", "roboto", "helvetica", "helvetica neue", "arial", "inter",
                 "noto sans", "noto sans jp", "noto sans cjk", "meiryo", "yu gothic",
                 "verdana", "tahoma", "trebuchet ms", "open sans", "lato", "poppins" -> true;
            default -> false;
        };
    }

    /** {@code font-weight} 記述子（{@code bold} / 数値 / 範囲 {@code 100 900}）を下限の数値にする。 */
    public static int parseFontWeightDescriptor(String value) {
        String v = value.trim().toLowerCase(Locale.ROOT);
        if (v.startsWith("bold")) return 700;
        if (v.startsWith("normal")) return 400;
        Matcher m = Pattern.compile("^\\d+").matcher(v);
        return m.find() ? Integer.parseInt(m.group()) : 400;
    }

    /** 1 つの {@code @font-face} の本体（波括弧の中）を解く。family か src が無ければ null。 */
    public static FaceDecl parseFontFaceBlock(String block) {
        String family = null;
        String srcUrl = null;
        int weight = 400;
        boolean italic = false;
        float size = 11.0f;
        float oversample = 4.0f;

        for (String decl : block.split(";")) {
            int colon = decl.indexOf(':');
            if (colon <= 0) continue;
            String key = decl.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String val = decl.substring(colon + 1).trim();
            switch (key) {
                case "font-family" -> family = cleanFamilyName(val);
                case "src" -> {
                    Matcher um = URL_PATTERN.matcher(val);
                    srcUrl = um.find() ? um.group(1).trim() : cleanFamilyName(val);
                }
                case "font-weight" -> weight = parseFontWeightDescriptor(val);
                case "font-style" -> {
                    String v = val.toLowerCase(Locale.ROOT);
                    italic = v.startsWith("italic") || v.startsWith("oblique");
                }
                case "font-size", "size" -> {
                    try { size = Float.parseFloat(val.replace("px", "").trim()); } catch (Exception ignored) {}
                }
                case "oversample" -> {
                    try { oversample = Float.parseFloat(val.trim()); } catch (Exception ignored) {}
                }
                default -> { }
            }
        }
        if (family == null || family.isEmpty() || srcUrl == null || srcUrl.isEmpty()) return null;
        return new FaceDecl(family, srcUrl, weight, italic, size, oversample);
    }

    /**
     * CSS 文字列内の {@code @font-face} を解いて {@code sink} へ渡し、TTF / OTF なら {@link WebFonts} に登録して、
     * {@code @font-face} を除いた CSS を返す。{@code sink} は Minecraft の書体など、固有の登録に使う。
     */
    public static String extractAndRegister(String css, Consumer<FaceDecl> sink) {
        if (css == null || !css.toLowerCase(Locale.ROOT).contains("@font-face")) return css;
        Matcher m = FONT_FACE_PATTERN.matcher(css);
        StringBuilder cleaned = new StringBuilder();
        while (m.find()) {
            FaceDecl d = parseFontFaceBlock(m.group(1));
            if (d != null) {
                OutlineSource outline = new OutlineSource(d.family(), d.src(), d.weight(), d.italic());
                if (isOutlineName(d.src())) {
                    registerOutline(outline);
                    // まだ読めない出所（HTTP の取得中など）も覚え、reloadOutlineFonts で読み直せるようにする
                    if (!OUTLINE_SOURCES.contains(outline)) OUTLINE_SOURCES.add(outline);
                }
                if (sink != null) sink.accept(d);
            }
            m.appendReplacement(cleaned, "");
        }
        m.appendTail(cleaned);
        return cleaned.toString();
    }

    /** 名前（クエリ・フラグメントを除く）が TTF / OTF か。 */
    static boolean isOutlineName(String source) {
        String lower = source.toLowerCase(Locale.ROOT);
        int cut = lower.indexOf('?');
        if (cut >= 0) lower = lower.substring(0, cut);
        cut = lower.indexOf('#');
        if (cut >= 0) lower = lower.substring(0, cut);
        return lower.endsWith(".ttf") || lower.endsWith(".otf");
    }

    /** TTF / OTF を読んで {@link WebFonts} に登録する。読めなければ false。 */
    private static boolean registerOutline(OutlineSource source) {
        if (!isOutlineName(source.source())) return false;
        String src = source.source();
        int colon = src.indexOf(':');
        // ns:path は font/ を挟んだ置き場も試す（リソースパックの慣習）
        String alt = (colon > 0 && !src.startsWith("http") && !src.startsWith("file"))
                ? src.substring(0, colon + 1) + "font/" + src.substring(colon + 1) : null;
        for (String candidate : alt == null ? List.of(src) : List.of(src, alt)) {
            try {
                java.nio.file.Path file = reader.locate(candidate);
                if (file != null && WebFonts.registerFile(source.family(), file, source.weight(), source.italic())) return true;
                byte[] bytes = reader.read(candidate);
                if (bytes == null) continue;
                return WebFonts.register(source.family(), bytes, source.weight(), source.italic());
            } catch (Exception ignored) {
                // 次の候補へ
            }
        }
        return false;
    }

    /** 資源の再読み込みのあとに、登録済みの TTF / OTF を読み直す。 */
    public static void reloadOutlineFonts() {
        WebFonts.clear();
        for (OutlineSource source : OUTLINE_SOURCES) registerOutline(source);
    }

    /**
     * {@code font-family} の並びで最初に使える書体が Minecraft の書体なら true。それ以外はシステムの書体で描く。
     *
     * @param registeredByMinecraft Minecraft 側で {@code @font-face} 登録した名前か
     */
    public static boolean prefersMinecraftFont(String fontFamily, Predicate<String> registeredByMinecraft) {
        SystemFonts system = SystemFonts.system();
        for (var e : SystemFonts.parseFamilyList(fontFamily)) {
            if (!e.generic()) {
                String lower = e.name().toLowerCase(Locale.ROOT);
                // @font-face で TTF / OTF を読めたものはシステムの書体と同じ経路で描くので、Minecraft の書体ではない
                if (WebFonts.has(lower)) return false;
                if (MINECRAFT_FAMILIES.contains(lower) || registeredByMinecraft.test(lower) || lower.contains(":")) return true;
            }
            if (system.isAvailable(e)) return false;
        }
        return false;
    }

    /** 描画要素に載っている指定から、測定と同じ {@link FontStyle} を作る。 */
    public static FontStyle fontStyleFor(DisplayItem el, float fontSizePx) {
        Map<String, String> ev = el.events() != null ? el.events() : Map.of();
        int weight = LayoutMetrics.numericWeight(ev.get("font-weight"));
        String style = ev.getOrDefault("font-style", "normal").trim();
        float letterSpacing = 0;
        try {
            letterSpacing = Float.parseFloat(ev.getOrDefault("letter-spacing", "0"));
        } catch (NumberFormatException ignored) {
        }
        boolean kerning = !"none".equalsIgnoreCase(ev.getOrDefault("font-kerning", "auto").trim());
        return new FontStyle(fontSizePx, weight >= 600, !style.equalsIgnoreCase("normal"), letterSpacing, weight, kerning);
    }
}
