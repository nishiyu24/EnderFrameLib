package com.nishiyu.enderframelib.client.renderer;

import com.mojang.blaze3d.platform.NativeImage;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.software.ImageProvider;
import com.nishiyu.enderframelib.core.raster.Pixmap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 描画コアの {@link ImageProvider} の Minecraft 版。画像（ネットワーク・リソースパックのテクスチャ）と動画を、
 * コアが読める画素（{@link Pixmap}）にして渡す。{@code raster:}・{@code data:}・SVG は標準の解決に任せる。
 *
 * <p>動画は、従来の描画と同じく再生の維持（音声の位置・{@code keepAlive}）とフレームの更新をここで行い、
 * 届いたフレームを同じ {@code Pixmap} に書き込んで返す（GPU 側は中身の更新を検知して上げ直す）。
 * 描画スレッドでだけ使う。
 */
final class McMediaImages implements ImageProvider {

    static final McMediaImages INSTANCE = new McMediaImages();

    private static final int MAX_CACHED = 128;

    /** 画素にした画像。{@code Pixmap} は同じものを使い回す（GPU のテクスチャの目印になる）。 */
    private final Map<String, Image> cache = new LinkedHashMap<>(64, 0.75f, true);

    // 動画の再生維持に必要な、描画パスごとの文脈
    private boolean playAudio, audio2D;
    private String sessionId = "";
    private BlockPos audioPos;

    private McMediaImages() {}

    /** 描画の前に、そのパスの音声の文脈を渡す。 */
    void begin(PaintRequest pass) {
        this.playAudio = pass.playAudio();
        this.audio2D = pass.is2DAudio();
        this.sessionId = pass.sessionId() == null ? "" : pass.sessionId();
        this.audioPos = pass.audioPos();
    }

    void clear() {
        cache.clear();
    }

    /** この項目をコアで描けるか。描けないもの（エンティティ・アイテム・流体・プレイヤーの頭など）は従来の経路。 */
    static boolean supports(DisplayItem item) {
        String src = item.text();
        if (src == null || src.isEmpty()) return true; // 何も描かない
        if (item.type() == ElementType.VIDEO || item.type() == ElementType.CANVAS) return true;
        if (src.startsWith("raster:") || src.startsWith("data:") || src.startsWith("http://") || src.startsWith("https://")) {
            return true;
        }
        if (src.startsWith("effect:")) return true; // 効果アイコンはスプライトを画素にして渡す
        if (src.startsWith("fluid:")) return true;  // 流体は静止テクスチャを色付けして画素にして渡す
        if (src.indexOf(':') < 0) return false;
        // 名前空間つきの PNG のテクスチャだけ（entity: player: item: などは別の描き方）
        return src.endsWith(".png") && ResourceLocation.tryParse(src) != null;
    }

    @Override
    public Image load(String src, int boxW, int boxH) {
        if (src == null || src.isEmpty()) return null;
        if (src.startsWith("http://") || src.startsWith("https://")) return web(src);
        if (src.startsWith("raster:") || src.startsWith("data:")) return ImageProvider.standard().load(src, boxW, boxH);
        if (src.startsWith("effect:")) return effect(src);
        return resource(src);
    }

    /** 状態効果のアイコン（効果のスプライト）。スプライトが無ければ描かない。 */
    private Image effect(String src) {
        Image hit = cache.get(src);
        if (hit != null) return hit;
        try {
            var holder = net.minecraft.core.registries.BuiltInRegistries.MOB_EFFECT.getHolder(ResourceLocation.parse(src.substring(7).trim()));
            if (holder.isEmpty()) return null;
            var sprite = Minecraft.getInstance().getMobEffectTextures().get(holder.get());
            NativeImage original = sprite.contents().getOriginalImage();
            // 動画のように縦に連なる場合に備え、1 枚目（幅×幅）だけを使う
            int w = sprite.contents().width(), h = sprite.contents().height();
            int[] argb = new int[w * h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int abgr = original.getPixelRGBA(x, y);
                    argb[y * w + x] = (abgr & 0xFF00FF00) | ((abgr & 0xFF) << 16) | ((abgr >> 16) & 0xFF);
                }
            }
            return remember(src, new Image(Pixmap.fromStraightArgb(w, h, argb), w, h));
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public Image load(DisplayItem item, int boxW, int boxH) {
        if (item.type() == ElementType.VIDEO) return video(item);
        if (item.type() == ElementType.CANVAS) return canvas(item);
        if (item.text() != null && item.text().startsWith("fluid:")) return fluid(item);
        return load(item.text(), boxW, boxH);
    }

    /**
     * 流体: 静止テクスチャの 1 枚目に色（{@code getTintColor}）を掛けて渡す。従来の描画と同じく、角丸があれば
     * 引き伸ばし、無ければ 16 単位のタイルで敷き詰める。
     */
    private Image fluid(DisplayItem el) {
        String id = el.text().substring(6).trim();
        boolean tiled = el.radius() <= 0 && el.width() > 0 && el.height() > 0 && (long) el.width() * el.height() <= 4_000_000L;
        String key = "fluid:" + id + (tiled ? "@" + el.width() + "x" + el.height() : "");
        Image hit = cache.get(key);
        if (hit != null) return hit;
        try {
            var fluid = net.minecraft.core.registries.BuiltInRegistries.FLUID.get(ResourceLocation.parse(id));
            if (fluid == net.minecraft.world.level.material.Fluids.EMPTY) return null;
            var ext = net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions.of(fluid);
            ResourceLocation still = ext.getStillTexture();
            if (still == null) return null;
            var sprite = Minecraft.getInstance().getTextureAtlas(net.minecraft.world.inventory.InventoryMenu.BLOCK_ATLAS).apply(still);
            NativeImage original = sprite.contents().getOriginalImage();
            int sw = sprite.contents().width(), sh = sprite.contents().height();
            int tint = ext.getTintColor();
            int[] src = new int[sw * sh];
            for (int y = 0; y < sh; y++) {
                for (int x = 0; x < sw; x++) {
                    src[y * sw + x] = tintArgb(original.getPixelRGBA(x, y), tint);
                }
            }
            if (!tiled) return remember(key, new Image(Pixmap.fromStraightArgb(sw, sh, src), el.width(), el.height()));
            int w = el.width(), h = el.height();
            int[] out = new int[w * h];
            for (int y = 0; y < h; y++) {
                int sy = Math.min(sh - 1, (y % 16) * sh / 16);
                for (int x = 0; x < w; x++) {
                    out[y * w + x] = src[sy * sw + Math.min(sw - 1, (x % 16) * sw / 16)];
                }
            }
            return remember(key, new Image(Pixmap.fromStraightArgb(w, h, out), w, h));
        } catch (Exception e) {
            return null;
        }
    }

    /** NativeImage の ABGR 画素に ARGB の色を掛けて ARGB にする（色のアルファが 0 なら不透明として扱う、従来と同じ）。 */
    static int tintArgb(int abgr, int tintArgb) {
        int a = abgr >>> 24, b = (abgr >> 16) & 0xFF, g = (abgr >> 8) & 0xFF, r = abgr & 0xFF;
        int ta = tintArgb >>> 24;
        if (ta == 0) ta = 255;
        int na = a * ta / 255;
        int nr = r * ((tintArgb >> 16) & 0xFF) / 255;
        int ng = g * ((tintArgb >> 8) & 0xFF) / 255;
        int nb = b * (tintArgb & 0xFF) / 255;
        return (na << 24) | (nr << 16) | (ng << 8) | nb;
    }

    private Image web(String url) {
        Image hit = cache.get(url);
        if (hit != null) return hit;
        WebMediaManager.ImageInfo info = WebMediaManager.getImage(url); // 未取得なら取得を始めて null
        if (info == null) return null;
        AbstractTexture texture = Minecraft.getInstance().getTextureManager().getTexture(info.textureId());
        if (!(texture instanceof DynamicTexture dynamic) || dynamic.getPixels() == null) return null;
        return remember(url, fromNative(dynamic.getPixels()));
    }

    private Image resource(String src) {
        Image hit = cache.get(src);
        if (hit != null) return hit;
        ResourceLocation id = ResourceLocation.tryParse(src);
        if (id == null) return null;
        Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(id);
        if (resource.isEmpty()) return null;
        try (InputStream in = resource.get().open(); NativeImage image = NativeImage.read(in)) {
            return remember(src, fromNative(image));
        } catch (Exception e) {
            return null;
        }
    }

    private Image remember(String key, Image image) {
        cache.put(key, image);
        if (cache.size() > MAX_CACHED) {
            var it = cache.entrySet().iterator();
            it.next();
            it.remove();
        }
        return image;
    }

    private static Image fromNative(NativeImage image) {
        int w = image.getWidth(), h = image.getHeight();
        int[] argb = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int abgr = image.getPixelRGBA(x, y);
                argb[y * w + x] = (abgr & 0xFF00FF00) | ((abgr & 0xFF) << 16) | ((abgr >> 16) & 0xFF);
            }
        }
        return new Image(Pixmap.fromStraightArgb(w, h, argb), w, h);
    }

    /** 描いた回数（{@code modCount}）を最後に {@code Pixmap} へ知らせた時点。キャンバスごと。 */
    private final Map<Pixmap, Long> canvasMods = new java.util.WeakHashMap<>();

    /**
     * {@code <canvas>}: 2D コンテキストの画素（{@code Pixmap}）をそのまま渡す。描き足されたら版を進めて GPU に上げ直させる。
     * 何も描いていなければ描かない（従来の経路と同じ）。
     */
    private Image canvas(DisplayItem el) {
        String session = sessionId.isEmpty() ? "unknown" : sessionId;
        Canvas2DContext ctx = Canvas2DContext.get(session, el.baseId());
        if (ctx == null) return null;
        ctx.adoptSize(el.events());
        return canvasImage(ctx, canvasMods);
    }

    static Image canvasImage(Canvas2DContext ctx, Map<Pixmap, Long> seen) {
        synchronized (ctx) {
            if (ctx.context2d().modCount() == 0) return null;
            Pixmap bitmap = ctx.context2d().bitmap();
            Long last = seen.get(bitmap);
            long now = ctx.context2d().modCount();
            if (last == null || last != now) {
                bitmap.markChanged();
                seen.put(bitmap, now);
            }
            return new Image(bitmap, ctx.width(), ctx.height());
        }
    }

    /** 従来の描画（{@code MediaElementRenderer}）と同じ手順で動画を進め、フレームの画素を返す。 */
    private Image video(DisplayItem el) {
        if (el.text() == null || el.text().isEmpty()) return null;
        WebMediaManager.VideoTexture video = WebMediaManager.getVideoTexture(el.text());
        if (video == null) return null;
        Map<String, String> events = el.events();
        video.applyMediaAttributes(events != null && events.containsKey("media-loop"),
                events != null && events.containsKey("media-muted"));
        if (audio2D) video.keepAlive2D(sessionId);
        else if (playAudio) video.keepAlive(audioPos, sessionId);
        video.updateFrame();
        if (!video.isInitialized) return null;
        Pixmap frame = video.cpuFrame();
        if (frame == null) return null;
        return new Image(frame, video.width, video.height);
    }
}
