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
     * HTML ファイル／URL を使わず、空の文書（{@code about:blank}）から単体ビューアを起動する。
     * 先頭が {@code width height [resizable]} でなければ既定値（1280×800・リサイズ可）を補う。
     */
    public static void launchEmpty(String[] args, java.util.function.Consumer<StandaloneView> setup) throws Exception {
        java.util.List<String> a = new java.util.ArrayList<>();
        a.add("about:blank");
        boolean sized = args.length >= 2 && args[0].matches("[0-9]+") && args[1].matches("[0-9]+");
        boolean hasResizable = sized
                ? (args.length >= 3 && ("true".equalsIgnoreCase(args[2]) || "false".equalsIgnoreCase(args[2])))
                : (args.length >= 1 && ("true".equalsIgnoreCase(args[0]) || "false".equalsIgnoreCase(args[0])));
        boolean resizable = !hasResizable || Boolean.parseBoolean(sized ? args[2] : args[0]);
        int pos = 0;
        if (sized) {
            a.add(args[0]);
            a.add(args[1]);
            pos = 2;
        } else {
            a.add("1280");
            a.add("800");
        }
        a.add(String.valueOf(resizable));
        if (hasResizable) pos++;
        for (int i = pos; i < args.length; i++) a.add(args[i]);
        launch(a.toArray(new String[0]), setup);
    }

    /**
     * {@link #main} と同じ。文書を読み込んだ直後に {@code setup} を 1 度呼ぶので、Java から DOM・タイマー・イベントを仕込める
     * （窓でも {@code --png} でも呼ばれる）。
     */
    public static void launch(String[] args, java.util.function.Consumer<StandaloneView> setup) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: StandaloneViewer <file|url> [width height [resizable]] [--png out.png] [--after ms] [--resizable]");
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
        boolean resizable = false;
        if (pos == 3 && args.length > 3 && (args[3].equalsIgnoreCase("true") || args[3].equalsIgnoreCase("false"))) {
            resizable = Boolean.parseBoolean(args[3]);
            pos = 4;
        }
        long after = 0;
        for (int i = pos; i < args.length; i++) {
            if ("--png".equals(args[i]) && i + 1 < args.length) {
                png = args[++i];
            } else if ("--after".equals(args[i]) && i + 1 < args.length) {
                after = Long.parseLong(args[++i]);
            } else if ("--resizable".equals(args[i])) {
                if (i + 1 < args.length && (args[i + 1].equalsIgnoreCase("true") || args[i + 1].equalsIgnoreCase("false"))) {
                    resizable = Boolean.parseBoolean(args[++i]);
                } else {
                    resizable = true;
                }
            } else if ("true".equalsIgnoreCase(args[i]) || "false".equalsIgnoreCase(args[i])) {
                resizable = Boolean.parseBoolean(args[i]);
            }
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
        run(html, w, h, resizable, fonts, setup);
    }

    /** HTML 文字列を窓に表示する（閉じるまで戻らない）。{@code setup} は文書を読み込んだ直後に 1 度呼ばれる。 */
    public static void show(String html, int w, int h, java.util.function.Consumer<StandaloneView> setup) {
        show(html, w, h, false, setup);
    }

    /** HTML 文字列を窓に表示する（閉じるまで戻らない）。{@code resizable} で可変ウィンドウ（最大化・枠ドラッグリサイズ）を制御する。 */
    public static void show(String html, int w, int h, boolean resizable, java.util.function.Consumer<StandaloneView> setup) {
        run(html, w, h, resizable, installFonts(""), setup);
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
        if ("about:blank".equalsIgnoreCase(source) || source.isEmpty()) {
            return "";
        }
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

    private static void run(String html, int w, int h, boolean resizable, FontSources fonts, java.util.function.Consumer<StandaloneView> setup) {
        ImageProvider.Web images = ImageProvider.web();
        try (LwjglGlRenderBackend gl = new LwjglGlRenderBackend(w, h, 1f, true, "EnderFrameLib", resizable)) {
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

            GLFW.glfwSetFramebufferSizeCallback(window, (win, fbW, fbH) -> {
                if (fbW > 0 && fbH > 0) {
                    gl.resize(fbW, fbH);
                    view.resize(fbW, fbH);
                    if (view.frame(System.currentTimeMillis(), gl, true)) {
                        gl.present();
                    }
                }
            });

            GLFW.glfwSetCursorPosCallback(window, (win, x, y) -> {
                int[] ww = new int[1], wh = new int[1];
                GLFW.glfwGetWindowSize(win, ww, wh);
                int curW = view.width();
                int curH = view.height();
                view.mouseMove(x * curW / Math.max(1, ww[0]), y * curH / Math.max(1, wh[0]), session);
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
