package com.nishiyu.enderframelib.client.renderer;

import com.mojang.blaze3d.font.GlyphProvider;
import com.nishiyu.enderframelib.EnderFrameLib;
import com.nishiyu.enderframelib.core.text.FontFace;
import com.nishiyu.enderframelib.core.text.FontFace.BitmapFontFace;
import com.nishiyu.enderframelib.core.text.FontFace.FontResolver;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.FontOption;
import net.minecraft.client.gui.font.FontSet;
import net.minecraft.client.gui.font.providers.TrueTypeGlyphProviderDefinition;
import net.minecraft.resources.ResourceLocation;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * W3C CSS Fonts Module Level 3 標準の {@code @font-face} ルールおよび {@code font-family} プロパティを解決し、
 * Minecraft の TrueType (TTF/OTF) グリフプロバイダーおよび FontSet を動的に構築・キャッシュするフォントマネージャー。
 */
public class WebFontManager {

    private static final Pattern FONT_FACE_PATTERN = Pattern.compile("(?is)@font-face\\s*\\{([^}]+)}");
    private static final Pattern URL_PATTERN = Pattern.compile("(?i)url\\(\\s*[\"']?([^\"')]+)[\"']?\\s*\\)");

    public record FontFaceSpec(
            String familyName,
            ResourceLocation fontResource,
            float size,
            float oversample,
            float shiftX,
            float shiftY
    ) {}

    private static final Map<String, FontFaceSpec> REGISTERED_FACES = new ConcurrentHashMap<>();
    private static final Map<String, Font> FONT_CACHE = new ConcurrentHashMap<>();
    private static final Set<FontSet> CREATED_FONT_SETS = ConcurrentHashMap.newKeySet();
    private static Method cachedGetFontSetMethod;

    /**
     * CSS文字列内の {@code @font-face} ブロックを解析して登録し、残りのCSS文字列を返す。
     */
    public static String extractAndRegisterFontFaces(String css) {
        if (css == null || !css.toLowerCase(Locale.ROOT).contains("@font-face")) {
            return css;
        }

        Matcher m = FONT_FACE_PATTERN.matcher(css);
        StringBuilder cleanedCss = new StringBuilder();
        while (m.find()) {
            String body = m.group(1);
            parseFontFaceBlock(body);
            m.appendReplacement(cleanedCss, "");
        }
        m.appendTail(cleanedCss);
        return cleanedCss.toString();
    }

    private static void parseFontFaceBlock(String block) {
        String family = null;
        String srcUrl = null;
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
                    if (um.find()) {
                        srcUrl = um.group(1).trim();
                    } else {
                        srcUrl = cleanFamilyName(val);
                    }
                }
                case "font-size", "size" -> {
                    try { size = Float.parseFloat(val.replace("px", "").trim()); } catch (Exception ignored) {}
                }
                case "oversample" -> {
                    try { oversample = Float.parseFloat(val.trim()); } catch (Exception ignored) {}
                }
            }
        }

        if (family != null && !family.isEmpty() && srcUrl != null && !srcUrl.isEmpty()) {
            try {
                ResourceLocation loc = ResourceLocation.parse(srcUrl);
                registerFontFace(new FontFaceSpec(family, loc, size, oversample, 0.0f, 0.0f));
            } catch (Exception ignored) {
            }
        }
    }

    public static void registerFontFace(FontFaceSpec spec) {
        if (spec == null || spec.familyName() == null) return;
        String key = cleanFamilyName(spec.familyName());
        REGISTERED_FACES.put(key, spec);
        FONT_CACHE.remove(key);
    }

    /**
     * CSSの {@code font-family} 文字列（例: {@code "'JetBrains Mono', 'minecraft:alt', sans-serif"}）から
     * 描画に使用する {@link Font} インスタンスを解決する。
     */
    public static Font resolveFont(String fontFamilyCss) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return null;
        if (fontFamilyCss == null || fontFamilyCss.isBlank()) {
            return mc.font;
        }

        for (String candidate : fontFamilyCss.split(",")) {
            String family = cleanFamilyName(candidate);
            if (family.isEmpty() || "sans-serif".equals(family) || "serif".equals(family) || "system-ui".equals(family) || "default".equals(family)) {
                continue;
            }

            Font cached = FONT_CACHE.get(family);
            if (cached != null) return cached;

            Font created = buildFontInstance(mc, family);
            if (created != null) {
                FONT_CACHE.put(family, created);
                return created;
            }
        }
        return mc.font;
    }

    private static FontSet resolveExistingFontSet(Minecraft mc, ResourceLocation loc) {
        if (mc == null || mc.font == null) return null;
        try {
            if (cachedGetFontSetMethod == null) {
                try {
                    cachedGetFontSetMethod = Font.class.getDeclaredMethod("getFontSet", ResourceLocation.class);
                    cachedGetFontSetMethod.setAccessible(true);
                } catch (NoSuchMethodException e) {
                    for (Method m : Font.class.getDeclaredMethods()) {
                        if (m.getReturnType() == FontSet.class && m.getParameterCount() == 1 && m.getParameterTypes()[0] == ResourceLocation.class) {
                            m.setAccessible(true);
                            cachedGetFontSetMethod = m;
                            break;
                        }
                    }
                }
            }
            if (cachedGetFontSetMethod != null) {
                return (FontSet) cachedGetFontSetMethod.invoke(mc.font, loc);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static Font buildFontInstance(Minecraft mc, String family) {
        try {
            FontFaceSpec spec = REGISTERED_FACES.get(family);
            if (spec != null) {
                ResourceLocation resLoc = spec.fontResource();
                String path = resLoc.getPath();
                if (path.endsWith(".ttf") || path.endsWith(".otf")) {
                    Font ttfFont = loadTrueTypeFont(mc, resLoc, spec.size(), spec.oversample(), spec.shiftX(), spec.shiftY());
                    if (ttfFont != null) return ttfFont;
                }
                FontSet fs = resolveExistingFontSet(mc, resLoc);
                if (fs != null) return new Font(id -> fs, false);
            }

            if ("alt".equals(family) || "enchant".equals(family) || "minecraft:alt".equals(family)) {
                ResourceLocation altLoc = ResourceLocation.withDefaultNamespace("alt");
                FontSet fs = resolveExistingFontSet(mc, altLoc);
                if (fs != null) return new Font(id -> fs, false);
            }
            if ("uniform".equals(family) || "monospace".equals(family) || "minecraft:uniform".equals(family)) {
                ResourceLocation uniLoc = ResourceLocation.withDefaultNamespace("uniform");
                FontSet fs = resolveExistingFontSet(mc, uniLoc);
                if (fs != null) return new Font(id -> fs, false);
            }
            if (family.contains(":")) {
                ResourceLocation customLoc = ResourceLocation.parse(family);
                if (customLoc.getPath().endsWith(".ttf") || customLoc.getPath().endsWith(".otf")) {
                    Font ttfFont = loadTrueTypeFont(mc, customLoc, 11.0f, 4.0f, 0.0f, 0.0f);
                    if (ttfFont != null) return ttfFont;
                }
                FontSet fs = resolveExistingFontSet(mc, customLoc);
                if (fs != null) return new Font(id -> fs, false);
            }
        } catch (Exception e) {
            EnderFrameLib.LOGGER.debug("Failed to resolve custom font '{}': {}", family, e.getMessage());
        }
        return null;
    }

    private static Font loadTrueTypeFont(Minecraft mc, ResourceLocation ttfLocation, float size, float oversample, float shiftX, float shiftY) {
        try {
            ResourceLocation normalized = ttfLocation;
            if (normalized.getPath().startsWith("font/")) {
                normalized = ResourceLocation.fromNamespaceAndPath(normalized.getNamespace(), normalized.getPath().substring(5));
            }
            TrueTypeGlyphProviderDefinition def = new TrueTypeGlyphProviderDefinition(
                    normalized,
                    size,
                    oversample,
                    new TrueTypeGlyphProviderDefinition.Shift(shiftX, shiftY),
                    ""
            );
            var loaderEither = def.unpack();
            if (loaderEither.left().isPresent()) {
                var glyphProvider = loaderEither.left().get().load(mc.getResourceManager());
                FontSet fontSet = new FontSet(mc.getTextureManager(), ttfLocation);
                GlyphProvider.Conditional conditional = new GlyphProvider.Conditional(
                        glyphProvider,
                        FontOption.Filter.ALWAYS_PASS
                );
                fontSet.reload(List.of(conditional), Set.of());
                CREATED_FONT_SETS.add(fontSet);
                return new Font(id -> fontSet, false);
            }
        } catch (Exception e) {
            EnderFrameLib.LOGGER.warn("Could not load TrueType font {}: {}", ttfLocation, e.getMessage());
        }
        return null;
    }

    public static void clearCache() {
        FONT_CACHE.clear();
        for (FontSet fs : CREATED_FONT_SETS) {
            try {
                fs.close();
            } catch (Exception ignored) {}
        }
        CREATED_FONT_SETS.clear();
    }

    private static String cleanFamilyName(String raw) {
        if (raw == null) return "";
        return raw.trim().replaceAll("^[\"']+|[\"']+$", "").trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Minecraft のフォントを {@link FontFace} として見せるアダプタ。
     *
     * 何が壊れていたか: レイアウトは常に {@code Minecraft.font} で測るのに、
     * 描画は {@link WebFontManager#resolveFont} で {@code font-family} に応じた
     * 別のフォントを選んでいた。そのため {@code @font-face} を当てたテキストは
     * 折り返し位置・中央寄せ・省略位置がすべてずれていた。
     * {@code font-family} はここで 1 回だけ解決し、測定も描画も同じ書体を通す。
     *
     * 太字を {@code §l} の前置で測るという Minecraft 固有の作法も、ここだけに閉じている。
     */
    public static final class McFontResolver implements FontResolver {

        public static final McFontResolver INSTANCE = new McFontResolver();

        private final Map<String, FontFace> faces = new ConcurrentHashMap<>();

        private McFontResolver() {}

        @Override
        public FontFace resolve(String fontFamily) {
            return faces.computeIfAbsent(fontFamily == null ? "" : fontFamily, McFontResolver::create);
        }

        /**
         * 描画に使う実フォント。描画側も測定と同じ書体を引くためにここを通す。
         * フォントは非同期に読み込まれるので、解決結果は保持せず毎回引き直す。
         */
        public static Font fontFor(String fontFamily) {
            Font resolved = (fontFamily == null || fontFamily.isBlank()) ? null : WebFontManager.resolveFont(fontFamily);
            if (resolved != null) return resolved;
            Minecraft mc = Minecraft.getInstance();
            return mc != null ? mc.font : null;
        }

        private static FontFace create(String fontFamily) {
            return new BitmapFontFace() {
                @Override
                protected float naturalAdvance(String text, boolean bold) {
                    Font font = fontFor(fontFamily);
                    if (font == null) return FontFace.FALLBACK.advance(text, FontStyleHolder.NATURAL);
                    // U+00A0（&nbsp;）は Minecraft のフォントに空白の幅の字形が無いので、空白として測る（描画も同じ置き換えをする）
                    String measured = text.replace(' ', ' ');
                    return font.width(bold ? ("§l" + measured) : measured);
                }
            };
        }

        /**
         * Minecraft がまだ無い状況（ワールド読み込み前のレイアウト）で素のサイズを測るための指定。
         * 1em = 素のサイズなので倍率 1.0 になる。
         */
        private static final class FontStyleHolder {
            static final com.nishiyu.enderframelib.core.text.FontFace.FontStyle NATURAL =
                    new com.nishiyu.enderframelib.core.text.FontFace.FontStyle(BitmapFontFace.MC_UNITS_PER_EM, false, false, 0f);
        }
    }
}