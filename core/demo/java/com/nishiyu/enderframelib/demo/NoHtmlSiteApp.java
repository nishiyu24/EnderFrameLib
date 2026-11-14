package com.nishiyu.enderframelib.demo;

import com.nishiyu.enderframelib.api.dom.Document;
import com.nishiyu.enderframelib.api.dom.Element;
import com.nishiyu.enderframelib.api.ui.State;
import com.nishiyu.enderframelib.api.ui.VNode;
import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.common.StandaloneViewer;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static com.nishiyu.enderframelib.api.ui.UI.*;

/**
 * HTML ファイルも HTML 文字列も一切使わず、Java の軽量 DOM ビルダー ({@link com.nishiyu.enderframelib.api.ui.UI})
 * だけで 1 から Web サイトを構築するデモアプリ。
 *
 * <pre>
 * ./gradlew :core:runNoHtmlSite                                 窓で表示（リサイズ可）
 * ./gradlew :core:runNoHtmlSite --args="--png no-html.png --after 1200"  窓なしで PNG 出力
 * </pre>
 */
public final class NoHtmlSiteApp {
    private static final DateTimeFormatter CLOCK_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private record TaskItem(int id, String title, String tag, boolean done) {
        TaskItem toggle() {
            return new TaskItem(id, title, tag, !done);
        }
    }

    private record PaletteCard(String name, String hex, String role) {}

    private static final List<PaletteCard> PALETTE = List.of(
            new PaletteCard("Sky Cyan", "#38bdf8", "Primary / Highlights"),
            new PaletteCard("Violet Pulse", "#8b5cf6", "Interactive Controls"),
            new PaletteCard("Emerald Mint", "#10b981", "Success & Healthy"),
            new PaletteCard("Amber Glow", "#f59e0b", "Warnings & Focus")
    );

    private NoHtmlSiteApp() {}

    public static void main(String[] args) throws Exception {
        StandaloneViewer.launchEmpty(args, NoHtmlSiteApp::wire);
    }

    static void wire(StandaloneView view) {
        Document doc = view.document();
        Element body = doc.getBody();

        var activeTab = State.of("dashboard");
        var darkMode = State.of(true);
        var accentHex = State.of("#38bdf8");
        var autoTick = State.of(true);
        var ticks = State.of(0);
        var counter = State.of(3);
        var clock = State.of(LocalTime.now().format(CLOCK_FMT));

        var nextTaskId = new AtomicInteger(5);
        var draftTitle = State.of("");
        var filterQuery = State.of("");
        var statusFilter = State.of("all"); // all | open | done
        var tasks = State.of(List.of(
                new TaskItem(1, "HTML レス DOM 構築を確認", "Core", true),
                new TaskItem(2, "watch / each で動的タブとリストを実装", "UI DSL", true),
                new TaskItem(3, "ダーク／ライトテーマとアクセントカラーを切替", "UI", false),
                new TaskItem(4, "ウィンドウリサイズとスクロール挙動を確認", "Layout", false)
        ));

        var selectedPalette = State.of(0);

        var openCount = State.derive(
                () -> (int) tasks.get().stream().filter(t -> !t.done()).count(),
                tasks
        );
        var doneCount = State.derive(
                () -> (int) tasks.get().stream().filter(TaskItem::done).count(),
                tasks
        );
        var filteredTasks = State.derive(() -> {
            String q = filterQuery.get().trim().toLowerCase();
            String status = statusFilter.get();
            return tasks.get().stream()
                    .filter(t -> switch (status) {
                        case "open" -> !t.done();
                        case "done" -> t.done();
                        default -> true;
                    })
                    .filter(t -> q.isEmpty()
                            || t.title().toLowerCase().contains(q)
                            || t.tag().toLowerCase().contains(q))
                    .toList();
        }, tasks, filterQuery, statusFilter);

        // <body> 全体の基本スタイルも直接 DOM へ反映
        Runnable applyBodyTheme = () -> {
            boolean dark = Boolean.TRUE.equals(darkMode.get());
            setStyle(body, Map.of(
                    "margin", "0",
                    "fontFamily", "sans-serif",
                    "background", dark ? "#0f172a" : "#f1f5f9",
                    "color", dark ? "#f8fafc" : "#0f172a"
            ));
        };
        applyBodyTheme.run();
        darkMode.onChange(v -> applyBodyTheme.run());

        // 1 秒ごとに時計とライブメトリクスを更新
        view.timers().setInterval(() -> {
            clock.set(LocalTime.now().format(CLOCK_FMT));
            if (Boolean.TRUE.equals(autoTick.get())) {
                ticks.update(t -> t + 1);
            }
        }, 1000);

        var surfaceBg = darkMode.when("#1e293b", "#ffffff");
        var mutedSurfaceBg = darkMode.when("#0b1220", "#e2e8f0");
        var borderColor = darkMode.when("#334155", "#cbd5e1");
        var mutedText = darkMode.when("#94a3b8", "#475569");
        var cardText = darkMode.when("#f8fafc", "#0f172a");

        render(body,
                div(id("app-shell"),
                        Header(activeTab, darkMode, accentHex, clock, openCount),
                        div(id("workspace"),
                                sx("position", "absolute", "left", "0", "top", "64px", "right", "0", "bottom", "36px",
                                        "display", "flex", "overflow", "hidden"),
                                Sidebar(activeTab, tasks, openCount, doneCount, accentHex, darkMode, surfaceBg, borderColor, mutedText),
                                mainTag(id("content-scroll"),
                                        sx("flex", "1", "overflowY", "auto", "padding", "24px",
                                                "display", "flex", "flexDirection", "column", "gap", "20px"),
                                        watch(activeTab, tab -> switch (tab) {
                                            case "tasks" -> TasksPage(
                                                    tasks, filteredTasks, draftTitle, filterQuery, statusFilter,
                                                    nextTaskId, accentHex, surfaceBg, mutedSurfaceBg, borderColor, mutedText, cardText
                                            );
                                            case "palette" -> PalettePage(
                                                    selectedPalette, accentHex, surfaceBg, borderColor, mutedText, cardText
                                            );
                                            case "settings" -> SettingsPage(
                                                    darkMode, accentHex, autoTick, surfaceBg, borderColor, mutedText
                                            );
                                            default -> DashboardPage(
                                                    ticks, counter, openCount, doneCount, autoTick, accentHex,
                                                    surfaceBg, mutedSurfaceBg, borderColor, mutedText
                                            );
                                        })
                                )
                        ),
                        Footer(activeTab, ticks, darkMode, accentHex, borderColor, mutedText)
                )
        );
    }

    private static VNode Header(
            State<String> activeTab,
            State<Boolean> darkMode,
            State<String> accentHex,
            State<String> clock,
            State<Integer> openCount
    ) {
        return header(
                sx("position", "absolute", "left", "0", "top", "0", "right", "0", "height", "64px",
                        "display", "flex", "alignItems", "center", "gap", "12px", "padding", "0 24px",
                        "background", darkMode.when("#020617", "#1e293b"),
                        "color", "#f8fafc",
                        "borderBottom", "1px solid #334155"),
                div(sx("width", "12px", "height", "12px", "borderRadius", "999px", "background", accentHex)),
                div(sx("display", "flex", "flexDirection", "column", "flex", "1"),
                        span(sx("fontSize", "17px", "fontWeight", "bold"), "EnderFrame Pure-Java Site"),
                        small(sx("fontSize", "11px", "color", "#94a3b8"), "0 HTML files / 0 HTML strings — built 100% from Java DSL")
                ),
                span(sx("fontSize", "12px", "padding", "4px 10px", "borderRadius", "999px",
                                "background", "#1e293b", "color", "#cbd5e1", "border", "1px solid #334155"),
                        openCount.map(n -> n + " open tasks")),
                span(sx("fontSize", "12px", "fontFamily", "monospace", "color", "#cbd5e1", "padding", "0 8px"), clock),
                nav(sx("display", "flex", "gap", "8px"),
                        NavButton("Dashboard", "dashboard", activeTab, accentHex),
                        NavButton("Tasks", "tasks", activeTab, accentHex),
                        NavButton("Palette", "palette", activeTab, accentHex),
                        NavButton("Settings", "settings", activeTab, accentHex)
                ),
                button(type("button"),
                        sx("padding", "7px 12px", "borderRadius", "8px", "border", "1px solid #475569",
                                "background", "#0f172a", "color", "#f8fafc", "fontSize", "12px", "cursor", "pointer"),
                        onClick(e -> darkMode.update(d -> !Boolean.TRUE.equals(d))),
                        darkMode.when("Light Mode", "Dark Mode"))
        );
    }

    private static VNode NavButton(String label, String key, State<String> activeTab, State<String> accentHex) {
        var isActive = activeTab.map(t -> t.equals(key));
        var bg = State.derive(() -> Boolean.TRUE.equals(isActive.get()) ? accentHex.get() : "#1e293b", isActive, accentHex);
        return button(
                type("button"),
                sx("padding", "8px 14px", "border", "0", "borderRadius", "8px", "fontSize", "13px",
                        "fontWeight", "bold", "cursor", "pointer",
                        "background", bg,
                        "color", isActive.when("#0f172a", "#e2e8f0")),
                onClick(e -> activeTab.set(key)),
                label
        );
    }

    private static VNode Sidebar(
            State<String> activeTab,
            State<List<TaskItem>> tasks,
            State<Integer> openCount,
            State<Integer> doneCount,
            State<String> accentHex,
            State<Boolean> darkMode,
            State<String> surfaceBg,
            State<String> borderColor,
            State<String> mutedText
    ) {
        return aside(
                sx("width", "230px", "flex", "none", "padding", "20px 16px",
                        "background", surfaceBg,
                        "borderRight", borderColor.map(c -> "1px solid " + c),
                        "display", "flex", "flexDirection", "column", "gap", "16px"),
                div(sx("fontSize", "11px", "fontWeight", "bold", "letterSpacing", "0.08em", "color", mutedText),
                        "WORKSPACE SUMMARY"),
                div(sx("display", "flex", "flexDirection", "column", "gap", "8px"),
                        StatRow("Total tasks", tasks.map(l -> String.valueOf(l.size())), darkMode, mutedText),
                        StatRow("Remaining", openCount.map(String::valueOf), darkMode, mutedText),
                        StatRow("Completed", doneCount.map(String::valueOf), darkMode, mutedText)
                ),
                div(sx("height", "1px", "background", borderColor)),
                div(sx("fontSize", "11px", "fontWeight", "bold", "letterSpacing", "0.08em", "color", mutedText),
                        "QUICK JUMP"),
                div(sx("display", "flex", "flexDirection", "column", "gap", "6px"),
                        SidebarLink("Overview & Live Pulse", "dashboard", activeTab, accentHex, darkMode),
                        SidebarLink("Task Manager", "tasks", activeTab, accentHex, darkMode),
                        SidebarLink("Theme Palette", "palette", activeTab, accentHex, darkMode),
                        SidebarLink("App Preferences", "settings", activeTab, accentHex, darkMode)
                )
        );
    }

    private static VNode StatRow(String label, State<String> value, State<Boolean> darkMode, State<String> mutedText) {
        return div(
                sx("display", "flex", "justifyContent", "space-between", "alignItems", "center",
                        "padding", "8px 10px", "borderRadius", "8px",
                        "background", darkMode.when("#0f172a", "#f8fafc")),
                span(sx("fontSize", "13px", "color", mutedText), label),
                span(sx("fontSize", "14px", "fontWeight", "bold"), value)
        );
    }

    private static VNode SidebarLink(
            String label,
            String key,
            State<String> activeTab,
            State<String> accentHex,
            State<Boolean> darkMode
    ) {
        var active = activeTab.map(t -> t.equals(key));
        var bg = State.derive(
                () -> Boolean.TRUE.equals(active.get())
                        ? (Boolean.TRUE.equals(darkMode.get()) ? "#334155" : "#e2e8f0")
                        : "transparent",
                active, darkMode
        );
        var fg = State.derive(
                () -> Boolean.TRUE.equals(active.get())
                        ? accentHex.get()
                        : (Boolean.TRUE.equals(darkMode.get()) ? "#cbd5e1" : "#334155"),
                active, accentHex, darkMode
        );
        return button(
                type("button"),
                sx("textAlign", "left", "padding", "9px 12px", "border", "0", "borderRadius", "8px",
                        "fontSize", "13px", "cursor", "pointer",
                        "background", bg,
                        "color", fg,
                        "fontWeight", active.when("bold", "normal")),
                onClick(e -> activeTab.set(key)),
                label
        );
    }

    private static VNode DashboardPage(
            State<Integer> ticks,
            State<Integer> counter,
            State<Integer> openCount,
            State<Integer> doneCount,
            State<Boolean> autoTick,
            State<String> accentHex,
            State<String> surfaceBg,
            State<String> mutedSurfaceBg,
            State<String> borderColor,
            State<String> mutedText
    ) {
        return section(
                sx("display", "flex", "flexDirection", "column", "gap", "18px"),
                // Hero panel
                div(sx("padding", "22px", "borderRadius", "14px",
                                "background", surfaceBg,
                                "border", borderColor.map(c -> "1px solid " + c),
                                "display", "flex", "flexDirection", "column", "gap", "10px"),
                        h1(sx("margin", "0", "fontSize", "24px"), "Built Entirely Without HTML"),
                        p(sx("margin", "0", "fontSize", "14px", "color", mutedText, "lineHeight", "1.6"),
                                "This entire web interface is composed from pure-Java UI builders, State / State.derive, "
                                        + "reactive watch() routing, and each() list rendering starting from an empty document."),
                        div(sx("display", "flex", "gap", "10px", "marginTop", "6px"),
                                button(type("button"),
                                        sx("padding", "9px 16px", "border", "0", "borderRadius", "8px", "fontWeight", "bold",
                                                "background", accentHex, "color", "#0f172a", "cursor", "pointer"),
                                        onClick(e -> counter.update(c -> c + 1)),
                                        "Boost Scale (+1)"),
                                button(type("button"),
                                        sx("padding", "9px 16px", "borderRadius", "8px", "fontWeight", "bold",
                                                "background", mutedSurfaceBg, "color", "#94a3b8",
                                                "border", borderColor.map(c -> "1px solid " + c), "cursor", "pointer"),
                                        onClick(e -> counter.set(1)),
                                        "Reset Scale"),
                                button(type("button"),
                                        sx("padding", "9px 16px", "border", "0", "borderRadius", "8px", "fontWeight", "bold",
                                                "background", autoTick.when("#10b981", "#64748b"),
                                                "color", "#ffffff", "cursor", "pointer"),
                                        onClick(e -> autoTick.update(v -> !Boolean.TRUE.equals(v))),
                                        autoTick.when("Live Pulse: ON", "Live Pulse: PAUSED"))
                        )
                ),
                // KPI Cards
                div(sx("display", "flex", "gap", "14px", "flexWrap", "wrap"),
                        KpiCard("Live Timer Ticks", ticks.map(t -> t + "s"), "setInterval(1000ms)", accentHex, surfaceBg, borderColor, mutedText),
                        KpiCard("Interactive Scale", counter.map(c -> "x" + c), "State<Integer> binding", accentHex, surfaceBg, borderColor, mutedText),
                        KpiCard("Open Tasks", openCount.map(String::valueOf), "Derived via State.derive", accentHex, surfaceBg, borderColor, mutedText),
                        KpiCard("Completed Tasks", doneCount.map(String::valueOf), "Derived via State.derive", accentHex, surfaceBg, borderColor, mutedText)
                ),
                // Live signal bars
                div(sx("padding", "20px", "borderRadius", "14px",
                                "background", surfaceBg,
                                "border", borderColor.map(c -> "1px solid " + c),
                                "display", "flex", "flexDirection", "column", "gap", "12px"),
                        h2(sx("margin", "0", "fontSize", "16px"), "Reactive Signal Channels"),
                        map(4, idx -> SignalBar("Channel " + (idx + 1), idx, ticks, counter, accentHex, mutedSurfaceBg, mutedText))
                )
        );
    }

    private static VNode KpiCard(
            String title,
            State<String> value,
            String subtitle,
            State<String> accentHex,
            State<String> surfaceBg,
            State<String> borderColor,
            State<String> mutedText
    ) {
        return div(
                sx("flex", "1", "minWidth", "190px", "padding", "16px", "borderRadius", "12px",
                        "background", surfaceBg,
                        "border", borderColor.map(c -> "1px solid " + c),
                        "display", "flex", "flexDirection", "column", "gap", "6px"),
                span(sx("fontSize", "12px", "color", mutedText), title),
                span(sx("fontSize", "26px", "fontWeight", "bold", "color", accentHex), value),
                small(sx("fontSize", "11px", "color", mutedText), subtitle)
        );
    }

    private static VNode SignalBar(
            String label,
            int channel,
            State<Integer> ticks,
            State<Integer> counter,
            State<String> accentHex,
            State<String> trackBg,
            State<String> mutedText
    ) {
        var percent = State.derive(
                () -> 20 + ((ticks.get() * (channel + 2) * 11 + counter.get() * 17 + channel * 13) % 75),
                ticks, counter
        );
        return div(
                sx("display", "flex", "alignItems", "center", "gap", "12px"),
                span(sx("width", "92px", "fontSize", "13px", "color", mutedText), label),
                div(sx("flex", "1", "height", "12px", "borderRadius", "999px", "background", trackBg, "overflow", "hidden"),
                        div(sx("height", "100%", "borderRadius", "999px",
                                "background", accentHex,
                                "width", percent.map(p -> p + "%")))),
                span(sx("width", "44px", "textAlign", "right", "fontSize", "12px", "fontFamily", "monospace"),
                        percent.map(p -> p + "%"))
        );
    }

    private static VNode TasksPage(
            State<List<TaskItem>> tasks,
            State<List<TaskItem>> filteredTasks,
            State<String> draftTitle,
            State<String> filterQuery,
            State<String> statusFilter,
            AtomicInteger nextTaskId,
            State<String> accentHex,
            State<String> surfaceBg,
            State<String> mutedSurfaceBg,
            State<String> borderColor,
            State<String> mutedText,
            State<String> cardText
    ) {
        return section(
                sx("display", "flex", "flexDirection", "column", "gap", "16px"),
                div(sx("padding", "18px", "borderRadius", "14px",
                                "background", surfaceBg,
                                "border", borderColor.map(c -> "1px solid " + c),
                                "display", "flex", "flexDirection", "column", "gap", "12px"),
                        h2(sx("margin", "0", "fontSize", "18px"), "Reactive Task Board"),
                        // 新規タスク追加フォーム
                        div(sx("display", "flex", "gap", "10px", "alignItems", "center"),
                                input(id("new-task-input"), type("text"),
                                        placeholder("新しいタスク名を入力..."),
                                        value(draftTitle),
                                        sx("flex", "1", "padding", "9px 12px", "borderRadius", "8px",
                                                "border", borderColor.map(c -> "1px solid " + c),
                                                "background", mutedSurfaceBg, "color", cardText),
                                        onInputValue(draftTitle::set)),
                                button(type("button"),
                                        sx("padding", "9px 16px", "border", "0", "borderRadius", "8px",
                                                "background", accentHex, "color", "#0f172a", "fontWeight", "bold", "cursor", "pointer"),
                                        onClick(e -> {
                                            String title = draftTitle.get().trim();
                                            if (title.isEmpty()) {
                                                title = "Dynamic Java Component #" + nextTaskId.get();
                                            }
                                            int id = nextTaskId.getAndIncrement();
                                            String finalTitle = title;
                                            tasks.update(list -> {
                                                List<TaskItem> next = new ArrayList<>(list);
                                                next.add(0, new TaskItem(id, finalTitle, "Custom", false));
                                                return next;
                                            });
                                            draftTitle.set("");
                                        }),
                                        "+ Add Task")
                        ),
                        // 検索 & ステータスフィルタ
                        div(sx("display", "flex", "gap", "10px", "alignItems", "center", "flexWrap", "wrap"),
                                input(id("task-search"), type("text"),
                                        placeholder("キーワードで絞り込み..."),
                                        value(filterQuery),
                                        sx("width", "260px", "padding", "8px 12px", "borderRadius", "8px",
                                                "border", borderColor.map(c -> "1px solid " + c),
                                                "background", mutedSurfaceBg, "color", cardText),
                                        onInputValue(filterQuery::set)),
                                FilterChip("All", "all", statusFilter, accentHex, mutedSurfaceBg, cardText),
                                FilterChip("Open", "open", statusFilter, accentHex, mutedSurfaceBg, cardText),
                                FilterChip("Done", "done", statusFilter, accentHex, mutedSurfaceBg, cardText)
                        )
                ),
                // タスク一覧 (each)
                div(sx("display", "flex", "flexDirection", "column", "gap", "10px"),
                        each(filteredTasks, item -> div(
                                sx("display", "flex", "alignItems", "center", "gap", "12px",
                                        "padding", "14px 16px", "borderRadius", "12px",
                                        "background", surfaceBg,
                                        "border", borderColor.map(c -> "1px solid " + c)),
                                button(type("button"),
                                        sx("padding", "6px 12px", "border", "0", "borderRadius", "6px",
                                                "fontSize", "12px", "fontWeight", "bold", "cursor", "pointer",
                                                "background", item.done() ? "#10b981" : "#334155",
                                                "color", "#ffffff"),
                                        onClick(e -> tasks.update(list -> list.stream()
                                                .map(t -> t.id() == item.id() ? t.toggle() : t)
                                                .toList())),
                                        item.done() ? "DONE" : "OPEN"),
                                div(sx("flex", "1", "display", "flex", "flexDirection", "column", "gap", "2px"),
                                        span(sx("fontSize", "14px", "fontWeight", "bold",
                                                "color", item.done() ? mutedText : cardText), item.title()),
                                        small(sx("fontSize", "11px", "color", mutedText), "#" + item.id() + "  [" + item.tag() + "]")
                                ),
                                button(type("button"),
                                        sx("padding", "6px 10px", "border", "0", "borderRadius", "6px",
                                                "background", "#ef4444", "color", "#ffffff", "fontSize", "12px", "cursor", "pointer"),
                                        onClick(e -> tasks.update(list -> list.stream()
                                                .filter(t -> t.id() != item.id())
                                                .toList())),
                                        "Delete")
                        ))
                )
        );
    }

    private static VNode FilterChip(
            String label,
            String key,
            State<String> statusFilter,
            State<String> accentHex,
            State<String> mutedSurfaceBg,
            State<String> cardText
    ) {
        var active = statusFilter.map(s -> s.equals(key));
        var bg = State.derive(
                () -> Boolean.TRUE.equals(active.get()) ? accentHex.get() : mutedSurfaceBg.get(),
                active, accentHex, mutedSurfaceBg
        );
        var fg = State.derive(
                () -> Boolean.TRUE.equals(active.get()) ? "#0f172a" : cardText.get(),
                active, cardText
        );
        return button(
                type("button"),
                sx("padding", "7px 14px", "border", "0", "borderRadius", "999px",
                        "fontSize", "12px", "fontWeight", "bold", "cursor", "pointer",
                        "background", bg,
                        "color", fg),
                onClick(e -> statusFilter.set(key)),
                label
        );
    }

    private static VNode PalettePage(
            State<Integer> selectedPalette,
            State<String> accentHex,
            State<String> surfaceBg,
            State<String> borderColor,
            State<String> mutedText,
            State<String> cardText
    ) {
        return section(
                sx("display", "flex", "flexDirection", "column", "gap", "16px"),
                h2(sx("margin", "0", "fontSize", "18px"), "Interactive Accent Palette"),
                p(sx("margin", "0", "fontSize", "13px", "color", mutedText),
                        "カードをクリックするとサイト全体のアクセントカラーが即座に切り替わります。"),
                div(sx("display", "flex", "gap", "14px", "flexWrap", "wrap"),
                        map(PALETTE.size(), idx -> {
                            PaletteCard card = PALETTE.get(idx);
                            var isSelected = selectedPalette.map(s -> s == idx);
                            var cardBorder = State.derive(
                                    () -> Boolean.TRUE.equals(isSelected.get())
                                            ? "2px solid " + card.hex()
                                            : "1px solid " + borderColor.get(),
                                    isSelected, borderColor
                            );
                            return div(
                                    sx("width", "220px", "padding", "16px", "borderRadius", "12px", "cursor", "pointer",
                                            "background", surfaceBg,
                                            "border", cardBorder,
                                            "display", "flex", "flexDirection", "column", "gap", "10px"),
                                    onClick(e -> {
                                        selectedPalette.set(idx);
                                        accentHex.set(card.hex());
                                    }),
                                    div(sx("height", "72px", "borderRadius", "8px", "background", card.hex())),
                                    span(sx("fontSize", "15px", "fontWeight", "bold", "color", cardText), card.name()),
                                    code(sx("fontSize", "12px", "color", mutedText), card.hex() + " — " + card.role())
                            );
                        })
                )
        );
    }

    private static VNode SettingsPage(
            State<Boolean> darkMode,
            State<String> accentHex,
            State<Boolean> autoTick,
            State<String> surfaceBg,
            State<String> borderColor,
            State<String> mutedText
    ) {
        return section(
                sx("padding", "22px", "borderRadius", "14px",
                        "background", surfaceBg,
                        "border", borderColor.map(c -> "1px solid " + c),
                        "display", "flex", "flexDirection", "column", "gap", "16px"),
                h2(sx("margin", "0", "fontSize", "18px"), "Preferences"),
                div(sx("display", "flex", "alignItems", "center", "justifyContent", "space-between"),
                        div(sx("display", "flex", "flexDirection", "column", "gap", "2px"),
                                span(sx("fontWeight", "bold"), "Appearance Theme"),
                                small(sx("color", mutedText), "Toggle dark/light theme applied directly to <body> style")
                        ),
                        button(type("button"),
                                sx("padding", "9px 16px", "border", "0", "borderRadius", "8px",
                                        "background", accentHex, "color", "#0f172a", "fontWeight", "bold", "cursor", "pointer"),
                                onClick(e -> darkMode.update(d -> !Boolean.TRUE.equals(d))),
                                darkMode.when("Switch to Light Theme", "Switch to Dark Theme"))
                ),
                div(sx("display", "flex", "alignItems", "center", "justifyContent", "space-between"),
                        div(sx("display", "flex", "flexDirection", "column", "gap", "2px"),
                                span(sx("fontWeight", "bold"), "Realtime Signal Timer"),
                                small(sx("color", mutedText), "Controls 1-second setInterval signal pulse updates")
                        ),
                        button(type("button"),
                                sx("padding", "9px 16px", "border", "0", "borderRadius", "8px",
                                        "background", autoTick.when("#10b981", "#64748b"), "color", "#ffffff",
                                        "fontWeight", "bold", "cursor", "pointer"),
                                onClick(e -> autoTick.update(v -> !Boolean.TRUE.equals(v))),
                                autoTick.when("Pause Timer", "Resume Timer"))
                )
        );
    }

    private static VNode Footer(
            State<String> activeTab,
            State<Integer> ticks,
            State<Boolean> darkMode,
            State<String> accentHex,
            State<String> borderColor,
            State<String> mutedText
    ) {
        var status = State.derive(
                () -> "tab=" + activeTab.get()
                        + " | ticks=" + ticks.get()
                        + " | theme=" + (Boolean.TRUE.equals(darkMode.get()) ? "dark" : "light")
                        + " | accent=" + accentHex.get(),
                activeTab, ticks, darkMode, accentHex
        );
        return footer(
                sx("position", "absolute", "left", "0", "right", "0", "bottom", "0", "height", "36px",
                        "display", "flex", "alignItems", "center", "justifyContent", "space-between",
                        "padding", "0 24px", "fontSize", "12px",
                        "background", darkMode.when("#020617", "#e2e8f0"),
                        "borderTop", borderColor.map(c -> "1px solid " + c),
                        "color", mutedText),
                span("EnderFrameLib Demo — No HTML Mode"),
                span(sx("fontFamily", "monospace"), status)
        );
    }
}
