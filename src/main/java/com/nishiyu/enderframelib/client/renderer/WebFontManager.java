package com.nishiyu.enderframelib.client.renderer;

import com.mojang.blaze3d.font.GlyphProvider;
import com.nishiyu.enderframelib.EnderFrameLib;
import com.nishiyu.enderframelib.core.text.model.FontFace;
import com.nishiyu.enderframelib.core.text.model.BitmapFontFace;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
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
import com.nishiyu.enderframelib.core.text.model.FontStyle;
import com.nishiyu.enderframelib.core.layout.model.LayoutMetrics;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.text.font.OutlineFontFace;
import com.nishiyu.enderframelib.core.text.font.SystemFonts;
import com.nishiyu.enderframelib.core.text.font.FontFaceRules;
import com.nishiyu.enderframelib.core.text.font.WebFonts;
import java.io.InputStream;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * W3C CSS Fonts Module Level 3 標準の {@code @font-face} ルールおよび {@code font-family} プロパティを解決し、
 * Minecraft の TrueType (TTF/OTF) グリフプロバイダーおよび FontSet を動的に構築・キャッシュするフォントマネージャー。
 */
public class WebFontManager {

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
    private static volatile Font cachedUniformFont;

    /**
     * Minecraft バニラ同梱の Unicode 通常フォント（minecraft:uniform）を取得・キャッシュする。
     * ドット絵ではない滑らかな通常文字フォント。
     */
    public static Font getUniformFont(Minecraft mc) {
        if (cachedUniformFont != null) return cachedUniformFont;
        if (mc == null) return null;
        try {
            ResourceLocation uniLoc = ResourceLocation.withDefaultNamespace("uniform");
            FontSet fs = resolveExistingFontSet(mc, uniLoc);
            if (fs != null) {
                cachedUniformFont = new Font(id -> fs, false);
                return cachedUniformFont;
            }
        } catch (Exception ignored) {
        }
        return mc.font;
    }

    /**
     * Web UI のデフォルトフォントを取得する。
     * 通常文字（minecraft:uniform）を優先し、利用できない場合は mc.font にフォールバックする。
     */
    public static Font getDefaultFont() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return null;
        Font uniform = getUniformFont(mc);
        return uniform != null ? uniform : mc.font;
    }

    public static boolean isWebSansSerifFamily(String family) {
        return FontFaceRules.isWebSansSerifFamily(family);
    }

    /** CSS 文字列内の {@code @font-face} を解析して登録し、残りの CSS を返す。 */
    public static String extractAndRegisterFontFaces(String css) {
        return FontFaceRules.extractAndRegister(css, d -> {
            try {
                ResourceLocation loc = ResourceLocation.parse(d.src());
                registerFontFace(new FontFaceSpec(d.family(), loc, d.size(), d.oversample(), 0.0f, 0.0f));
            } catch (Exception ignored) {
            }
        });
    }

    /** 書体データの読み出しをリソースパックへつなぐ（起動時に 1 回）。 */
    public static void installFontReader() {
        FontFaceRules.setFontReader(source -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return null;
            ResourceLocation loc = ResourceLocation.tryParse(source);
            if (loc == null) return null;
            var resource = mc.getResourceManager().getResource(loc);
            if (resource.isEmpty()) return null;
            try (InputStream in = resource.get().open()) {
                return in.readAllBytes();
            }
        });
    }

    public static void registerFontFace(FontFaceSpec spec) {
        if (spec == null || spec.familyName() == null) return;
        String key = FontFaceRules.cleanFamilyName(spec.familyName());
        REGISTERED_FACES.put(key, spec);
        FONT_CACHE.remove(key);
        McFontResolver.INSTANCE.clear();
    }

    /**
     * CSSの {@code font-family} 文字列（例: {@code "'JetBrains Mono', 'minecraft:alt', sans-serif"}）から
     * 描画に使用する {@link Font} インスタンスを解決する。
     */
    public static Font resolveFont(String fontFamilyCss) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return null;
        if (fontFamilyCss == null || fontFamilyCss.isBlank()) {
            return getDefaultFont();
        }

        for (String candidate : fontFamilyCss.split(",")) {
            String family = FontFaceRules.cleanFamilyName(candidate);
            if (family.isEmpty()) {
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
        return getDefaultFont();
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
            if (isWebSansSerifFamily(family) || "uniform".equals(family) || "monospace".equals(family) || "minecraft:uniform".equals(family)) {
                Font uni = getUniformFont(mc);
                if (uni != null) return uni;
            }
            if ("pixel".equals(family) || "minecraft:default".equals(family)) {
                return mc.font;
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
        FontFaceRules.reloadOutlineFonts();
        McFontResolver.INSTANCE.clear();
        FONT_CACHE.clear();
        cachedUniformFont = null;
        for (FontSet fs : CREATED_FONT_SETS) {
            try {
                fs.close();
            } catch (Exception ignored) {}
        }
        CREATED_FONT_SETS.clear();
    }

    static boolean prefersMinecraftFont(String fontFamily) {
        return FontFaceRules.prefersMinecraftFont(fontFamily, REGISTERED_FACES::containsKey);
    }

    /** 要素の文字をシステムの書体で描くならその書体、Minecraft の書体で描くなら null。 */
    public static OutlineFontFace outlineFaceFor(DisplayItem el) {
        String family = el.events() != null ? el.events().get("font-family") : null;
        return McFontResolver.INSTANCE.resolve(family) instanceof OutlineFontFace outline ? outline : null;
    }

    public static FontStyle fontStyleFor(DisplayItem el, float fontSizePx) {
        return FontFaceRules.fontStyleFor(el, fontSizePx);
    }

    /**
     * {@code font-family} を測定用の書体にする。
     *
     * 何が壊れていたか: レイアウトは常に {@code Minecraft.font} で測るのに、
     * 描画は {@link WebFontManager#resolveFont} で {@code font-family} に応じた
     * 別のフォントを選んでいた。そのため {@code @font-face} を当てたテキストは
     * 折り返し位置・中央寄せ・省略位置がすべてずれていた。
     * {@code font-family} はここで 1 回だけ解決し、測定も描画も同じ書体を通す。
     *
     * ブラウザと同じく、{@code font-family} はシステムの書体（{@link SystemFonts}）で引く。
     * 以前は Web の sans-serif 系の名前をすべて Minecraft の unifont（16px のビットマップ）に寄せ、
     * それを CSS の大きさに拡大縮小して描いていたので、中間の倍率で線が潰れていた。
     * Minecraft の書体を明示したとき（{@link #prefersMinecraftFont}）と、システムに書体が 1 つも無いときだけ
     * ビットマップの書体を使う。太字を {@code §l} の前置で測るという Minecraft 固有の作法はそちらに閉じている。
     */
    public static final class McFontResolver implements FontResolver {

        public static final McFontResolver INSTANCE = new McFontResolver();

        private final Map<String, FontFace> faces = new ConcurrentHashMap<>();

        /** {@link #clear} のたびに増える。描いた結果を使い回す側が、書体の登録が変わったかを見分ける。 */
        private static final java.util.concurrent.atomic.AtomicInteger GENERATION = new java.util.concurrent.atomic.AtomicInteger();

        private McFontResolver() {}

        public static int generation() {
            return GENERATION.get();
        }

        @Override
        public FontFace resolve(String fontFamily) {
            return faces.computeIfAbsent(fontFamily == null ? "" : fontFamily, McFontResolver::create);
        }

        /** {@code @font-face} の登録などで解決結果が変わったときに捨てる。 */
        public void clear() {
            faces.clear();
            GENERATION.incrementAndGet();
            // 文字の幅が変わり得るので、差分レイアウトに前回の measure の結果を使わせない
            com.nishiyu.enderframelib.core.layout.LayoutBox.invalidateAllMeasurements();
        }

        /**
         * ビットマップの書体を描くときの実フォント。描画側も測定と同じ書体を引くためにここを通す。
         * フォントは非同期に読み込まれるので、解決結果は保持せず毎回引き直す。
         */
        public static Font fontFor(String fontFamily) {
            Font resolved = (fontFamily == null || fontFamily.isBlank()) ? null : WebFontManager.resolveFont(fontFamily);
            if (resolved != null) return resolved;
            return WebFontManager.getDefaultFont();
        }

        private static FontFace create(String fontFamily) {
            if (!prefersMinecraftFont(fontFamily)) {
                FontFace system = SystemFonts.system().resolve(fontFamily.isEmpty() ? null : fontFamily);
                if (system instanceof OutlineFontFace) return system;
            }
            return bitmapFace(fontFamily);
        }

        private static FontFace bitmapFace(String fontFamily) {
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
            static final FontStyle NATURAL =
                    new FontStyle(BitmapFontFace.MC_UNITS_PER_EM, false, false, 0f);
        }
    }
}
