package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.api.EnderEngine;
import com.nishiyu.enderframelib.api.EnginePlatform;
import com.nishiyu.enderframelib.api.MouseButton;
import com.nishiyu.enderframelib.api.PixelSink;
import com.nishiyu.enderframelib.api.SurfaceClipboard;
import com.nishiyu.enderframelib.api.SurfaceLifecycleListener;
import com.nishiyu.enderframelib.api.SurfaceState;
import com.nishiyu.enderframelib.api.WebDocument;
import com.nishiyu.enderframelib.api.WebViewSurface;
import com.nishiyu.enderframelib.api.ImageSource;
import com.nishiyu.enderframelib.api.SurfaceFont;
import com.nishiyu.enderframelib.api.SurfaceImage;
import com.nishiyu.enderframelib.core.paint.RenderBackend;
import com.nishiyu.enderframelib.core.paint.software.ImageProvider;
import com.nishiyu.enderframelib.core.paint.software.OverrideImages;
import com.nishiyu.enderframelib.core.text.font.SystemFonts;
import com.nishiyu.enderframelib.core.text.font.WebFonts;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.ArrayList;
import com.nishiyu.enderframelib.core.paint.gl.LwjglGlRenderBackend;
import com.nishiyu.enderframelib.core.paint.software.SoftwareRenderBackend;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.Supplier;
import org.jetbrains.annotations.ApiStatus;

/**
 * {@link WebViewSurface} の実装: {@link StandaloneView}（文書・入力・フレーム）と {@link RenderBackend}（描画先）の組。
 * 利用者は {@link EnderEngine} から作る（{@link Platform} が ServiceLoader で api へつながる）。
 */
@ApiStatus.Internal
public final class EngineSurface implements WebViewSurface {

    /** {@link EnginePlatform} の実装（META-INF/services に登録）。 */
    @ApiStatus.Internal
    public static final class Platform implements EnginePlatform {
        @Override
        public WebViewSurface create(Spec spec) {
            return EngineSurface.create(spec);
        }

        @Override
        public void openWindow(String html, int width, int height) {
            StandaloneViewer.show(html, width, height, v -> {});
        }
    }

    private interface BackendFactory {
        RenderBackend create(int widthCss, int heightCss, float devicePixelRatio);
    }

    private final StandaloneView view;
    private final BackendFactory factory;
    private final PixelSink sink;
    private final float dpr;
    private final ImageProvider images;
    private int seenImages = -1;
    private final UiStateStore store;
    private final SurfaceState state;
    private final List<SurfaceLifecycleListener> listeners = new CopyOnWriteArrayList<>();
    private RenderBackend backend;
    private int w;
    private int h;
    private boolean closed;

    private EngineSurface(StandaloneView view, BackendFactory factory, PixelSink sink, RenderBackend backend, int w, int h, float dpr,
                          ImageProvider images) {
        this.images = images;
        this.view = view;
        this.factory = factory;
        this.sink = sink;
        this.backend = backend;
        this.w = w;
        this.h = h;
        this.dpr = dpr;
        this.store = new UiStateStore("surface", () -> view.ui());
        this.state = new State(store);
    }

    /** {@link UiStateStore} を api の {@link SurfaceState} に見せるだけの薄い皮。 */
    private record State(UiStateStore store) implements SurfaceState {
        @Override public SurfaceState set(String key, Object value) { store.set(key, value); return this; }
        @Override public String get(String key) { return store.get(key); }
        @Override public SurfaceState setList(String key, List<Map<String, String>> rows) { store.setList(key, rows); return this; }
        @Override public SurfaceState watch(String key, Consumer<String> listener) { store.watch(key, listener); return this; }
        @Override public SurfaceState bind(String key, Supplier<?> supplier) { store.bind(key, supplier); return this; }
        @Override public void forceSync() { store.forceSync(); }
    }

    /** 面ごとの書体の目録。持ち込みが無く OS の書体を使うなら共有の目録のまま。読めない書体は例外。 */
    static FontResolver fontsFor(EnginePlatform.Spec s) {
        SystemFonts base = s.systemFonts() ? SystemFonts.system() : new SystemFonts(List.of(), java.util.Locale.getDefault());
        if (s.fonts().isEmpty()) return base;
        List<SystemFonts.Face> extra = new ArrayList<>();
        for (SurfaceFont f : s.fonts()) {
            String name = f.family().trim().toLowerCase(java.util.Locale.ROOT);
            String display = f.family().trim() + " (surface)";
            if (f.data() != null) {
                if (!WebFonts.isUsable(f.data())) throw new IllegalArgumentException("書体として開けません: " + f.family());
                extra.add(new SystemFonts.Face(null, 0, java.util.Set.of(name), display, f.weight(), f.italic(), 5,
                        new SystemFonts.MemoryFont(f.data())));
            } else {
                if (!WebFonts.isUsableFile(f.file())) throw new IllegalArgumentException("書体として開けません: " + f.file());
                extra.add(new SystemFonts.Face(f.file().toAbsolutePath(), 0, java.util.Set.of(name), display, f.weight(), f.italic(), 5));
            }
        }
        return base.withFaces(extra);
    }

    /** 画像の読み方。差し込みがあれば先に引き、無ければ既定（HTTP を許すなら取得つき）。 */
    private static ImageProvider imagesFor(EnginePlatform.Spec s) {
        ImageProvider base = s.webImages() ? ImageProvider.web() : ImageProvider.standard();
        if (s.images() == null) return base;
        ImageSource src = s.images();
        return new OverrideImages(base, path -> {
            SurfaceImage img = src.load(path);
            return img == null ? null : new OverrideImages.Raw(img.width(), img.height(), img.argb(), img.cssWidth(), img.cssHeight());
        });
    }

    static WebViewSurface create(EnginePlatform.Spec s) {
        int clear = s.clearColor();
        FontResolver fonts = fontsFor(s);
        ImageProvider images = imagesFor(s);
        BackendFactory f = switch (s.target()) {
            case SOFTWARE -> (cw, ch, r) -> new SoftwareRenderBackend(cw, ch, r).clearColor(clear).fonts(fonts).images(images);
            case GPU_OFFSCREEN -> (cw, ch, r) -> new LwjglGlRenderBackend(cw, ch, r).fonts(fonts).images(images);
        };
        RenderBackend backend = f.create(s.width(), s.height(), s.devicePixelRatio());
        try {
            StandaloneView view = new StandaloneView(s.html(), s.width(), s.height(), fonts).images(images);
            return new EngineSurface(view, f, s.sink(), backend, s.width(), s.height(), s.devicePixelRatio(), images);
        } catch (RuntimeException e) {
            backend.close();
            closeImages(images);
            throw e;
        }
    }

    private static void closeImages(ImageProvider p) {
        if (p instanceof ImageProvider.Web w) w.close();
        else if (p instanceof OverrideImages o) o.close();
    }

    @Override public int width() { return w; }
    @Override public int height() { return h; }

    @Override
    public void resize(int width, int height) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("size must be positive");
        if (width == w && height == h) return;
        RenderBackend next = factory.create(width, height, dpr);
        RenderBackend old = backend;
        backend = next;
        w = width;
        h = height;
        view.resize(width, height);
        old.close();
        for (SurfaceLifecycleListener l : listeners) l.onResized(width, height);
    }

    @Override
    public boolean frame(long nowMs, boolean force) {
        // 取得中の画像が取れた・アニメーションが動いている間は描き直す（StandaloneViewer と同じ合図）
        ImageProvider.Web web = images instanceof ImageProvider.Web x ? x
                : images instanceof OverrideImages o ? o.web() : null;
        if (web != null) {
            int done = web.fetcher().completedCount();
            if (done != seenImages || web.hasAnimation()) {
                seenImages = done;
                view.invalidate();
            }
        }
        boolean drew = view.frame(nowMs, backend, force);
        if (drew && sink != null) {
            var pm = backend.pixmap();
            sink.accept(pm.pixels(), pm.width(), pm.height());
        }
        for (SurfaceLifecycleListener l : listeners) l.onFrame(nowMs, drew);
        return drew;
    }

    @Override
    public void load(String html) {
        if (html == null) throw new NullPointerException("html");
        view.load(html);
        store.markAllDirty();
        store.syncToDom();
        for (SurfaceLifecycleListener l : listeners) l.onLoaded();
    }

    @Override public void invalidate() { view.invalidate(); }
    @Override public boolean needsFrame() { return view.needsFrame(); }
    @Override public boolean scrollToAnchor(String id) { return view.scrollToAnchor(id); }
    @Override public SurfaceState state() { return state; }
    @Override public void addLifecycleListener(SurfaceLifecycleListener l) { if (l != null) listeners.add(l); }
    @Override public void removeLifecycleListener(SurfaceLifecycleListener l) { listeners.remove(l); }
    @Override public boolean isClosed() { return closed; }

    @Override public int setTimeout(Runnable task, long delayMs) { return view.timers().setTimeout(task, delayMs); }
    @Override public boolean clearTimeout(int id) { return view.timers().clearTimeout(id); }
    @Override public int setInterval(Runnable task, long intervalMs) { return view.timers().setInterval(task, intervalMs); }
    @Override public boolean clearInterval(int id) { return view.timers().clearInterval(id); }
    @Override public int requestAnimationFrame(DoubleConsumer cb) { return cb == null ? -1 : view.timers().requestAnimationFrame(cb::accept); }
    @Override public boolean cancelAnimationFrame(int id) { return view.timers().cancelAnimationFrame(id); }

    @Override public void mouseMove(double x, double y) { view.mouseMove(x, y, backend.sessionId()); }
    @Override public void mouseLeave() { view.mouseLeave(); }
    @Override
    public void mouseButton(MouseButton button, boolean down) {
        if (button == null) throw new NullPointerException("button");
        view.mouseButton(down, button.glfwButton(), backend.sessionId());
    }

    @Override
    public void setClipboard(SurfaceClipboard clipboard) {
        view.setClipboard(clipboard == null ? null : new com.nishiyu.enderframelib.common.TextEditing.Clipboard() {
            @Override public String get() { String t = clipboard.get(); return t == null ? "" : t; }
            @Override public void set(String text) { clipboard.set(text); }
        });
    }
    @Override public boolean scroll(double nx, double ny) { return view.scroll(nx, ny, backend.sessionId()); }
    @Override public void keyDown(int key, int mods) { view.keyDown(key, mods); }
    @Override public void keyUp(int key, int mods) { view.keyUp(key, mods); }
    @Override public boolean charTyped(int codePoint) { return view.charTyped(codePoint); }
    @Override public boolean isPointerOverUi(double x, double y) { return view.isPointerOverUi(x, y); }
    @Override public String focusedId() { String id = view.focusedId(); return id == null || id.isEmpty() ? null : id; }
    @Override public void onNavigate(Consumer<String> handler) { view.onNavigate(handler); }

    @Override public WebDocument document() { return view.ui().document(); }

    @Override public int[] pixels() { return backend.pixmap().pixels(); }
    @Override public int pixelWidth() { return backend.pixmap().width(); }
    @Override public int pixelHeight() { return backend.pixmap().height(); }
    @Override public void writePng(Path file) throws IOException { backend.writePng(file); }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        for (SurfaceLifecycleListener l : listeners) {
            try {
                l.onClosed();
            } catch (RuntimeException ignored) {
                // 通知の失敗で資源の解放を止めない
            }
        }
        try {
            view.close();
        } finally {
            try {
                backend.close();
            } finally {
                closeImages(images);
            }
        }
    }
}
