package com.nishiyu.enderframelib.client.renderer;

import com.mojang.blaze3d.font.GlyphInfo;
import com.mojang.blaze3d.font.GlyphProvider;
import com.mojang.blaze3d.font.SheetGlyphInfo;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nishiyu.enderframelib.EnderFrameLib;
import com.nishiyu.enderframelib.core.text.BidiText;
import com.nishiyu.enderframelib.core.text.font.AtlasCache;
import com.nishiyu.enderframelib.core.text.font.AtlasSizing;
import com.nishiyu.enderframelib.core.text.font.GlyphKey;
import com.nishiyu.enderframelib.core.text.font.LoadedFace;
import com.nishiyu.enderframelib.core.text.font.OutlineFontFace;
import com.nishiyu.enderframelib.core.text.model.FontStyle;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.ints.IntSets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.FontOption;
import net.minecraft.client.gui.font.FontSet;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * システムの書体（{@link OutlineFontFace}）で文字列を描く。
 *
 * 字形は描く大きさのデバイスピクセルでラスタライズし（拡大縮小しないので潰れない）、
 * Minecraft の字形の置き場（{@link FontSet} のテクスチャ）に載せて 1 字ずつ置く。
 * 字の位置は測定（{@link OutlineFontFace#positions}）と同じ値で、詰め（カーニング）と字間を含む。
 * 字の原点はデバイスピクセルに揃える（Windows の Chrome がグレースケールで描くときと同じ見え方）。
 *
 * 置き場は「面・合成の有無・ピクセルの大きさ・倍率」ごとに 1 つ作る。原点の端数と字の明るさは置き場の鍵に含めず、
 * 字形の番号（{@link GlyphKey}）に入れて同じ置き場に載せる（以前は最大 8 枚のテクスチャに分かれ、どれも少ししか埋まらなかった）。
 * 使われなくなった置き場は {@link AtlasCache} が捨てる。ピクセルの大きさは {@link AtlasSizing} で量子化するので、
 * {@code scale} のアニメーション中もサイズごとの置き場が増え続けない。
 */
public final class OutlineTextRenderer {

    /** 1 字のビットマップの最大の大きさ（px）。Minecraft の字形のテクスチャは 256px 四方。 */
    private static final float MAX_PIXEL_SIZE = 192f;
    /** 置き場の個数の目安。超えても、直近に使われたものは捨てない（{@link AtlasCache}）。 */
    private static final int SOFT_LIMIT_FONT_SETS = 64;
    private static final long PROTECT_NANOS = 2_000_000_000L;
    private static final long EXPIRE_NANOS = 30_000_000_000L;
    /** 使われない面の FreeType のハンドルを閉じるまでの時間と、その確認の間隔。 */
    private static final long FACE_IDLE_NANOS = 60_000_000_000L;
    private static long lastFaceTrim = System.nanoTime();

    private record AtlasKey(LoadedFace face, boolean bold, boolean italic, int pixelSizeQ, int scaleQ) {}

    /** 字形の置き場と、その中での字形の番号の割り当て。 */
    private record Atlas(FontSet set, GlyphKey keys) {}

    private static final AtlasCache<AtlasKey, Atlas> ATLASES = new AtlasCache<>(
            SOFT_LIMIT_FONT_SETS, PROTECT_NANOS, EXPIRE_NANOS, System::nanoTime, atlas -> atlas.set().close());
    private static int atlasCounter;

    private OutlineTextRenderer() {}

    /**
     * 文字列を描く。
     *
     * @param x           左端（CSS px、変換行列の座標系）
     * @param baselineY   ベースライン（CSS px）
     * @param deviceScale 1 CSS px が何デバイスピクセルか（画面なら GUI の倍率 × 画面の倍率）
     * @param snap        原点をデバイスピクセルに揃えるか（GUI では揃え、ワールド内では揃えない）
     */
    public static void draw(String text, OutlineFontFace face, FontStyle style, float x, float baselineY, int argb,
                            boolean shadow, Matrix4f pose, MultiBufferSource buffer, float deviceScale, boolean snap,
                            int packedLight) {
        if (text == null || text.isEmpty()) return;
        float a = ((argb >>> 24) & 0xFF) / 255f;
        if (a <= 0) return;
        housekeeping();
        // 右から左の文字を含むなら、描く順（視覚順）に並べ替えてから字を置く（UAX #9）
        text = BidiText.toVisual(text, null);
        float scale = Math.max(0.25f, deviceScale);
        float[] positions = face.positions(text, style);
        if (shadow) {
            // Minecraft の影と同じく 1/9em ずらして 1/4 の明るさで描く
            float off = style.fontSizePx() / 9f;
            drawRun(text, null, face, style, positions, x + off, baselineY + off, shadowOf(argb), pose, buffer, scale, snap, packedLight);
        }
        drawRun(text, null, face, style, positions, x, baselineY, argb, pose, buffer, scale, snap, packedLight);
    }

    /**
     * Minecraft の色コード（{@code §0}〜{@code §f}、{@code §r}）を含む文字列を描く。色コードは字として数えない。
     * テキストエリアの構文の色分けが使う。
     */
    public static void drawFormatted(String formatted, OutlineFontFace face, FontStyle style, float x, float baselineY, int argb,
                                     Matrix4f pose, MultiBufferSource buffer, float deviceScale, boolean snap, int packedLight) {
        if (formatted == null || formatted.isEmpty()) return;
        StringBuilder plain = new StringBuilder(formatted.length());
        int[] colors = new int[formatted.length()];
        int current = argb;
        for (int i = 0; i < formatted.length(); i++) {
            char c = formatted.charAt(i);
            if (c == '§' && i + 1 < formatted.length()) {
                int code = Character.digit(formatted.charAt(i + 1), 16);
                char k = Character.toLowerCase(formatted.charAt(i + 1));
                if (code >= 0) current = (argb & 0xFF000000) | FORMAT_COLORS[code];
                else if (k == 'r') current = argb;
                i++;
                continue;
            }
            colors[plain.length()] = current;
            plain.append(c);
        }
        String text = plain.toString();
        drawRun(text, colors, face, style, face.positions(text, style), x, baselineY, argb, pose, buffer,
                Math.max(0.25f, deviceScale), snap, packedLight);
    }

    /** Minecraft の 16 色（{@code ChatFormatting} と同じ値）。 */
    private static final int[] FORMAT_COLORS = {
            0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
            0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF};

    private static int shadowOf(int argb) {
        return (argb & 0xFF000000) | ((argb & 0xFCFCFC) >> 2);
    }

    private static void drawRun(String text, int[] colors, OutlineFontFace face, FontStyle style, float[] positions, float x,
                                float baselineY, int argb, Matrix4f pose, MultiBufferSource buffer, float scale, boolean snap,
                                int packedLight) {
        float pixelSize = AtlasSizing.quantize(Math.min(style.fontSizePx() * scale, MAX_PIXEL_SIZE));
        float glyphScale = pixelSize / style.fontSizePx();
        float originY = snap ? Math.round(baselineY * scale) / scale : baselineY;

        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            int n = Character.charCount(cp);
            if (cp <= 0x20 || cp == 0xA0 || Character.isWhitespace(cp)) {
                i += n;
                continue;
            }
            var gf = face.glyphFace(cp, style);
            int c = colors != null ? colors[i] : argb;
            // 原点は 1/4 デバイスピクセル単位に置く。整数部は位置で、端数はその端数の位置でラスタライズした字形で表す
            float gx = x + positions[i];
            int subpixel = 0;
            if (snap) {
                float device = gx * scale;
                float whole = (float) Math.floor(device);
                subpixel = Math.round((device - whole) * LoadedFace.SUBPIXEL_STEPS);
                if (subpixel == LoadedFace.SUBPIXEL_STEPS) {
                    whole += 1;
                    subpixel = 0;
                }
                gx = whole / scale;
            }
            BakedGlyph glyph = glyph(gf.face(), gf.syntheticBold(), gf.syntheticItalic(), cp, pixelSize, glyphScale,
                    subpixel, isLightText(c));
            // 字形の上端はベースラインから GlyphProvider.BASELINE（7）を引いた位置が基準
            VertexConsumer vc = buffer.getBuffer(glyph.renderType(Font.DisplayMode.NORMAL));
            glyph.render(false, gx, originY - GlyphProvider.BASELINE, pose, vc,
                    ((c >>> 16) & 0xFF) / 255f, ((c >>> 8) & 0xFF) / 255f, (c & 0xFF) / 255f, ((c >>> 24) & 0xFF) / 255f,
                    packedLight);
            i += n;
        }
    }

    /** 明るい字（sRGB の輝度が 0.4 以上）。暗い背景に置かれる前提で、補正の向きを決める。 */
    private static boolean isLightText(int argb) {
        float luma = (0.2126f * ((argb >>> 16) & 0xFF) + 0.7152f * ((argb >>> 8) & 0xFF) + 0.0722f * (argb & 0xFF)) / 255f;
        return luma >= 0.4f;
    }

    private static BakedGlyph glyph(LoadedFace face, boolean bold, boolean italic, int cp, float pixelSize, float glyphScale,
                                    int subpixel, boolean lightText) {
        AtlasKey key = new AtlasKey(face, bold, italic, AtlasSizing.key(pixelSize), Math.round(glyphScale * 64));
        Atlas atlas = ATLASES.get(key, k -> createAtlas(k, pixelSize, glyphScale));
        return atlas.set().getGlyph(atlas.keys().encode(cp, subpixel, lightText));
    }

    /** 置き場の掃除と、使われない面のハンドルの解放。描画のたびに呼んでよい（時刻の比較だけ）。 */
    private static void housekeeping() {
        ATLASES.sweepIfDue();
        long now = System.nanoTime();
        if (now - lastFaceTrim >= FACE_IDLE_NANOS / 2) {
            lastFaceTrim = now;
            LoadedFace.releaseIdle(FACE_IDLE_NANOS);
        }
    }

    private static Atlas createAtlas(AtlasKey key, float pixelSize, float glyphScale) {
        Minecraft mc = Minecraft.getInstance();
        ResourceLocation name = ResourceLocation.fromNamespaceAndPath(EnderFrameLib.MODID, "outline_text/" + (atlasCounter++));
        FontSet set = new FontSet(mc.getTextureManager(), name);
        GlyphKey keys = new GlyphKey();
        GlyphProvider provider = new FaceGlyphProvider(key.face(), key.bold(), key.italic(), pixelSize, glyphScale, keys);
        set.reload(List.of(new GlyphProvider.Conditional(provider, FontOption.Filter.ALWAYS_PASS)), Set.of());
        return new Atlas(set, keys);
    }

    /** 描画の倍率が変わった（画面の大きさの変更など）ときや資源の再読み込みのときに置き場を捨てる。 */
    public static void clear() {
        ATLASES.clear();
    }

    /** いま持っている置き場の数（テストと診断用）。 */
    public static int atlasCount() {
        return ATLASES.size();
    }

    /** 1 つの面を 1 つの大きさでラスタライズする {@link GlyphProvider}。 */
    private static final class FaceGlyphProvider implements GlyphProvider {
        private final LoadedFace face;
        private final boolean bold;
        private final boolean italic;
        private final float pixelSize;
        /** 1 CSS px あたりのビットマップのピクセル数（字形の四角形の大きさを CSS px に直す）。 */
        private final float oversample;
        private final GlyphKey keys;

        FaceGlyphProvider(LoadedFace face, boolean bold, boolean italic, float pixelSize, float oversample, GlyphKey keys) {
            this.keys = keys;
            this.face = face;
            this.bold = bold;
            this.italic = italic;
            this.pixelSize = pixelSize;
            this.oversample = oversample;
        }

        @Override
        public GlyphInfo getGlyph(int glyphKey) {
            int codePoint = keys.codePoint(glyphKey);
            // FontSet が置き場を作るときに試す番号（割り当て前の空白など）。null を返すと FontSet はこの提供元を外してしまう
            // （対応字を 1 つも引けない提供元は使われない）ので、空白の字形として答える
            if (codePoint < 0) return (GlyphInfo.SpaceGlyphInfo) () -> 1.0f;
            LoadedFace.GlyphBitmap bitmap = face.rasterize(codePoint, pixelSize, bold, italic,
                    GlyphKey.subpixel(glyphKey)).withTextContrast(GlyphKey.lightText(glyphKey));
            if (bitmap.isEmpty()) return (GlyphInfo.SpaceGlyphInfo) () -> 1.0f;
            return new GlyphInfo() {
                @Override
                public float getAdvance() {
                    // 送り位置はこちらで決めるので使わない（FontSet の「怪しい送り幅」の判定に掛からない値）
                    return 1.0f;
                }

                @Override
                public BakedGlyph bake(Function<SheetGlyphInfo, BakedGlyph> baker) {
                    return baker.apply(new Sheet(bitmap, oversample));
                }
            };
        }

        @Override
        public IntSet getSupportedGlyphs() {
            // FontSet は対応字が 1 つも無い提供元を外すので、空白だけ持っていると答える（実際の字は getGlyph が引く）
            return IntSets.singleton(' ');
        }
    }

    private record Sheet(LoadedFace.GlyphBitmap bitmap, float oversample) implements SheetGlyphInfo {
        @Override public int getPixelWidth() { return bitmap.width(); }
        @Override public int getPixelHeight() { return bitmap.height(); }
        @Override public boolean isColored() { return false; }
        @Override public float getOversample() { return oversample; }
        @Override public float getBearingLeft() { return bitmap.left() / oversample; }
        @Override public float getBearingTop() { return bitmap.top() / oversample; }

        @Override
        public void upload(int xOffset, int yOffset) {
            NativeImage image = new NativeImage(NativeImage.Format.LUMINANCE, bitmap.width(), bitmap.height(), false);
            for (int y = 0; y < bitmap.height(); y++) {
                for (int x = 0; x < bitmap.width(); x++) {
                    image.setPixelLuminance(x, y, (byte) bitmap.coverageAt(x, y));
                }
            }
            // 字形の置き場は等倍で使うので最近傍で引く（線形だと原点の端数で半ピクセルぼける）
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            image.upload(0, xOffset, yOffset, 0, 0, bitmap.width(), bitmap.height(), false, true);
        }
    }
}
