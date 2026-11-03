package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.paint.gl.LwjglGlRenderBackend;
import com.nishiyu.enderframelib.core.css.CssParser;
import com.nishiyu.enderframelib.core.net.HttpPolicy;
import com.nishiyu.enderframelib.core.net.SafeHttpClient;
import com.nishiyu.enderframelib.core.paint.software.ImageProvider;
import com.nishiyu.enderframelib.core.text.font.FontFaceRules;
import com.nishiyu.enderframelib.core.text.font.FontSources;
import com.nishiyu.enderframelib.core.paint.software.SoftwareRenderBackend;
import org.lwjgl.glfw.GLFW;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Minecraft なしで HTML/CSS を GLFW のウィンドウ（純 LWJGL の OpenGL）に表示する単体ビューア。
 *
 * <pre>
 * java -cp enderframelib-core.jar:lwjgl-*.jar com.nishiyu.enderframelib.common.StandaloneViewer page.html [幅 高さ]
 * java ... StandaloneViewer https://example.com/ 1024 768
 * java ... StandaloneViewer page.html 800 600 --png out.png [--after 5000]  (ウィンドウなしで CPU 描画して PNG に保存。--after はその ms 後の見た目)
 * </pre>
 *
 * ホバー・押下・クリック・ホイールスクロール・キー入力（Tab・フォーム）・タイマー・{@code transition} / {@code @keyframes} が動く。
 * 変化が無いあいだは描き直さない（CPU / GPU を使わない）。
 */
public final class StandaloneViewer {
    private StandaloneViewer() {}

    public static void main(String[] args) throws Exception {
        launch(args, view -> {});
    }

    /**
     * {@link #main} と同じ。文書を読み込んだ直後に {@code setup} を 1 度呼ぶので、Java から DOM・タイマー・イベントを仕込める
     * （窓でも {@code --png} でも呼ばれる）。
     */
    public static void launch(String[] args, java.util.function.Consumer<StandaloneView> setup) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: StandaloneViewer <file|url> [width height] [--png out.png] [--after ms]");
            System.exit(2);
        }
        String source = args[0];
        int w = 1024, h = 768;
        String png = null;
        int pos = 1;
        if (args.length >= 3 && args[1].matches("\\d+") && args[2].matches("\\d+")) {
            w = Integer.parseInt(args[1]);
            h = Integer.parseInt(args[2]);
            pos = 3;
        }
        long after = 0;
        for (int i = pos; i + 1 < args.length; i += 2) {
            if ("--png".equals(args[i])) png = args[i + 1];
            else if ("--after".equals(args[i])) after = Long.parseLong(args[i + 1]);
        }

        String html = load(source);
        FontSources fonts = installFonts(source);
        if (png != null) {
            StandaloneView view = new StandaloneView(html, w, h);
            setup.accept(view);
            try (SoftwareRenderBackend cpu = new SoftwareRenderBackend(w, h, 1f)) {
                cpu.images(ImageProvider.web());
                long t0 = System.currentTimeMillis();
                view.frame(t0, cpu, true);
                // http の @font-face は取れてから描き直す（最大 5 秒待つ）
                long wait = System.currentTimeMillis() + 5000;
                while (fonts.pending() > 0 && System.currentTimeMillis() < wait) Thread.sleep(20);
                if (fonts.consumeLoaded()) {
                    FontFaceRules.reloadOutlineFonts();
                    view.fontsChanged();
                    view.frame(t0, cpu, true);
                }
                // アニメーション（フェードインなど）が終わった後の見た目。ブラウザの --virtual-time-budget と同じ
                if (after > 0) view.frame(t0 + after, cpu, true);
                cpu.writePng(Path.of(png));
            }
            System.out.println("wrote " + png);
            return;
        }
        run(html, w, h, fonts, setup);
    }

    /**
     * {@code @font-face} を窓でも効かせる: CSS の解析から登録へつなぎ、書体の出所（ファイル・HTTP・data:）を引く。
     * 文書を作る前に呼ぶ。
     */
    static FontSources installFonts(String source) {
        FontSources fonts = new FontSources(new SafeHttpClient(HttpPolicy.publicOnly())).setBase(source);
        FontFaceRules.setFontReader(fonts);
        CssParser.fontFaceCallback = css -> FontFaceRules.extractAndRegister(css, null);
        return fonts;
    }

    static String load(String source) throws Exception {
        if (source.startsWith("http://") || source.startsWith("https://")) {
            HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(10)).build();
            HttpResponse<String> res = client.send(HttpRequest.newBuilder(URI.create(source))
                            .timeout(Duration.ofSeconds(20)).header("User-Agent", "EnderFrameLib").GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return res.body();
        }
        return Files.readString(Path.of(source), StandardCharsets.UTF_8);
    }

    private static void run(String html, int w, int h, FontSources fonts, java.util.function.Consumer<StandaloneView> setup) {
        ImageProvider.Web images = ImageProvider.web();
        try (LwjglGlRenderBackend gl = new LwjglGlRenderBackend(w, h, 1f, true, "EnderFrameLib")) {
            gl.images(images).clearColor(0xFFFFFFFF);
            StandaloneView view = new StandaloneView(html, w, h).images(images);
            setup.accept(view);
            long window = gl.windowHandle();
            String session = gl.sessionId();
            Keys.nameProvider(code -> GLFW.glfwGetKeyName(code, 0)); // 印字キーの表示名は GLFW（配列依存）に聞く
            view.setClipboard(new TextEditing.Clipboard() {
                @Override
                public String get() {
                    String text = GLFW.glfwGetClipboardString(window);
                    return text != null ? text : "";
                }

                @Override
                public void set(String text) {
                    GLFW.glfwSetClipboardString(window, text);
                }
            });

            GLFW.glfwSetCursorPosCallback(window, (win, x, y) -> {
                int[] ww = new int[1], wh = new int[1];
                GLFW.glfwGetWindowSize(win, ww, wh);
                view.mouseMove(x * w / Math.max(1, ww[0]), y * h / Math.max(1, wh[0]), session);
            });
            GLFW.glfwSetCursorEnterCallback(window, (win, entered) -> {
                if (!entered) view.mouseLeave();
            });
            GLFW.glfwSetMouseButtonCallback(window, (win, button, action, mods) -> {
                if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) view.mouseButton(action == GLFW.GLFW_PRESS, session);
            });
            GLFW.glfwSetScrollCallback(window, (win, dx, dy) -> view.scroll(dx, dy, session));
            GLFW.glfwSetKeyCallback(window, (win, key, scancode, action, mods) -> {
                if (action == GLFW.GLFW_RELEASE) view.keyUp(key, mods);
                else view.keyDown(key, mods); // 押下と、押しっぱなしの繰り返し
            });
            GLFW.glfwSetCharCallback(window, (win, codePoint) -> view.charTyped(codePoint));

            int seenImages = -1;
            boolean first = true;
            while (!gl.shouldClose()) {
                gl.pollEvents();
                int done = images.fetcher().completedCount();
                if (done != seenImages || images.hasAnimation()) {
                    seenImages = done;
                    view.invalidate();
                }
                if (fonts.consumeLoaded()) {
                    FontFaceRules.reloadOutlineFonts();
                    view.fontsChanged();
                }
                if (view.frame(System.currentTimeMillis(), gl, first) || first) {
                    gl.present();
                    first = false;
                } else {
                    try {
                        Thread.sleep(8);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        } finally {
            images.close();
            fonts.close();
        }
    }
}
