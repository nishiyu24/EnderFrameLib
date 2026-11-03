package com.nishiyu.enderframelib.core.paint.model;

/**
 * 外部（ホストが持つ）GPU テクスチャへの、描画 API に依存しない不透明な参照。画像の項目の {@code src}
 * （{@code texture:<handle>@<幅>x<高さ>[?uv=u0,v0,u1,v1][&flipy]}）として運ぶ。
 *
 * <p>{@code handle} の意味は描画バックエンドが決める（OpenGL ならテクスチャ名＝{@code GL_TEXTURE_2D}、Vulkan なら
 * ホストが払い出す ID など）。コアは数値としてしか扱わない。画素の中身は持たないので、ピクセル配列への読み戻し・複製をせず、
 * アイテムのアイコン・3D ビューの FBO・ミニマップのテクスチャをそのまま貼れる。</p>
 *
 * <p>{@code u0,v0,u1,v1} は貼る範囲（0〜1、<b>見た目の左上が原点</b>）。アトラスの 1 区画を貼るのに使う。
 * {@code flipY} は、テクスチャが下から上へ並んでいる（FBO へ描いたもの）ことを表す。範囲は見た目の向きのまま指定する。
 * テクスチャの補間・折り返しはホストが付けた設定のまま（ドット絵なら {@code GL_NEAREST}）。</p>
 *
 * @param width  テクスチャの幅（画素）。固有の大きさ・範囲の画素への換算に使う
 * @param height テクスチャの高さ（画素）
 */
public record TextureRef(long handle, int width, int height, float u0, float v0, float u1, float v1, boolean flipY) {
    public static final String PREFIX = "texture:";
    private static final String FLIP = "&flipy";

    public TextureRef(long handle, int width, int height) {
        this(handle, width, height, 0f, 0f, 1f, 1f, false);
    }

    /** 範囲の画素（幅）。 */
    public float regionWidth() {
        return (u1 - u0) * width;
    }

    public float regionHeight() {
        return (v1 - v0) * height;
    }

    public boolean isValid() {
        return handle > 0 && width > 0 && height > 0 && u1 > u0 && v1 > v0 && u0 >= 0 && v0 >= 0 && u1 <= 1.0001f && v1 <= 1.0001f;
    }

    /** 画像の項目の {@code src}。 */
    public String src() {
        StringBuilder sb = new StringBuilder(PREFIX).append(handle).append('@').append(width).append('x').append(height);
        if (u0 != 0f || v0 != 0f || u1 != 1f || v1 != 1f) {
            sb.append("?uv=").append(u0).append(',').append(v0).append(',').append(u1).append(',').append(v1);
        }
        if (flipY) sb.append(FLIP);
        return sb.toString();
    }

    public static boolean isTextureSrc(String src) {
        return src != null && src.startsWith(PREFIX);
    }

    /** {@link #src()} の逆。形式が違う・値が不正なら null。 */
    public static TextureRef parse(String src) {
        if (!isTextureSrc(src)) return null;
        try {
            String s = src.substring(PREFIX.length());
            boolean flip = false;
            if (s.endsWith(FLIP)) {
                flip = true;
                s = s.substring(0, s.length() - FLIP.length());
            }
            float u0 = 0f, v0 = 0f, u1 = 1f, v1 = 1f;
            int q = s.indexOf("?uv=");
            if (q >= 0) {
                String[] p = s.substring(q + 4).split(",");
                if (p.length != 4) return null;
                u0 = Float.parseFloat(p[0]);
                v0 = Float.parseFloat(p[1]);
                u1 = Float.parseFloat(p[2]);
                v1 = Float.parseFloat(p[3]);
                s = s.substring(0, q);
            }
            int at = s.indexOf('@');
            int x = at < 0 ? -1 : s.indexOf('x', at);
            if (at < 0 || x < 0) return null;
            TextureRef r = new TextureRef(Long.parseLong(s.substring(0, at)), Integer.parseInt(s.substring(at + 1, x)),
                    Integer.parseInt(s.substring(x + 1)), u0, v0, u1, v1, flip);
            return r.isValid() ? r : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
