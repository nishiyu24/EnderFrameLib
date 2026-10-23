package com.nishiyu.enderframelib.client.gui.desktop;

import com.nishiyu.enderframelib.client.ClientWebManager;
import com.nishiyu.enderframelib.client.SessionContext;
import com.nishiyu.enderframelib.client.gui.AbstractEnderScreen;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * EnderFrameLib を用いた Minecraft 内 Linux 風デスクトップ環境画面。
 *
 * <p>ウィンドウのドラッグ移動、フォーカス制御、最小化・最大化、
 * ターミナルコマンド実行、テキスト編集、システム監視を完全統合。</p>
 */
public class LinuxDesktopScreen extends AbstractEnderScreen {

    private final DesktopWindowManager windowManager = new DesktopWindowManager();
    private boolean appMenuOpen = false;
    private String activeDocTab = "overview";

    // ドラッグ移動制御。ドラッグ中は DOM を変えず（再レイアウトしない）、描画上の平行移動だけで動かし、
    // 離したときに一度だけ位置を確定する
    private static final double DRAG_THRESHOLD = 4.0;
    private String draggingWindowId = null;
    private double pressMouseX = 0;
    private double pressMouseY = 0;
    private double grabOffsetX = 0;
    private double grabOffsetY = 0;
    private int dragDomX = 0;
    private int dragDomY = 0;
    private boolean dragMoved = false;
    private DesktopLayout.SnapZone dragSnapZone = DesktopLayout.SnapZone.NONE;
    private long lastTitlebarClickTime = 0;
    private String lastTitlebarClickWinId = null;
    private boolean initialLayoutDone = false;

    // ターミナル状態
    private final StringBuilder terminalHistory = new StringBuilder();
    private final long startupTimeMs = System.currentTimeMillis();

    // システムモニターは表示中のみ 1 秒ごとに更新する
    private final TickThrottle sysmonRefresh = new TickThrottle(20);

    public LinuxDesktopScreen() {
        super(
                Component.literal("EnderFrame Linux Desktop"),
                ResourceLocation.fromNamespaceAndPath("enderframelib", "enderframe_ui/linux_desktop.html")
        );

        // 全画面レスポンシブモード
        this.fullscreenResponsive = true;
        this.viewportMarginRatio = 1.0f;

        // ウィンドウ登録 (位置は init() で仮想解像度に合わせて DesktopLayout が決める)
        windowManager.registerWindow("docs", "How-To & Documentation", 112, 76, 600, 340);
        windowManager.registerWindow("terminal", "Terminal", 366, 36, 480, 270);
        windowManager.registerWindow("editor", "Text Editor", 156, 96, 440, 260);
        windowManager.registerWindow("sysmon", "System Monitor", 216, 126, 360, 260);

        // 初期状態でターミナルとドキュメントを開く (ドキュメントを前面に)
        windowManager.open("terminal");
        windowManager.open("docs");

        terminalHistory.append("EnderFrame Linux 6.8.0-generic (x86_64)\n")
                .append("Type 'help' or 'howto' for available commands.\n\n");
        this.state.set("sys_java", "Java " + System.getProperty("java.version") + " (" + System.getProperty("os.arch") + ")");
    }

    @Override
    protected void init() {
        super.init();
        if (!initialLayoutDone) {
            layoutInitialWindows(this.virtualWidth, this.virtualHeight);
        }
        syncAllStates();
    }

    /** 起動時のウィンドウ配置を仮想解像度に合わせて決める（画面サイズ変更時の再 init では行わない）。 */
    void layoutInitialWindows(int desktopW, int desktopH) {
        DesktopLayout.placeInitialWindows(windowManager, desktopW, desktopH);
        initialLayoutDone = true;
        syncAllStates();
    }

    DesktopWindowManager windowManager() {
        return windowManager;
    }

    @Override
    protected void registerWebActions() {
        // メニュートグル
        onAction("menu:toggle", this::toggleAppMenu);

        // ドキュメントタブ切り替え
        onActionPrefix("doc:tab:", this::switchDocTab);

        // アプリ起動
        onActionPrefix("app:launch:", id -> {
            openAndFocusWindow(id);
            closeAppMenu();
        });

        // ウィンドウ操作 (close, min, max)
        onActionPrefix("win:close:", id -> {
            windowManager.close(id);
            syncAllStates();
        });
        onActionPrefix("win:min:", id -> {
            windowManager.minimize(id);
            syncAllStates();
        });
        onActionPrefix("win:max:", id -> {
            windowManager.toggleMaximize(id, this.virtualWidth, this.virtualHeight, 28, 48);
            syncAllStates();
        });

        // タスクバー / ドック切り替え
        onActionPrefix("win:toggle:", id -> {
            handleDockToggle(id);
            syncAllStates();
        });
    }

    public void switchDocTab(String tabId) {
        this.activeDocTab = tabId;
        syncDocTabStates();
    }

    private void syncDocTabStates() {
        this.state.set("doc_tab_overview", "overview".equals(activeDocTab));
        this.state.set("doc_tab_desktop", "desktop".equals(activeDocTab));
        this.state.set("doc_tab_terminal", "terminal".equals(activeDocTab));
        this.state.set("doc_tab_web", "web".equals(activeDocTab));
        this.state.set("doc_tab_commands", "commands".equals(activeDocTab));
    }

    public void openAndFocusWindow(String windowId) {
        windowManager.open(windowId);
        windowManager.focus(windowId);
        if ("docs".equals(windowId)) {
            switchDocTab("overview");
        }
        syncAllStates();
    }

    @Override
    protected void onWebAction(String action, DisplayItem clickedElement, int mouseButton) {
        super.onWebAction(action, clickedElement, mouseButton);
    }

    private void handleDockToggle(String id) {
        DesktopWindowManager.WindowState win = windowManager.get(id);
        if (win == null) return;

        if (win.isOpen && !win.isMinimized && id.equals(windowManager.activeWindowId())) {
            windowManager.minimize(id);
        } else {
            windowManager.open(id);
        }
    }

    private void toggleAppMenu() {
        this.appMenuOpen = !this.appMenuOpen;
        this.state.set("app_menu_open", this.appMenuOpen);
    }

    private void closeAppMenu() {
        if (this.appMenuOpen) {
            this.appMenuOpen = false;
            this.state.set("app_menu_open", false);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        double webX = toWebX(mouseX);
        double webY = toWebY(mouseY);
        this.draggingWindowId = null;
        this.dragMoved = false;

        // メニュー外クリックで閉じる。閉じるだけにして背面の要素へは届けない。
        // Applications ボタン上の押下は閉じずに、ボタン自身の onclick でトグルさせる
        if (appMenuOpen && DesktopLayout.shouldCloseAppMenu(webX, webY, elementRect("app-menu"), elementRect("app-menu-btn"))) {
            closeAppMenu();
            this.draggingWindowId = null;
            return true;
        }

        boolean onTopBar = webY < DesktopLayout.TOP_BAR_HEIGHT;
        DesktopLayout.Rect dockRect = elementRect("dock");
        boolean onDock = dockRect != null ? dockRect.contains(webX, webY)
                : webY >= (this.virtualHeight - DesktopLayout.DOCK_HEIGHT)
                && webX >= (this.virtualWidth / 2.0 - 150)
                && webX <= (this.virtualWidth / 2.0 + 150);

        // ここに来た時点でメニューが開いていれば、押下はメニュー上（最前面）にある
        boolean onAppMenu = appMenuOpen;

        boolean focusChanged = false;
        if (!onTopBar && !onDock && !onAppMenu) {
            DesktopWindowManager.WindowState topWin = windowManager.findTopWindowAt(webX, webY);
            if (topWin != null) {
                String prevActive = windowManager.activeWindowId();
                if (!topWin.id.equals(prevActive)) {
                    windowManager.focus(topWin.id);
                    focusChanged = true;
                }

                // タイトルバー判定 (上部 32px、右端 65px は最小化・最大化・閉じるボタン)
                boolean inHeaderY = webY >= topWin.y && webY <= topWin.y + 32;
                boolean inDragHeader = inHeaderY && webX >= topWin.x && webX <= (topWin.x + topWin.width - 65);

                if (button == 0 && inDragHeader) {
                    long now = System.currentTimeMillis();
                    if (now - lastTitlebarClickTime < 350 && topWin.id.equals(lastTitlebarClickWinId)) {
                        // タイトルバーのダブルクリック -> 最大化 / 元に戻す を切り替え
                        windowManager.toggleMaximize(topWin.id, this.virtualWidth, this.virtualHeight, 28, 48);
                        syncAllStates();
                        this.draggingWindowId = null;
                        this.lastTitlebarClickTime = 0;
                        this.lastTitlebarClickWinId = null;
                        return true;
                    }
                    this.lastTitlebarClickTime = now;
                    this.lastTitlebarClickWinId = topWin.id;

                    // 最大化・タイル中でも掴める（動かし始めたら元の大きさに戻す）
                    beginWindowDrag(topWin, webX, webY);
                }
            }
        }

        if (focusChanged) {
            syncAllStates();
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggingWindowId != null && button == 0) {
            DesktopWindowManager.WindowState win = windowManager.get(draggingWindowId);
            if (win == null) {
                this.draggingWindowId = null;
                return true;
            }
            double webX = toWebX(mouseX);
            double webY = toWebY(mouseY);

            if (!dragMoved) {
                // 押しただけの手ぶれはドラッグにしない
                if (Math.hypot(webX - pressMouseX, webY - pressMouseY) < DRAG_THRESHOLD) return true;
                this.dragMoved = true;
                if (win.isMaximized || win.isTiled) restoreForDrag(win, webX, webY);
            }

            win.x = DesktopWindowManager.clampX(win, webX - grabOffsetX, this.virtualWidth);
            win.y = DesktopWindowManager.clampY(webY - grabOffsetY, this.virtualHeight, DesktopLayout.TOP_BAR_HEIGHT);
            updateDragPaintOffset(win);
            updateSnapPreview(DesktopLayout.snapZoneAt(webX, webY, this.virtualWidth), win);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (this.draggingWindowId != null && button == 0 && this.dragMoved) {
            // ウィンドウを動かした解放はドラッグの終わりであってクリックではない
            this.uiSupport.suppressNextClick();
            finishWindowDrag();
        }
        this.draggingWindowId = null;
        this.dragMoved = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private void beginWindowDrag(DesktopWindowManager.WindowState win, double webX, double webY) {
        this.draggingWindowId = win.id;
        this.pressMouseX = webX;
        this.pressMouseY = webY;
        this.grabOffsetX = webX - win.x;
        this.grabOffsetY = webY - win.y;
        this.dragDomX = win.x;
        this.dragDomY = win.y;
        this.dragMoved = false;
        this.dragSnapZone = DesktopLayout.SnapZone.NONE;
    }

    /**
     * 最大化・タイル中のウィンドウを引き出したら元の大きさへ戻す。掴んだ位置がタイトルバーの
     * 同じ割合の位置に来るようにして、カーソルの下からウィンドウが逃げないようにする（GNOME と同じ）。
     */
    private void restoreForDrag(DesktopWindowManager.WindowState win, double webX, double webY) {
        double ratio = win.width > 0 ? Math.clamp(grabOffsetX / win.width, 0.0, 1.0) : 0.5;
        windowManager.restore(win.id);
        this.grabOffsetX = ratio * win.width;
        this.grabOffsetY = Math.min(grabOffsetY, 16.0);
        win.x = DesktopWindowManager.clampX(win, webX - grabOffsetX, this.virtualWidth);
        win.y = DesktopWindowManager.clampY(webY - grabOffsetY, this.virtualHeight, DesktopLayout.TOP_BAR_HEIGHT);
        // 大きさが変わるので、ここだけは一度レイアウトし直す
        syncAllStates();
    }

    /** DOM 上の位置との差を、ウィンドウの描画上の平行移動として掛ける。 */
    private void updateDragPaintOffset(DesktopWindowManager.WindowState win) {
        SessionContext session = ClientWebManager.getSession(this.sessionId);
        if (session != null) session.setPaintOffset("win-" + win.id, win.x - dragDomX, win.y - dragDomY);
    }

    /** 吸着先のプレビュー枠。吸着先が変わったときだけ DOM を変える。 */
    private void updateSnapPreview(DesktopLayout.SnapZone zone, DesktopWindowManager.WindowState win) {
        if (zone == dragSnapZone) return;
        this.dragSnapZone = zone;
        DesktopLayout.Rect r = DesktopLayout.snapRect(zone, this.virtualWidth, this.virtualHeight);
        this.state.set("snap_preview_visible", r != null);
        if (r != null) {
            this.state.set("snap_x", (int) r.x() + "px");
            this.state.set("snap_y", (int) r.y() + "px");
            this.state.set("snap_w", (int) r.width() + "px");
            this.state.set("snap_h", (int) r.height() + "px");
            // ドラッグ中のウィンドウと同じ z-index。DOM ではウィンドウより前にあるので、その真下に描かれる
            this.state.set("snap_z", win.zIndex);
        }
    }

    /** 離した位置（または吸着先）でウィンドウの配置を確定し、描画上の平行移動を外す。 */
    private void finishWindowDrag() {
        String id = this.draggingWindowId;
        DesktopLayout.SnapZone zone = this.dragSnapZone;
        this.draggingWindowId = null;
        this.dragSnapZone = DesktopLayout.SnapZone.NONE;
        this.state.set("snap_preview_visible", false);
        // ドラッグの直後の押下はダブルクリックの 2 回目として数えない
        this.lastTitlebarClickTime = 0;
        this.lastTitlebarClickWinId = null;

        DesktopLayout.Rect r = DesktopLayout.snapRect(zone, this.virtualWidth, this.virtualHeight);
        if (zone == DesktopLayout.SnapZone.MAXIMIZE) {
            windowManager.maximize(id, this.virtualWidth, this.virtualHeight, DesktopLayout.TOP_BAR_HEIGHT, DesktopLayout.DOCK_HEIGHT);
        } else if (r != null) {
            windowManager.tile(id, (int) r.x(), (int) r.y(), (int) r.width(), (int) r.height());
        }

        SessionContext session = ClientWebManager.getSession(this.sessionId);
        if (session != null) session.clearPaintOffset("win-" + id);
        syncAllStates();
    }

    /**
     * 描画済みの要素の外形。未描画・非表示なら null。
     * transform は要素本体（id がそのまま一致する NODE）だけが持つので、背景などの部品ではなくそれを使う。
     */
    private DesktopLayout.Rect elementRect(String elementId) {
        SessionContext session = ClientWebManager.getSession(this.sessionId);
        if (session == null) return null;
        for (DisplayItem item : session.renderCache()) {
            if (elementId.equals(item.id())) return DesktopLayout.Rect.of(item);
        }
        return null;
    }

    @Override
    protected void onWebInputSubmitted(String elementId, String text) {
        if ("term-input".equals(elementId)) {
            executeTerminalCommand(text != null ? text.trim() : "");
            focusWebInput("term-input", "");
        }
    }

    private void executeTerminalCommand(String rawCmd) {
        if (rawCmd.isEmpty()) return;

        terminalHistory.append("user@enderframe:~$ ").append(rawCmd).append("\n");

        String[] parts = rawCmd.split("\\s+");
        String cmd = parts[0].toLowerCase(Locale.ROOT);

        switch (cmd) {
            case "help" -> {
                terminalHistory.append("""
                        Available commands:
                          help        - Show available commands
                          howto       - Open interactive in-game documentation
                          neofetch    - Show system overview & ASCII art
                          clear       - Clear terminal history
                          date        - Show current date & time
                          uname -a    - Kernel and system info
                          free        - Memory statistics
                          echo <msg>  - Print message
                          ls          - List files
                          cat <file>  - Display file contents
                          notes       - Open Notes application
                          sysmon      - Open System Monitor
                          exit        - Close terminal window
                        """);
            }
            case "howto", "docs" -> {
                openAndFocusWindow("docs");
                terminalHistory.append("Opening How-To & In-Game Documentation...\n");
            }
            case "neofetch" -> {
                long uptimeSec = (System.currentTimeMillis() - startupTimeMs) / 1000;
                long totalMem = Runtime.getRuntime().totalMemory() / (1024 * 1024);
                long freeMem = Runtime.getRuntime().freeMemory() / (1024 * 1024);
                long usedMem = totalMem - freeMem;
                long maxMem = Runtime.getRuntime().maxMemory() / (1024 * 1024);

                terminalHistory.append(String.format("""
                           /\\_/\\          user@enderframe
                          ( o.o )         ---------------
                           > ^ <          OS: EnderFrame Linux 6.8.0 x86_64
                         /|     |\\        Host: Minecraft 1.21.1 (NeoForge)
                        (_|_ _ _|_)       Uptime: %ds
                                          Shell: bash 5.2.21
                                          Resolution: %dx%d
                                          WM: EnderFrame WindowManager
                                          Theme: Catppuccin Mocha
                                          Terminal: ender-term
                                          Memory: %dMB / %dMB
                        """, uptimeSec, virtualWidth, virtualHeight, usedMem, maxMem));
            }
            case "clear" -> {
                terminalHistory.setLength(0);
            }
            case "date" -> {
                terminalHistory.append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss yyyy"))).append("\n");
            }
            case "uname" -> {
                terminalHistory.append("Linux enderframe 6.8.0-generic #42-NeoForge SMP PREEMPT_DYNAMIC x86_64 GNU/Linux\n");
            }
            case "free" -> {
                long total = Runtime.getRuntime().totalMemory() / 1024;
                long free = Runtime.getRuntime().freeMemory() / 1024;
                long used = total - free;
                terminalHistory.append(String.format("               total        used        free\nMem:        %8d    %8d    %8d\n", total, used, free));
            }
            case "echo" -> {
                if (parts.length > 1) {
                    terminalHistory.append(rawCmd.substring(5).trim()).append("\n");
                }
            }
            case "ls" -> {
                terminalHistory.append("notes.txt  motd.txt  config.sys  system.log\n");
            }
            case "cat" -> {
                if (parts.length < 2) {
                    terminalHistory.append("cat: missing file operand\n");
                } else if ("notes.txt".equals(parts[1])) {
                    terminalHistory.append("EnderFrame Notes: Fully reactive desktop UI for Minecraft!\n");
                } else if ("motd.txt".equals(parts[1])) {
                    terminalHistory.append("Welcome to EnderFrame Linux Desktop Environment!\n");
                } else {
                    terminalHistory.append("cat: ").append(parts[1]).append(": No such file or directory\n");
                }
            }
            case "notes" -> {
                windowManager.open("editor");
                syncAllStates();
            }
            case "sysmon" -> {
                windowManager.open("sysmon");
                syncAllStates();
            }
            case "exit" -> {
                windowManager.close("terminal");
                syncAllStates();
            }
            default -> {
                terminalHistory.append("bash: ").append(cmd).append(": command not found. Type 'help' for info.\n");
            }
        }

        this.state.set("terminal_output", terminalHistory.toString());
    }

    @Override
    public void tick() {
        super.tick();

        // 時計更新
        this.state.set("clock_time", LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm")));

        // プレイヤー名
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            this.state.set("user_name", mc.player.getGameProfile().getName());
        }

        // システムメトリクス更新 (System Monitor の表示中のみ、1 秒ごと)
        DesktopWindowManager.WindowState sysmon = windowManager.get("sysmon");
        if (sysmonRefresh.tick(sysmon != null && sysmon.isVisible())) {
            long totalMem = Runtime.getRuntime().totalMemory() / (1024 * 1024);
            long freeMem = Runtime.getRuntime().freeMemory() / (1024 * 1024);
            long maxMem = Runtime.getRuntime().maxMemory() / (1024 * 1024);
            long usedMem = totalMem - freeMem;

            this.state.set("sys_mem", usedMem + " MB / " + maxMem + " MB (" + (usedMem * 100 / Math.max(1, maxMem)) + "%)");
            this.state.set("sys_fps", mc.getFps() + " FPS");
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (appMenuOpen && keyCode == GLFW.GLFW_KEY_ESCAPE) {
            closeAppMenu();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    void syncAllStates() {
        syncDocTabStates();
        this.state.set("app_menu_open", this.appMenuOpen);
        this.state.set("terminal_output", terminalHistory.toString());

        String active = windowManager.activeWindowId();
        DesktopWindowManager.WindowState activeWin = windowManager.get(active);
        this.state.set("active_app_title", (activeWin != null && activeWin.isVisible()) ? activeWin.title : "Desktop");

        for (DesktopWindowManager.WindowState win : windowManager.allWindows()) {
            boolean isVisible = win.isVisible();
            this.state.set(win.id + "_open", isVisible);
            this.state.set(win.id + "_active", win.id.equals(active) && isVisible);
            // タイル中も画面の端に接するので、最大化と同じく角を丸めない
            this.state.set(win.id + "_max", win.isMaximized || win.isTiled);
            this.state.set(win.id + "_x", win.x + "px");
            this.state.set(win.id + "_y", win.y + "px");
            this.state.set(win.id + "_w", win.width + "px");
            this.state.set(win.id + "_h", win.height + "px");
            this.state.set(win.id + "_z", win.zIndex);

            // ドックのアクティブインジケータ
            this.state.set(win.id + "_dock_active", isVisible);
        }

        if (draggingWindowId == null) {
            this.state.set("snap_preview_visible", false);
        } else {
            // ドラッグ中に配置を DOM へ流したら、そこを新しい基準にして平行移動を 0 に戻す
            DesktopWindowManager.WindowState dragged = windowManager.get(draggingWindowId);
            if (dragged != null) {
                this.dragDomX = dragged.x;
                this.dragDomY = dragged.y;
                updateDragPaintOffset(dragged);
            }
        }
    }

    /**
     * Linux デスクトップ環境のウィンドウ状態管理クラス。
     */
    public static class DesktopWindowManager {

        public static class WindowState {
            public final String id;
            public final String title;
            public int x;
            public int y;
            public int width;
            public int height;

            // 最大化前の状態復元用
            public int prevX;
            public int prevY;
            public int prevW;
            public int prevH;

            public boolean isOpen = false;
            public boolean isMinimized = false;
            public boolean isMaximized = false;
            /** 画面端スナップで左右半分に並べた状態。最大化と同じく prev* に元の位置と大きさを持つ。 */
            public boolean isTiled = false;
            public int zIndex = 10;

            public WindowState(String id, String title, int x, int y, int width, int height) {
                this.id = id;
                this.title = title != null ? title : id;
                this.x = x;
                this.y = y;
                this.width = width;
                this.height = height;
                this.prevX = x;
                this.prevY = y;
                this.prevW = width;
                this.prevH = height;
            }

            public boolean isVisible() {
                return isOpen && !isMinimized;
            }
        }

        private final Map<String, WindowState> windows = new LinkedHashMap<>();
        private String activeWindowId = "";
        private int topZIndex = 10;

        public void registerWindow(String id, String title, int x, int y, int width, int height) {
            windows.put(id, new WindowState(id, title, x, y, width, height));
        }

        public WindowState get(String id) {
            return windows.get(id);
        }

        public Collection<WindowState> allWindows() {
            return Collections.unmodifiableCollection(windows.values());
        }

        public String activeWindowId() {
            return activeWindowId;
        }

        public void open(String id) {
            WindowState win = windows.get(id);
            if (win == null) return;
            win.isOpen = true;
            win.isMinimized = false;
            focus(id);
        }

        public void close(String id) {
            WindowState win = windows.get(id);
            if (win == null) return;
            win.isOpen = false;
            if (id.equals(activeWindowId)) {
                activeWindowId = "";
                focusTopRemainingWindow();
            }
        }

        public void minimize(String id) {
            WindowState win = windows.get(id);
            if (win == null) return;
            win.isMinimized = true;
            if (id.equals(activeWindowId)) {
                activeWindowId = "";
                focusTopRemainingWindow();
            }
        }

        public void focus(String id) {
            WindowState win = windows.get(id);
            if (win == null) return;
            if (win.isMinimized) {
                win.isMinimized = false;
            }
            win.isOpen = true;
            win.zIndex = ++topZIndex;
            if (topZIndex >= 500) {
                normalizeZIndices();
            }
            activeWindowId = id;
        }

        private void normalizeZIndices() {
            List<WindowState> sorted = new ArrayList<>(windows.values());
            sorted.sort(Comparator.comparingInt(w -> w.zIndex));
            int z = 10;
            for (WindowState w : sorted) {
                w.zIndex = z++;
            }
            topZIndex = z;
        }

        public void toggleMaximize(String id, int desktopW, int desktopH, int topBarH, int dockH) {
            WindowState win = windows.get(id);
            if (win == null) return;

            if (win.isMaximized) {
                restore(id);
            } else {
                maximize(id, desktopW, desktopH, topBarH, dockH);
            }
            focus(id);
        }

        public void maximize(String id, int desktopW, int desktopH, int topBarH, int dockH) {
            WindowState win = windows.get(id);
            if (win == null) return;
            rememberRestoreGeometry(win);
            win.x = 0;
            win.y = topBarH;
            win.width = Math.max(200, desktopW);
            win.height = Math.max(150, desktopH - topBarH - dockH);
            win.isMaximized = true;
            win.isTiled = false;
            focus(id);
        }

        /** 画面の左右半分などに並べる（GNOME の端スナップ）。元の位置と大きさは復元用に覚えておく。 */
        public void tile(String id, int x, int y, int width, int height) {
            WindowState win = windows.get(id);
            if (win == null) return;
            rememberRestoreGeometry(win);
            win.x = x;
            win.y = y;
            win.width = width;
            win.height = height;
            win.isMaximized = false;
            win.isTiled = true;
            focus(id);
        }

        /** 最大化・タイルを解き、覚えていた位置と大きさへ戻す。 */
        public void restore(String id) {
            WindowState win = windows.get(id);
            if (win == null || (!win.isMaximized && !win.isTiled)) return;
            win.x = win.prevX;
            win.y = win.prevY;
            win.width = win.prevW;
            win.height = win.prevH;
            win.isMaximized = false;
            win.isTiled = false;
        }

        /** 最大化・タイル中に別の配置へ移るときは、最初に覚えた通常時の値を保つ。 */
        private static void rememberRestoreGeometry(WindowState win) {
            if (win.isMaximized || win.isTiled) return;
            win.prevX = win.x;
            win.prevY = win.y;
            win.prevW = win.width;
            win.prevH = win.height;
        }

        public void move(String id, int dx, int dy, int maxRight, int maxBottom, int topBarH) {
            WindowState win = windows.get(id);
            if (win == null || win.isMaximized) return;
            win.x = clampX(win, win.x + dx, maxRight);
            win.y = clampY(win.y + dy, maxBottom, topBarH);
        }

        // 画面外への極端な移動を防止
        private static final int MIN_VISIBLE_X = 60;
        private static final int MIN_VISIBLE_Y = 24;

        public static int clampX(WindowState win, double x, int maxRight) {
            return (int) Math.round(Math.clamp(x, -win.width + MIN_VISIBLE_X, Math.max(0, maxRight - MIN_VISIBLE_X)));
        }

        public static int clampY(double y, int maxBottom, int topBarH) {
            return (int) Math.round(Math.clamp(y, topBarH, Math.max(topBarH, maxBottom - MIN_VISIBLE_Y)));
        }

        public WindowState findTopWindowAt(double x, double y) {
            WindowState top = null;
            int highestZ = -1;
            for (WindowState win : windows.values()) {
                if (win.isVisible()) {
                    if (x >= win.x && x <= win.x + win.width && y >= win.y && y <= win.y + win.height) {
                        if (win.zIndex > highestZ) {
                            highestZ = win.zIndex;
                            top = win;
                        }
                    }
                }
            }
            return top;
        }

        private void focusTopRemainingWindow() {
            WindowState top = null;
            int highestZ = -1;
            for (WindowState win : windows.values()) {
                if (win.isVisible() && win.zIndex > highestZ) {
                    highestZ = win.zIndex;
                    top = win;
                }
            }
            if (top != null) {
                activeWindowId = top.id;
            }
        }
    }

    /**
     * Linux 風デスクトップの配置と当たり判定の規則。
     *
     * <p>Minecraft の状態に触れず、仮想解像度と矩形だけで判定する（画面を開かずにテストできる）。</p>
     */
    public static final class DesktopLayout {

        /** トップパネルの高さ（linux_desktop.html の {@code #top-bar}）。 */
        public static final int TOP_BAR_HEIGHT = 28;
        /** 最大化時にドック用に空ける高さ。 */
        public static final int DOCK_HEIGHT = 48;
        /** 初期配置でドック（bottom: 8px + 本体）と重ならないよう下端に空ける高さ。 */
        static final int DOCK_RESERVED = 64;
        /** デスクトップアイコン列（left: 16px + width: 68px）の右端に余白を足した位置。 */
        static final int ICON_COLUMN_RIGHT = 96;
        static final int MARGIN = 8;
        /** 背面ウィンドウのタイトルバー（高さ 32px）が見えるよう前面ウィンドウをずらす量。 */
        static final int TITLE_BAR_REVEAL = 40;
        static final int MIN_WIDTH = 240;
        static final int MIN_HEIGHT = 160;

        private DesktopLayout() {}

        /** 軸に平行な矩形（仮想解像度の CSS px）。 */
        public record Rect(double x, double y, double width, double height) {
            public boolean contains(double px, double py) {
                return px >= x && px < x + width && py >= y && py < y + height;
            }

            public double right() {
                return x + width;
            }

            public double bottom() {
                return y + height;
            }

            public boolean intersects(Rect o) {
                return x < o.right() && o.x < right() && y < o.bottom() && o.y < bottom();
            }

            /** 描画要素の外形。transform の平行移動を含める（拡大・回転は扱わない）。 */
            public static @Nullable Rect of(@Nullable DisplayItem item) {
                if (item == null) return null;
                return new Rect(item.x() + item.translateX(), item.y() + item.translateY(), item.width(), item.height());
            }

            public static Rect of(DesktopWindowManager.WindowState win) {
                return new Rect(win.x, win.y, win.width, win.height);
            }
        }

        /**
         * メニューを開いた状態の押下でメニューを閉じるか。メニュー本体と Applications ボタンの上は
         * 閉じない（ボタンは自身の onclick でトグルする）。メニューの矩形が分からなければ閉じる。
         */
        public static boolean shouldCloseAppMenu(double x, double y, @Nullable Rect menu, @Nullable Rect menuButton) {
            if (menuButton != null && menuButton.contains(x, y)) return false;
            return menu == null || !menu.contains(x, y);
        }

        /** ドラッグ中のカーソルが画面の端に触れたときの吸着先（GNOME の端スナップ）。 */
        public enum SnapZone { NONE, MAXIMIZE, LEFT, RIGHT }

        /** 左右の端と見なす幅。カーソルが画面の外へは出ないので、端の数 px を当たりにする。 */
        static final int SNAP_EDGE = 4;

        /** カーソル位置の吸着先。左右の端を上端より優先する（左上・右上の角は半分に並べる）。 */
        public static SnapZone snapZoneAt(double x, double y, int desktopW) {
            if (x <= SNAP_EDGE) return SnapZone.LEFT;
            if (x >= desktopW - 1 - SNAP_EDGE) return SnapZone.RIGHT;
            if (y < TOP_BAR_HEIGHT) return SnapZone.MAXIMIZE;
            return SnapZone.NONE;
        }

        /** 吸着先に置いたときのウィンドウの矩形。トップパネルとドックの間を使う（最大化と同じ領域）。 */
        public static @Nullable Rect snapRect(SnapZone zone, int desktopW, int desktopH) {
            int top = TOP_BAR_HEIGHT;
            int h = Math.max(150, desktopH - TOP_BAR_HEIGHT - DOCK_HEIGHT);
            int half = desktopW / 2;
            return switch (zone) {
                case MAXIMIZE -> new Rect(0, top, Math.max(200, desktopW), h);
                case LEFT -> new Rect(0, top, half, h);
                case RIGHT -> new Rect(half, top, desktopW - half, h);
                case NONE -> null;
            };
        }

        /**
         * 起動時のウィンドウ配置。デスクトップアイコン列・トップパネル・ドックを避け、
         * ターミナルを右上、ドキュメントをその手前に少し下げて置き、両方のタイトルバーが見えるようにする。
         */
        public static void placeInitialWindows(DesktopWindowManager wm, int desktopW, int desktopH) {
            int left = ICON_COLUMN_RIGHT;
            int top = TOP_BAR_HEIGHT + MARGIN;
            int right = Math.max(left + MIN_WIDTH, desktopW - MARGIN);
            int bottom = Math.max(top + TITLE_BAR_REVEAL + MIN_HEIGHT, desktopH - DOCK_RESERVED);
            int areaW = right - left;
            int areaH = bottom - top;

            place(wm.get("terminal"), right - fit(480, areaW), top, fit(480, areaW), fit(270, areaH - TITLE_BAR_REVEAL));
            place(wm.get("docs"), left, top + TITLE_BAR_REVEAL, fit(600, areaW), fit(360, areaH - TITLE_BAR_REVEAL));
            place(wm.get("editor"), left + 60, top + 60, fit(440, areaW - 60), fit(260, areaH - 60));
            place(wm.get("sysmon"), left + 120, top + 90, fit(360, areaW - 120), fit(260, areaH - 90));
        }

        /** 狭い画面でも操作できる最小の辺。 */
        private static final int MIN_SIDE = 120;

        private static int fit(int preferred, int available) {
            return Math.max(MIN_SIDE, Math.min(preferred, available));
        }

        private static void place(@Nullable DesktopWindowManager.WindowState win, int x, int y, int w, int h) {
            if (win == null) return;
            win.x = x;
            win.y = y;
            win.width = w;
            win.height = h;
            win.prevX = x;
            win.prevY = y;
            win.prevW = w;
            win.prevH = h;
        }
    }

    /**
     * 表示中のあいだだけ、一定ティックごとに更新を許す間引き器。
     *
     * <p>非表示の間は何も許さず、次に表示されたティックで即座に更新を許す。</p>
     */
    public static final class TickThrottle {

        private final int intervalTicks;
        private int countdown = 0;

        public TickThrottle(int intervalTicks) {
            this.intervalTicks = Math.max(1, intervalTicks);
        }

        /** 1 ティック進め、このティックで更新すべきなら true を返す。 */
        public boolean tick(boolean active) {
            if (!active) {
                countdown = 0;
                return false;
            }
            if (--countdown > 0) return false;
            countdown = intervalTicks;
            return true;
        }
    }
}
