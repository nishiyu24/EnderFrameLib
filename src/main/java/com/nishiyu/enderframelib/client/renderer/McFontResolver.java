package com.nishiyu.enderframelib.client.renderer;

import com.nishiyu.enderframelib.core.text.FontFace.BitmapFontFace;
import com.nishiyu.enderframelib.core.text.FontFace;
import com.nishiyu.enderframelib.core.text.FontFace.FontResolver;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
public final class McFontResolver implements FontResolver {

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
                return font.width(bold ? ("§l" + text) : text);
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
