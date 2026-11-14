package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.api.event.UIEvents;
import com.nishiyu.enderframelib.core.css.CssProperty;
import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.event.CoreEvent;
import com.nishiyu.enderframelib.core.event.DefaultActionContext;
import com.nishiyu.enderframelib.core.event.StandardDefaultActions;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.paint.DisplayListBuilder;
import com.nishiyu.enderframelib.core.paint.RenderBackend;
import com.nishiyu.enderframelib.core.paint.UIHitResult;
import com.nishiyu.enderframelib.core.layout.model.LayoutMetrics;
import com.nishiyu.enderframelib.core.css.model.AnimationSpec;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.model.ImageFitMode;
import com.nishiyu.enderframelib.core.paint.model.RenderMeta;
import com.nishiyu.enderframelib.core.text.font.OutlineFontFace;
import com.nishiyu.enderframelib.core.text.font.SystemFonts;
import com.nishiyu.enderframelib.core.text.model.FontFace;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import com.nishiyu.enderframelib.core.text.model.FontStyle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Minecraft なしで HTML/CSS を「生きた画面」として動かすホスト。{@link UiView}（文書・レイアウト・無効化・
 * 状態・タイマー）と {@link UiInput}（ポインタ・クリック合成・フォーム）の上に載り、窓の入力と描画ループだけを足す。
 * {@link #frame} を毎フレーム呼ぶと、{@code requestAnimationFrame} / タイマー → トランジション／アニメーション →
 * 必要な分の再スタイル・再レイアウトの順に進めて {@link RenderBackend} に描く。
 *
 * <p>座標はすべて CSS px（文書の左上が原点）。ウィンドウ側の入力（GLFW / Swing など）は
 * {@link #mouseMove} / {@link #mouseButton} / {@link #scroll} / {@link #keyDown} / {@link #charTyped} に渡すだけでよい。
 * キーは GLFW のキーコード。スレッドセーフではない。
 *
 * <p>フォーカスは、クリックしたフォーカスできる要素と {@code Tab} / {@code Shift+Tab}（{@code tabindex} の正の値が先）で移り、
 * ボタンやリンクなどでは十字キーで空間移動する（{@link UiFocus}）。テキスト欄は {@link TextEditing} の規則で、
 * キャレット移動・範囲選択（Shift・ドラッグ）・単語単位・Ctrl+A/C/X/V・複数行の上下移動ができる。
 * {@code <select>} は押すと {@link SelectPopup} が開き、クリックか {@code ↑↓ Enter} で選ぶ。キャレットの点滅はしない
 * （アイドル時は描き直さないため）。
 * タイマーは実時計（{@code System.currentTimeMillis()}）で数え、{@link #frame} のたびに期限が来たものを実行する。
 */
public final class StandaloneView implements AutoCloseable {
    private static final float WHEEL_PIXELS = 40f;
    /** スクロールが止まってから、平行移動で使い回した項目を厳密に作り直すまでの待ち（ms）。 */
    private static final long SETTLE_DELAY_MS = 120;

    /** 窓のための {@link UiView}。フォントと、時計（フレームの時刻）と、画面外の箱を省く描画だけ差し込む。 */
    private final class Ui extends UiView {
        Ui() {
            super("standalone");
        }

        @Override
        protected FontResolver fontResolver() {
            return fonts;
        }

        @Override
        protected long nowMs() {
            return lastNow;
        }

        @Override
        protected List<DisplayItem> paintBoxes(LayoutBox root, int w, int h, boolean scrollOnly) {
            List<DisplayItem> items = super.paintBoxes(root, w, h, scrollOnly);
            DisplayListBuilder b = paintBuilder();
            settlePending = scrollOnly && b != null && b.translatedHits() > 0;
            lastScrollPaintAt = lastNow;
            return items;
        }
    }

    private int width;
    private int height;
    /** 直近のスクロールの作り直しが前回の項目の平行移動を含む（止まったら厳密に描き直す）。 */
    private boolean settlePending;
    private long lastScrollPaintAt;
    private final FontResolver fonts;
    private final Ui ui;
    private final UiInput input;
    private final InteractionState interaction;

    private List<DisplayItem> items;
    private boolean drawn;
    private boolean redraw = true;
    private boolean animating;
    private long compositorUntil;
    private long lastNow;
    private String timings = "";
    /** ホイールで動かしている最中のスクロール先（節 → {x, y}）。毎フレーム少しずつ近づける。 */
    private final Map<HtmlNode, float[]> scrollTargets = new HashMap<>();
    private double mouseX = -1, mouseY = -1;
    private boolean mouseDown;
    /** 押している左以外のボタン（GLFW の番号）。無ければ -1。 */
    private int otherButton = -1;
    private Consumer<String> navigation = href -> {};
    private final Map<HtmlNode, TextEditing.State> edits = new HashMap<>();
    private TextEditing.Clipboard clipboard = TextEditing.Clipboard.inMemory();
    private final SelectPopup popup = new SelectPopup();
    /** ドラッグで範囲選択している最中のテキスト欄。 */
    private HtmlNode dragField;
    /** 押下をポップアップが使ったので、対になる解放は文書へ渡さない。 */
    private boolean swallowRelease;
    private java.util.function.LongSupplier clickClock = System::currentTimeMillis;
    private long lastClickAt = Long.MIN_VALUE;
    private double lastClickX, lastClickY;
    private int clickCount;
    private HtmlNode lastClickField;

    public StandaloneView(String html, int width, int height) {
        this(html, width, height, SystemFonts.system());
    }

    public StandaloneView(String html, int width, int height, FontResolver fonts) {
        this.width = width;
        this.height = height;
        this.fonts = fonts;
        com.nishiyu.enderframelib.core.paint.software.ImageSizes.installDefault();
        this.ui = new Ui();
        this.ui.alphaMaskProvider(com.nishiyu.enderframelib.core.paint.software.AlphaMasks.from(com.nishiyu.enderframelib.core.paint.software.ImageProvider.standard()));
        this.input = new UiInput(ui);
        this.interaction = ui.interactionState();
        long t0 = System.nanoTime();
        ui.loadLocalHtml(html, width, height);
        this.items = ui.renderCache();
        timings = "load=" + (System.nanoTime() - t0) / 1_000_000 + "ms items=" + items.size();
    }

    /** 文書を別の HTML に差し替える（入力中の編集・ポップアップ・スクロール先は捨てる）。 */
    public void load(String html) {
        popup.close();
        dragField = null;
        swallowRelease = false;
        scrollTargets.clear();
        edits.clear();
        ui.loadLocalHtml(html, width, height);
        items = ui.renderCache();
        redraw = true;
        drawn = false;
    }

    /** 下の {@link UiView}（DOM・タイマー・状態を直接触りたいとき）。 */
    public UiView ui() {
        return ui;
    }

    /** 文書の DOM。 */
    public HtmlNode root() {
        return ui.rootNode();
    }

    /** 直近の再レイアウトの内訳（調べる用）。 */
    public String lastTimings() {
        return timings;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** 直近のフレームのディスプレイリスト。 */
    public List<DisplayItem> items() {
        return items;
    }

    /** {@code requestAnimationFrame} / {@code setTimeout} の登録簿。 */
    public UiTimers timers() {
        return ui.timers();
    }

    /**
     * Runs a declarative React/JS-styled UI setup block bound to this view's DOM root and timers.
     */
    public void mountApp(Runnable app) {
        mountApp(timers(), app);
    }

    /**
     * Runs a declarative React/JS-styled UI setup block bound to this view's DOM root and {@code timers}.
     */
    public void mountApp(UiTimers timers, Runnable app) {
        com.nishiyu.enderframelib.api.ui.UI.Scheduler scheduler = timers == null ? null : new com.nishiyu.enderframelib.api.ui.UI.Scheduler() {
            @Override public int setInterval(Runnable task, long intervalMs) { return timers.setInterval(task, intervalMs); }
            @Override public int setTimeout(Runnable task, long delayMs) { return timers.setTimeout(task, delayMs); }
            @Override public boolean clearInterval(int id) { return timers.clearInterval(id); }
            @Override public boolean clearTimeout(int id) { return timers.clearTimeout(id); }
            @Override public int requestAnimationFrame(Consumer<Double> callback) { return timers.requestAnimationFrame(callback); }
            @Override public boolean cancelAnimationFrame(int id) { return timers.cancelAnimationFrame(id); }
        };
        com.nishiyu.enderframelib.api.ui.UI.withContext(root(), scheduler, app);
    }

    /** クリップボードの実体（窓では GLFW のクリップボードにつなぐ）。既定はこのビューの中だけのメモリ。 */
    public void setClipboard(TextEditing.Clipboard clipboard) {
        this.clipboard = clipboard != null ? clipboard : TextEditing.Clipboard.inMemory();
    }

    /** 開いている {@code <select>} のポップアップ。 */
    public SelectPopup selectPopup() {
        return popup;
    }

    /** テキスト欄のキャレット／選択（まだ編集していなければ末尾）。 */
    public TextEditing.State editState(HtmlNode field) {
        TextEditing.State st = edits.computeIfAbsent(field, f -> new TextEditing.State(valueOf(f).length()));
        st.clampTo(valueOf(field));
        return st;
    }

    /** フォーカス中の要素の id。無ければ空。 */
    public String focusedId() {
        HtmlNode f = interaction.focusedNode();
        return f != null ? f.key() : "";
    }

    /** 見た目が変わりうる状態か（入力の反映待ち・アニメーション・タイマー）。false なら描き直さなくてよい。 */
    public boolean needsFrame() {
        return redraw || animating || settlePending || lastNow < compositorUntil || !scrollTargets.isEmpty()
                || ui.invalidationLevel() != UiView.InvalidationLevel.NONE || interaction.hasDirtyNodes()
                || !ui.timers().isEmpty();
    }

    /**
     * 書体の登録が変わった（{@code @font-face} の取得完了など）。文字の幅が変わりうるので測り直して再レイアウトする。
     * 描画スレッドから呼ぶ。
     */
    public void fontsChanged() {
        com.nishiyu.enderframelib.core.layout.LayoutBox.invalidateAllMeasurements();
        ui.markInvalidation(UiView.InvalidationLevel.RELAYOUT);
        redraw = true;
    }

    /**
     * 描画側と同じ画像の読み方を、透過ヒットテスト（{@code <img data-hit-alpha>}）にも使う。既定は {@link com.nishiyu.enderframelib.core.paint.software.ImageProvider#standard()}。
     */
    public StandaloneView images(com.nishiyu.enderframelib.core.paint.software.ImageProvider provider) {
        ui.alphaMaskProvider(com.nishiyu.enderframelib.core.paint.software.AlphaMasks.from(provider));
        return this;
    }

    /** 点が対話要素の上にあるか（CSS px）。ゲームが、クリックを UI に渡すか自分で処理するかを決めるのに使う。 */
    public boolean isPointerOverUi(double x, double y) {
        return ui.isPointerOverUi(x, y);
    }

    /** 外の事情（画像の取得完了など）で描き直したいとき。 */
    public void invalidate() {
        redraw = true;
    }

    /**
     * 時刻 {@code nowMs} まで進めて {@code backend} に描く。
     *
     * @return 描いたか（変化が無く {@code force} でもなければ描かない）
     */
    public boolean frame(long nowMs, RenderBackend backend, boolean force) {
        ui.adoptSessionId(backend.sessionId());
        long prevNow = lastNow;
        lastNow = nowMs;
        interaction.setAnimationTime(nowMs);
        ui.timers().runAnimationFrames((double) nowMs);
        ui.timers().runDueTimers();
        stepScroll(prevNow == 0 ? 16 : nowMs - prevNow);
        // スクロールが止まって少し経ったら、平行移動で使い回した項目を厳密な位置の項目に作り直す
        if (settlePending && scrollTargets.isEmpty() && nowMs - lastScrollPaintAt >= SETTLE_DELAY_MS
                && ui.invalidationLevel() == UiView.InvalidationLevel.NONE) {
            ui.markInvalidation(UiView.InvalidationLevel.REPAINT);
        }

        List<DisplayItem> before = ui.renderCache();
        long t0 = System.nanoTime();
        ui.flushInvalidation();
        List<DisplayItem> after = ui.renderCache();
        boolean rebuilt = before != after;
        boolean running = interaction.animator().isRunning(nowMs);
        if (rebuilt) {
            items = after;
            timings = "flush=" + (System.nanoTime() - t0) / 1_000_000 + "ms items=" + items.size() + " " + ui.lastBreakdown();
            // レイアウト中に新しく走り出したアニメーションも拾う
            compositorUntil = compositorUntil(items, nowMs);
        }
        boolean wasAnimating = animating;
        animating = running;
        boolean wasRedraw = redraw;
        redraw = false;
        if (!rebuilt && !force && !wasRedraw && drawn && !wasAnimating && prevNow >= compositorUntil) {
            return false; // 窓を抜けた最初の1枚は最終値のために描く
        }
        backend.render(withOverlays(items), nowMs);
        drawn = true;
        return true;
    }

    /**
     * opacity / transform / 色の {@code @keyframes} と {@code transition} は描画側（CssAnimator）が補間するので、
     * 再レイアウトの後は、その長さの間（無限なら続けて）描き続ける。いつまでかを返す。
     */
    private static long compositorUntil(List<DisplayItem> items, long nowMs) {
        long until = nowMs;
        for (DisplayItem it : items) {
            AnimationSpec spec = it.animDef();
            if (spec != null && spec.durationMs() > 0 && !spec.frames().isEmpty()) {
                if (spec.infinite()) return Long.MAX_VALUE;
                until = Math.max(until, nowMs + spec.delayMs() + (long) Math.ceil(spec.durationMs() * spec.iterationCount()) + 50);
            }
            if (it.transitionDuration() > 0) until = Math.max(until, nowMs + it.transitionDuration() + 50);
        }
        return until;
    }

    // ------------------------------------------------------------------
    // ポインタ
    // ------------------------------------------------------------------

    /** マウス位置（CSS px）。ホバー中の要素が変われば再スタイルを予約する。押している間はドラッグ（range など）も進む。 */
    public void mouseMove(double x, double y, String sessionId) {
        ui.adoptSessionId(sessionId);
        mouseX = x;
        mouseY = y;
        if (popup.isOpen()) {
            popup.hover(x, y);
            redraw = true;
            if (popup.contains(x, y)) return;
        }
        input.hover(x, y);
        if (mouseDown) input.drag(x, y);
        if (mouseDown && dragField != null) {
            TextEditing.State st = editState(dragField);
            int at = caretAt(dragField, x, y);
            if (at >= 0 && at != st.caret) {
                st.caret = at;
                redraw = true;
            }
        }
    }

    /** ウィンドウ外へ出た。 */
    public void mouseLeave() {
        mouseX = mouseY = -1;
        ui.setHoveredElement("");
    }

    /**
     * 左ボタンの押下／解放。押している間は {@code :active}。押した要素の上で離すと {@code click} を発火し、
     * 既定動作（{@code <details>} の開閉・チェックボックス・{@code <a href="#id">} のスクロールなど）を実行する。
     * 押した要素（またはその祖先）がフォーカスできればフォーカスが移り、そうでなければ外れる。
     */
    public void mouseButton(boolean down, String sessionId) {
        mouseButton(down, 0, sessionId);
    }

    /**
     * ボタン指定つき（GLFW の番号: 0=左・1=右・2=中）。右・中は押下で {@code pointerdown} / {@code mousedown}、押した要素の上で
     * 離すと右は {@code contextmenu}、右と中は {@code auxclick} を送る。ポップアップは閉じるだけで、フォーカス移動・
     * テキスト選択・{@code :active} は左だけ。
     */
    public void mouseButton(boolean down, int hostButton, String sessionId) {
        ui.adoptSessionId(sessionId);
        if (hostButton != 0) {
            if (hostButton < 0 || hostButton > 2) return;
            mouseButtonOther(down, hostButton);
            return;
        }
        if (down) {
            if (popup.isOpen()) {
                // ポップアップが開いている間の押下は、選ぶか閉じるかだけ。閉じる押下が select 自身なら開き直さない
                String chosen = popup.valueAt(mouseX, mouseY);
                boolean inside = popup.contains(mouseX, mouseY);
                HtmlNode owner = popup.select();
                if (chosen != null) commitSelect(owner, chosen);
                popup.close();
                redraw = true;
                if (inside || ownerUnderPointer(owner)) {
                    swallowRelease = true;
                    return;
                }
            }
            mouseDown = true;
            UiInput.PressResult pressed = input.press(mouseX, mouseY, 0, UiInput.Host.NONE);
            HtmlNode target = pressed != null ? pressed.pressedNode() : null;
            HtmlNode focusable = UiFocus.nearestFocusable(target);
            if (focusable != interaction.focusedNode()) {
                ui.setFocusedElement(focusable != null ? focusable.key() : "", false);
            }
            dragField = null;
            if (focusable != null && isTextControl(focusable)) {
                TextEditing.State st = editState(focusable);
                int at = caretAt(focusable, mouseX, mouseY);
                int count = countClick(focusable);
                if (at >= 0) {
                    String text = valueOf(focusable);
                    if (count == 2) {
                        int[] w = TextEditing.wordRange(text, at);
                        st.anchor = w[0];
                        st.caret = w[1];
                    } else if (count >= 3) {
                        int[] l = "textarea".equalsIgnoreCase(focusable.tag)
                                ? TextEditing.lineRange(text, at) : new int[]{0, text.length()};
                        st.anchor = l[0];
                        st.caret = l[1];
                    } else {
                        st.set(at, false);
                    }
                }
                dragField = count >= 2 ? null : focusable; // 単語・行の選択はドラッグで崩さない
                redraw = true;
            } else if (focusable != null && "select".equalsIgnoreCase(focusable.tag)) {
                openSelect(focusable);
            }
            return;
        }
        mouseDown = false;
        dragField = null;
        if (swallowRelease) {
            swallowRelease = false;
            return;
        }
        input.release(mouseX, mouseY, 0, true, linkSink);
    }

    /** 左以外のボタン。押しているのは 1 つのボタンだけとして扱う（別のボタンの押下中の押下は無視）。 */
    private void mouseButtonOther(boolean down, int hostButton) {
        if (down) {
            if (mouseDown || otherButton != -1) return;
            if (popup.isOpen()) {
                popup.close();
                redraw = true;
                swallowRelease = true;
                otherButton = hostButton;
                return;
            }
            otherButton = hostButton;
            input.press(mouseX, mouseY, hostButton, UiInput.Host.NONE);
            return;
        }
        if (otherButton != hostButton) return;
        otherButton = -1;
        if (swallowRelease) {
            swallowRelease = false;
            return;
        }
        input.release(mouseX, mouseY, hostButton, true, linkSink);
    }

    /** 同じ場所を続けて押した回数（500ms・4px 以内で積む）。 */
    private int countClick(HtmlNode field) {
        long now = clickClock.getAsLong();
        boolean chained = field == lastClickField && now - lastClickAt <= 500
                && Math.abs(mouseX - lastClickX) <= 4 && Math.abs(mouseY - lastClickY) <= 4;
        clickCount = chained ? clickCount + 1 : 1;
        lastClickAt = now;
        lastClickX = mouseX;
        lastClickY = mouseY;
        lastClickField = field;
        return clickCount;
    }

    /** ダブルクリックの判定に使う時計（ミリ秒）。テスト用。 */
    public void setClickClock(java.util.function.LongSupplier clock) {
        this.clickClock = clock != null ? clock : System::currentTimeMillis;
    }

    /**
     * 表示領域の大きさ（CSS px）が変わった。次の {@link #frame} で再レイアウトする
     * （連続したリサイズは {@link UiView} が間引く）。描画先のバックエンドは呼び出し側が同じ大きさで作り直す。
     */
    public void resize(int newWidth, int newHeight) {
        if (newWidth <= 0 || newHeight <= 0 || (newWidth == width && newHeight == height)) return;
        width = newWidth;
        height = newHeight;
        popup.close();
        ui.requestResize(newWidth, newHeight);
        redraw = true;
    }

    /** ナビゲーション（{@code href} が {@code #id} 以外）の受け皿。既定は何もしない。 */
    public void onNavigate(Consumer<String> handler) {
        this.navigation = handler != null ? handler : href -> {};
    }

    /** リンクの遷移先の振り分け: {@code #id} は同じページ内のスクロール、それ以外はホストへ。 */
    private final UiInput.ClickSink linkSink = new UiInput.ClickSink() {
        @Override
        public void action(String action, DisplayItem clicked, int button) {
            if (action == null) return;
            if (action.startsWith("#")) scrollToAnchor(action.substring(1));
            else navigation.accept(action);
        }
    };

    /** {@code #id} へ、その要素を囲むスクロール箱を動かして寄せる。 */
    public boolean scrollToAnchor(String id) {
        HtmlNode node = id == null || id.isEmpty() ? null : ui.rootNode().getElementById(id);
        if (node == null) return false;
        // 1 つの節が複数の部品（背景・本体）に分かれるので、節の id で束ねる。スクロールする部品を使う
        Map<String, DisplayItem> scrollers = new HashMap<>();
        for (DisplayItem it : items) {
            if (it.hints().scrollable()) scrollers.put(it.baseId(), it);
        }
        // 画面の外の項目は作っていないので、位置はレイアウトの箱から読む
        LayoutBox targetBox = UiFocus.findBox(ui.layoutRoot(), node);
        if (targetBox == null) return false;
        for (HtmlNode p = node.parent; p != null; p = p.parent) {
            DisplayItem box = scrollers.get(p.key());
            if (box == null) continue;
            float limit = Math.max(0, box.hints().scrollHeight() - box.height());
            if (limit <= 0) continue;
            float cur = ui.scrollState().getScrollY(p);
            float next = clamp(cur + (targetBox.y - box.y()), limit);
            scrollTargets.put(p, new float[]{ui.scrollState().getScrollX(p), next});
            return true;
        }
        return false;
    }

    /** ホイール（上に回すと正）。{@code wheel} イベントを送り、止められなければカーソルの下のスクロールできる箱を動かす。 */
    public boolean scroll(double notchesX, double notchesY, String sessionId) {
        ui.adoptSessionId(sessionId);
        UIHitResult top = ui.hitTest(mouseX, mouseY);
        if (top != null) {
            HtmlNode node = ui.findInteractiveNode(top.element().baseId());
            if (node != null) {
                var wheel = CoreEvent.wheel(top.localX(), top.localY(), notchesX, notchesY, false, false, false, false);
                node.dispatchEvent(wheel);
                if (wheel.isDefaultPrevented()) return true;
            }
        }
        for (UIHitResult hit : ui.hitTestIndex().scrollContainersAt(ui.sessionId(), mouseX, mouseY)) {
            DisplayItem el = hit.element();
            HtmlNode node = ui.findNode(el.baseId());
            if (node == null) continue;
            float limitX = Math.max(0, el.hints().scrollWidth() - el.width());
            float limitY = Math.max(0, el.hints().scrollHeight() - el.height());
            if (limitX <= 0 && limitY <= 0) continue;
            // 連続して回したときは、いまの現在地ではなく前回のスクロール先から積む
            float[] base = scrollTargets.get(node);
            float curX = base != null ? base[0] : ui.scrollState().getScrollX(node);
            float curY = base != null ? base[1] : ui.scrollState().getScrollY(node);
            float nextX = clamp(curX - (float) notchesX * WHEEL_PIXELS, limitX);
            float nextY = clamp(curY - (float) notchesY * WHEEL_PIXELS, limitY);
            if (Math.abs(nextX - curX) > 0.1f || Math.abs(nextY - curY) > 0.1f) {
                scrollTargets.put(node, new float[]{nextX, nextY});
                return true;
            }
        }
        return false;
    }

    /** スクロール先へ近づける（指数的に減速。60Hz なら 1 フレームで残りの 3 割ずつ、近ければ吸着）。 */
    private void stepScroll(long dtMs) {
        if (scrollTargets.isEmpty() || dtMs <= 0) return; // 時間が進んでいなければ、減速も進めない
        // 1 フレームで残りの 3 割（60Hz のとき）を、経過時間に直して掛ける: フレームの時間が揺れても速さが揺れない
        float keep = (float) Math.pow(0.7, Math.min(100, dtMs) / 16.667);
        boolean moved = false;
        var it = scrollTargets.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            HtmlNode node = e.getKey();
            float[] t = e.getValue();
            float cx = ui.scrollState().getScrollX(node), cy = ui.scrollState().getScrollY(node);
            float nx = Math.abs(t[0] - cx) < 0.5f ? t[0] : cx + (t[0] - cx) * (1 - keep);
            float ny = Math.abs(t[1] - cy) < 0.5f ? t[1] : cy + (t[1] - cy) * (1 - keep);
            if (nx != cx || ny != cy) {
                ui.scrollState().setScroll(node, nx, ny);
                moved = true;
            }
            if (nx == t[0] && ny == t[1]) it.remove();
        }
        if (moved) ui.requestScrollReposition();
    }

    private static float clamp(float v, float max) {
        return Math.max(0, Math.min(max, v));
    }

    // ------------------------------------------------------------------
    // キーボード・フォーカス・フォーム
    // ------------------------------------------------------------------

    /**
     * キーの押下（GLFW のキーコードと修飾キー）。{@code keydown} を（フォーカス中の要素、無ければ文書へ）送り、
     * 止められなければ既定動作を行う: {@code Tab} でフォーカス移動、ボタン・リンクの {@code Enter}、
     * チェックボックス・ボタンの {@code Space}、テキスト欄の {@code Backspace} / {@code Delete} / {@code Enter}。
     */
    public void keyDown(int key, int mods) {
        HtmlNode focused = interaction.focusedNode();
        HtmlNode target = focused != null ? focused : ui.rootNode();
        boolean shift = (mods & Keys.MOD_SHIFT) != 0;
        UIEvents.Keyboard ev = input.keyEvent("keydown", target, key, (mods & Keys.MOD_CONTROL) != 0, shift,
                (mods & Keys.MOD_ALT) != 0, (mods & Keys.MOD_SUPER) != 0);
        if (ev == null || ev.isDefaultPrevented()) return;

        if (popup.isOpen() && selectKey(key)) return;
        if (key == Keys.TAB) {
            moveFocus(shift ? -1 : 1);
            return;
        }
        if (focused == null) return;
        boolean enter = key == Keys.ENTER || key == Keys.KP_ENTER;
        if (isTextControl(focused)) {
            editKey(focused, key, mods, enter, ev);
            return;
        }
        if ("select".equalsIgnoreCase(focused.tag)) {
            if (enter || key == Keys.SPACE || key == Keys.UP || key == Keys.DOWN) openSelect(focused);
            return;
        }
        if (key == Keys.UP || key == Keys.DOWN || key == Keys.LEFT || key == Keys.RIGHT) {
            HtmlNode to = UiFocus.spatial(ui, focused, key);
            if (to != null) ui.setFocusedElement(to.key(), true);
            return;
        }
        String tag = focused.tag != null ? focused.tag.toLowerCase(Locale.ROOT) : "";
        boolean activates = tag.equals("a") ? enter
                : (tag.equals("button") || isCheckable(focused)) && (enter || key == Keys.SPACE);
        if (activates && !focused.attrs.containsKey("disabled")) {
            input.dispatchClick(focused, 0, 0, 0, null, linkSink);
        }
    }

    /** キーの解放。{@code keyup} をフォーカス中の要素（無ければ文書）へ送る。 */
    public void keyUp(int key, int mods) {
        HtmlNode focused = interaction.focusedNode();
        input.keyEvent("keyup", focused != null ? focused : ui.rootNode(), key,
                (mods & Keys.MOD_CONTROL) != 0, (mods & Keys.MOD_SHIFT) != 0,
                (mods & Keys.MOD_ALT) != 0, (mods & Keys.MOD_SUPER) != 0);
    }

    /** 文字の入力（Unicode のコードポイント）。フォーカス中のテキスト欄の末尾に足す。入ったか。 */
    public boolean charTyped(int codePoint) {
        HtmlNode focused = interaction.focusedNode();
        if (focused == null || !isTextControl(focused) || codePoint < 0x20 || codePoint == 0x7F) return false;
        String s = new String(Character.toChars(codePoint));
        return applyEdit(focused, TextEditing.type(editState(focused), valueOf(focused), s));
    }

    /** IME 未確定入力の開始（compositionstart）。 */
    public void compositionStart() {
        HtmlNode focused = interaction.focusedNode();
        if (focused == null || !isTextControl(focused)) return;
        TextEditing.State st = editState(focused);
        st.startComposition();
        focused.dispatchEvent(CoreEvent.composition("compositionstart", ""));
        redraw = true;
    }

    /** IME 未確定文字列の更新（compositionupdate）。 */
    public void compositionUpdate(String data, int cursor) {
        HtmlNode focused = interaction.focusedNode();
        if (focused == null || !isTextControl(focused)) return;
        TextEditing.State st = editState(focused);
        if (!st.isComposing()) st.startComposition();
        st.updateComposition(data, cursor);
        focused.dispatchEvent(CoreEvent.composition("compositionupdate", st.compositionText));
        redraw = true;
    }

    /** IME の確定または取り消し（compositionend）。確定文字列があればテキスト欄に反映する。 */
    public void compositionEnd(String data) {
        HtmlNode focused = interaction.focusedNode();
        if (focused == null || !isTextControl(focused)) return;
        TextEditing.State st = editState(focused);
        String committed = data != null ? data : st.endComposition();
        st.endComposition();
        focused.dispatchEvent(CoreEvent.composition("compositionend", committed));
        if (committed != null && !committed.isEmpty()) {
            applyEdit(focused, TextEditing.type(st, valueOf(focused), committed));
        }
        redraw = true;
    }

    /** フォーカス中のテキスト欄のキャレット表示矩形 {x, y, w, h}（CSS px）。IME 候補窓の配置用。 */
    public float[] imeCursorRect() {
        HtmlNode field = interaction.focusedNode();
        if (field == null || !isTextControl(field)) return null;
        for (DisplayItem it : withOverlays(items)) {
            if (it.baseId().equals(field.key()) && (it.isPart("_caret") || it.id().endsWith("_caret"))) {
                return new float[]{it.x(), it.y(), it.width(), it.height()};
            }
        }
        DisplayItem el = fieldItem(field);
        if (el != null) {
            return new float[]{el.x() + 4f, el.y() + 4f, 1f, el.height() - 8f};
        }
        return null;
    }

    /** 編集の結果を反映する。値が変わるなら {@code beforeinput} が通ってからキャレットを動かす。 */
    private boolean applyEdit(HtmlNode field, TextEditing.Result r) {
        if (!r.handled()) return false;
        TextEditing.State st = editState(field);
        if (r.changed()) {
            if (!replaceValue(field, r.text(), r.data(), r.inputType())) return false;
            int len = valueOf(field).length();
            st.caret = Math.min(r.caret(), len);
            st.anchor = Math.min(r.anchor(), len);
        } else {
            st.caret = r.caret();
            st.anchor = r.anchor();
        }
        redraw = true;
        return true;
    }

    private void editKey(HtmlNode field, int key, int mods, boolean enter, UIEvents.Keyboard ev) {
        boolean multiline = "textarea".equalsIgnoreCase(field.tag);
        if (enter && !multiline) {
            input.submitInput(field.key(), valueOf(field)); // change を送る
            StandardDefaultActions.execute(ev, new DefaultActionContext() { // 暗黙のフォーム送信
                @Override
                public void onAttributeChanged(HtmlNode node, String attrName, String attrValue) {}

                @Override
                public void dispatchClickTo(HtmlNode other) {}

                @Override
                public void navigateTo(String href) {}

                @Override
                public void executeFormAction(HtmlNode form, String eventType) {}
            });
            return;
        }
        applyEdit(field, TextEditing.key(editState(field), valueOf(field), key, mods, multiline, clipboard));
    }

    // ------------------------------------------------------------------
    // select のポップアップ
    // ------------------------------------------------------------------

    private void openSelect(HtmlNode select) {
        DisplayItem box = null;
        for (DisplayItem it : items) {
            if (!it.baseId().equals(select.key()) || it.width() <= 0) continue;
            if (it.type() == ElementType.SELECT) {
                box = it;
                break;
            }
            if (box == null) box = it;
        }
        if (popup.open(select, box, height)) redraw = true;
    }

    private boolean ownerUnderPointer(HtmlNode owner) {
        UIHitResult hit = ui.hitTest(mouseX, mouseY);
        return owner != null && hit != null && hit.element().baseId().equals(owner.key());
    }

    /** ポップアップが開いている間のキー。使ったら true（{@code Tab} は閉じるだけで続けてフォーカスを移す）。 */
    private boolean selectKey(int key) {
        redraw = true;
        switch (key) {
            case Keys.ESCAPE -> popup.close();
            case Keys.UP -> popup.moveHighlight(-1);
            case Keys.DOWN -> popup.moveHighlight(1);
            case Keys.ENTER, Keys.KP_ENTER, Keys.SPACE -> {
                HtmlNode owner = popup.select();
                String value = popup.highlightedValue();
                popup.close();
                if (value != null) commitSelect(owner, value);
            }
            case Keys.TAB -> {
                popup.close();
                return false;
            }
            default -> { }
        }
        return true;
    }

    /** 選んだ値を反映し、変わっていれば {@code input} と {@code change} を送る。 */
    private void commitSelect(HtmlNode select, String value) {
        if (select == null) return;
        if (!value.equals(select.attrs.getOrDefault("value", ""))) input.submitInput(select.key(), value);
        redraw = true;
    }

    // ------------------------------------------------------------------
    // キャレット・選択・ポップアップの重ね描き
    // ------------------------------------------------------------------

    private List<DisplayItem> withOverlays(List<DisplayItem> base) {
        List<DisplayItem> extra = new ArrayList<>(textOverlay());
        extra.addAll(popup.overlay());
        if (extra.isEmpty()) return base;
        List<DisplayItem> all = new ArrayList<>(base.size() + extra.size());
        all.addAll(base);
        all.addAll(extra);
        return all;
    }

    /** テキスト欄の表示項目（{@code _input} / {@code _textarea}）。 */
    private DisplayItem fieldItem(HtmlNode field) {
        String id = field.key();
        String part = "textarea".equalsIgnoreCase(field.tag) ? "_textarea" : "_input";
        for (DisplayItem it : items) {
            if (it.baseId().equals(id) && it.isPart(part)) return it;
        }
        return null;
    }

    private OutlineFontFace faceOf(DisplayItem el) {
        String family = el.events() != null ? el.events().get("font-family") : null;
        FontFace f = fonts.resolve(family);
        if (f instanceof OutlineFontFace o) return o;
        f = SystemFonts.system().resolve(family);
        return f instanceof OutlineFontFace o ? o : null;
    }

    private static FontStyle styleOf(DisplayItem el, float fontPx) {
        Map<String, String> ev = el.events() != null ? el.events() : Map.of();
        int weight = LayoutMetrics.numericWeight(ev.get("font-weight"));
        String style = ev.getOrDefault("font-style", "normal").trim();
        float spacing = 0f;
        try {
            spacing = Float.parseFloat(ev.getOrDefault("letter-spacing", "0"));
        } catch (NumberFormatException ignored) {
        }
        boolean kerning = !"none".equalsIgnoreCase(ev.getOrDefault("font-kerning", "auto").trim());
        return new FontStyle(fontPx, weight >= 600, !style.equalsIgnoreCase("normal"), spacing, weight, kerning);
    }

    private static float fontPx(DisplayItem el) {
        RenderMeta meta = el.meta();
        return meta != null && meta.fontHeight() > 0f ? meta.fontHeight() : Math.max(1f, el.height());
    }

    /** 点（文書座標）の下の文字位置。欄の項目が無ければ -1。 */
    private int caretAt(HtmlNode field, double px, double py) {
        DisplayItem el = fieldItem(field);
        OutlineFontFace face = el != null ? faceOf(el) : null;
        if (face == null) return -1;
        String text = valueOf(field);
        FontStyle style = styleOf(el, fontPx(el));
        if (!"textarea".equalsIgnoreCase(field.tag)) {
            return OutlineFontFace.nearestBoundary(text, face.positions(text, style), px - (el.x() + 4f));
        }
        String[] lines = text.split("\n", -1);
        float lineH = (el.meta() != null ? el.meta() : RenderMeta.EMPTY).resolvedLineHeight();
        int row = Math.max(0, Math.min(lines.length - 1, (int) Math.floor((py - el.y() - 4f) / lineH)));
        int offset = 0;
        for (int i = 0; i < row; i++) offset += lines[i].length() + 1;
        return offset + OutlineFontFace.nearestBoundary(lines[row], face.positions(lines[row], style), px - (el.x() + 4f));
    }

    /** フォーカス中のテキスト欄の選択範囲（行ごとの矩形）とキャレット。 */
    private List<DisplayItem> textOverlay() {
        HtmlNode field = interaction.focusedNode();
        if (field == null || popup.isOpen() || !isTextControl(field)) return List.of();
        DisplayItem el = fieldItem(field);
        OutlineFontFace face = el != null ? faceOf(el) : null;
        if (face == null) return List.of();
        TextEditing.State st = editState(field);
        String text = valueOf(field);
        FontStyle style = styleOf(el, fontPx(el));
        boolean area = "textarea".equalsIgnoreCase(field.tag);
        float glyphH = face.ascent(style) + face.descent(style);
        float lineH = area ? (el.meta() != null ? el.meta() : RenderMeta.EMPTY).resolvedLineHeight() : el.height();
        float left = el.x() + 4f, right = el.x() + el.width() - 4f;
        int caretColor = (el.color() >>> 24) == 0 ? 0xFF000000 : (el.color() | 0xFF000000);
        List<DisplayItem> out = new ArrayList<>();
        String[] lines = area ? text.split("\n", -1) : new String[]{text};
        int offset = 0;
        for (int row = 0; row < lines.length; row++) {
            String line = lines[row];
            float top = area ? el.y() + 4f + row * lineH + (lineH - glyphH) / 2f : el.y() + (el.height() - glyphH) / 2f;
            if (top > el.y() + el.height()) break;
            float[] pos = face.positions(line, style);
            if (st.hasSelection() && st.start() <= offset + line.length() && st.end() >= offset) {
                int lo = Math.max(0, st.start() - offset), hi = Math.min(line.length(), st.end() - offset);
                float x0 = Math.min(right, left + pos[lo]);
                float x1 = Math.min(right, left + pos[hi]);
                // 行をまたぐ選択は、行末の改行も塗る
                if (st.end() > offset + line.length() && row < lines.length - 1) x1 = Math.min(right, x1 + 4f);
                if (x1 > x0) out.add(overlayRect(field, "_sel" + row, x0, top, x1 - x0, glyphH, 0x663B82F6));
            }
            if (st.isComposing() && st.compositionText != null && !st.compositionText.isEmpty()) {
                if (st.caret >= offset && st.caret <= offset + line.length()) {
                    int relCaret = st.caret - offset;
                    float preeditX0 = Math.min(right, left + pos[relCaret]);
                    float[] preeditPos = face.positions(st.compositionText, style);
                    float preeditW = preeditPos.length > 0 ? preeditPos[preeditPos.length - 1] : 0f;
                    float preeditX1 = Math.min(right, preeditX0 + preeditW);
                    int bg = (el.bgColor() >>> 24) == 0 ? 0xFFFFFFFF : el.bgColor();
                    if (preeditX0 < right) {
                        out.add(overlayRect(field, "_ime_clr", preeditX0, top - 2f, right - preeditX0, glyphH + 4f, bg));
                    }
                    if (preeditX1 > preeditX0) {
                        out.add(overlayRect(field, "_ime_bg", preeditX0, top, preeditX1 - preeditX0, glyphH, 0x333B82F6));
                        out.add(overlayRect(field, "_ime_ul", preeditX0, top + glyphH - 1f, preeditX1 - preeditX0, 2f, 0xFF3B82F6));
                        out.add(overlayText(field, "_ime_txt", preeditX0, top, preeditX1 - preeditX0, glyphH, st.compositionText, el));
                    }
                    if (relCaret < line.length() && preeditX1 < right) {
                        String remainder = line.substring(relCaret);
                        out.add(overlayText(field, "_ime_rem", preeditX1, top, right - preeditX1, glyphH, remainder, el));
                    }
                    int curIdx = Math.max(0, Math.min(st.compositionText.length(), st.compositionCursor));
                    float cx = Math.min(right, preeditX0 + (curIdx < preeditPos.length ? preeditPos[curIdx] : preeditW));
                    out.add(overlayRect(field, "_caret", cx, top, 1f, glyphH, caretColor));
                }
            } else {
                if (st.caret >= offset && st.caret <= offset + line.length()) {
                    float cx = Math.min(right, left + pos[st.caret - offset]);
                    out.add(overlayRect(field, "_caret", cx, top, 1f, glyphH, caretColor));
                }
            }
            offset += line.length() + 1;
        }
        return out;
    }

    private static DisplayItem overlayText(HtmlNode field, String part, float x, float y, float w, float h, String text, DisplayItem el) {
        int left = Math.round(x), top = Math.round(y);
        int color = (el.color() >>> 24) == 0 ? 0xFF000000 : (el.color() | 0xFF000000);
        return new DisplayItem(field.key() + part, field.key(), ElementType.TEXT, left, top,
                Math.max(1, Math.round(x + w) - left), Math.max(1, Math.round(y + h) - top), text, color, 0,
                Map.of(), 0, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, ImageFitMode.FILL, el.meta());
    }

    private static DisplayItem overlayRect(HtmlNode field, String part, float x, float y, float w, float h, int argb) {
        int left = Math.round(x), top = Math.round(y);
        return new DisplayItem(field.key() + part, field.key(), ElementType.RECT, left, top,
                Math.max(1, Math.round(x + w) - left), Math.max(1, Math.round(y + h) - top), "", 0, argb,
                Map.of(), 0, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, ImageFitMode.FILL, null);
    }

    private boolean replaceValue(HtmlNode field, String next, String data, String inputType) {
        if (field.attrs.containsKey("readonly") || field.attrs.containsKey("disabled")) return false;
        String id = field.key();
        if (!input.beginTextEdit(id, data, inputType)) return false; // beforeinput が止めた
        input.commitTextEdit(id, next, data, inputType);
        input.updateInputLocally(id, next);
        return true;
    }

    private static String valueOf(HtmlNode field) {
        return field.attrs.getOrDefault("value", "");
    }

    private static boolean isCheckable(HtmlNode node) {
        if (!"input".equalsIgnoreCase(node.tag)) return false;
        String type = node.attrs.getOrDefault("type", "").toLowerCase(Locale.ROOT);
        return type.equals("checkbox") || type.equals("radio");
    }

    private static boolean isTextControl(HtmlNode node) {
        if ("textarea".equalsIgnoreCase(node.tag)) return true;
        if (!"input".equalsIgnoreCase(node.tag)) return false;
        return switch (node.attrs.getOrDefault("type", "text").toLowerCase(Locale.ROOT)) {
            case "text", "search", "url", "email", "password", "tel", "number", "" -> true;
            default -> false;
        };
    }

    /** {@code Tab} 順（{@code tabindex} の正の値が先、続いて文書順）の次／前へ。 */
    private void moveFocus(int step) {
        HtmlNode next = UiFocus.next(ui, interaction.focusedNode(), step);
        if (next != null) ui.setFocusedElement(next.key(), true);
    }

    @Override
    public void close() {
        ui.close();
    }
}
