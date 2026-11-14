package com.nishiyu.enderframelib.api;

import java.util.Objects;
import java.util.ServiceLoader;
import org.jetbrains.annotations.ApiStatus;

/**
 * エンジンを数行で組み込むための統合エントリーポイント。
 *
 * <pre>{@code
 * // ヘッドレス（CPU・Pixmap）
 * try (WebViewSurface s = EnderEngine.html("<h1>Hello</h1>").size(640, 360).build()) {
 *     s.frame(0, true);
 *     s.writePng(Path.of("out.png"));
 * }
 * // カスタムホスト（描くたびに画素を受け取る）
 * EnderEngine.html(page).size(320, 200).sink((px, w, h) -> upload(px, w, h)).build();
 * // 窓（GLFW + OpenGL。閉じるまで戻らない）
 * EnderEngine.html(page).size(1024, 768).openWindow();
 * }</pre>
 *
 * 描画先は {@link Target}（CPU のヘッドレス / GPU のオフスクリーン）で選び、{@link Builder#sink} でホストへ画素を渡せる。
 * Minecraft は要らない。
 */
@ApiStatus.AvailableSince("1.0")
public final class EnderEngine {
    private EnderEngine() {}

    /** 組み込みの描画先。 */
    public enum Target {
        /** CPU のソフトウェア描画（ヘッドレス。GL 不要。既定）。 */
        SOFTWARE,
        /** GPU のオフスクリーン描画（OpenGL のコンテキストを内部で作る）。 */
        GPU_OFFSCREEN
    }

    /** HTML 文字列から面の組み立てを始める。 */
    public static Builder html(String html) {
        return new Builder(Objects.requireNonNull(html, "html"));
    }

    /**
     * HTML 文字列を使わずに空の文書（{@code <html><head/><body/>}）から面の組み立てを始める。
     * {@link com.nishiyu.enderframelib.api.ui.UI} 等で 1 から画面を構築する用途向け。
     */
    public static Builder empty() {
        return new Builder("");
    }

    private static EnginePlatform platform() {
        for (EnginePlatform p : ServiceLoader.load(EnginePlatform.class, EnginePlatform.class.getClassLoader())) {
            return p;
        }
        throw new IllegalStateException("エンジン本体（EnginePlatform の実装）がクラスパスにありません");
    }

    public static final class Builder {
        private final String html;
        private int width = 800;
        private int height = 600;
        private float dpr = 1f;
        private int clearColor = 0x00000000;
        private Target target = Target.SOFTWARE;
        private PixelSink sink;
        private final java.util.List<SurfaceFont> fonts = new java.util.ArrayList<>();
        private boolean systemFonts = true;
        private ImageSource images;
        private boolean webImages;

        private Builder(String html) {
            this.html = html;
        }

        /** 面の大きさ（CSS px）。既定 800×600。 */
        public Builder size(int widthCss, int heightCss) {
            if (widthCss <= 0 || heightCss <= 0) throw new IllegalArgumentException("size must be positive");
            this.width = widthCss;
            this.height = heightCss;
            return this;
        }

        /** 1 CSS px あたりの画素数。既定 1。 */
        public Builder devicePixelRatio(float ratio) {
            if (!(ratio > 0f)) throw new IllegalArgumentException("devicePixelRatio must be > 0");
            this.dpr = ratio;
            return this;
        }

        /** 背景を塗る色（ARGB）。既定は透明。 */
        public Builder clearColor(int argb) {
            this.clearColor = argb;
            return this;
        }

        /** 描画先を選ぶ。 */
        public Builder target(Target target) {
            this.target = Objects.requireNonNull(target, "target");
            return this;
        }

        /** 描くたびに画素を受け取るカスタムホストの窓口を付ける（{@link PixelSink}）。 */
        public Builder sink(PixelSink sink) {
            this.sink = Objects.requireNonNull(sink, "sink");
            return this;
        }

        /** 書体（TTF / OTF）を持ち込む。同じ名前の OS の書体より先に使われる。読めない書体は {@link #build} が例外にする。 */
        public Builder font(SurfaceFont font) {
            fonts.add(Objects.requireNonNull(font, "font"));
            return this;
        }

        /** {@code font(SurfaceFont.of(family, data))} の近道。 */
        public Builder font(String family, byte[] data) {
            return font(SurfaceFont.of(family, data));
        }

        /** {@code font(SurfaceFont.of(family, file))} の近道。 */
        public Builder font(String family, java.nio.file.Path file) {
            return font(SurfaceFont.of(family, file));
        }

        /** OS の書体を使うか。false なら持ち込んだ書体と内蔵の標準フォントだけ（環境に依らない描画）。既定 true。 */
        public Builder systemFonts(boolean use) {
            this.systemFonts = use;
            return this;
        }

        /** 画像の出所を差し込む。{@code null} を返した {@code src} は既定の解決へ回る。 */
        public Builder images(ImageSource source) {
            this.images = Objects.requireNonNull(source, "source");
            return this;
        }

        /** {@code http(s)://} の画像も取得するか（背景スレッドで取得し、取れたら自動で描き直す。ローカル／LAN は断る）。既定 false。 */
        public Builder webImages(boolean allow) {
            this.webImages = allow;
            return this;
        }

        /** 面を作ってマウントする。 */
        public WebViewSurface build() {
            return platform().create(new EnginePlatform.Spec(html, width, height, dpr, clearColor, target, sink,
                    java.util.List.copyOf(fonts), systemFonts, images, webImages));
        }

        /** GLFW の窓に表示する。窓を閉じるまで戻らない（入力・描画ループは内蔵）。 */
        public void openWindow() {
            platform().openWindow(html, width, height);
        }
    }
}
