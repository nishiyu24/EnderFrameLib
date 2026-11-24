package com.nishiyu.enderframelib.demo;

import com.nishiyu.enderframelib.api.dom.Element;
import com.nishiyu.enderframelib.api.ui.State;
import com.nishiyu.enderframelib.api.ui.VNode;
import com.nishiyu.enderframelib.api.ui.style.Color;
import com.nishiyu.enderframelib.api.ui.style.Length;
import com.nishiyu.enderframelib.api.ui.style.Theme;
import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.common.StandaloneViewer;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.nishiyu.enderframelib.api.ui.UI.*;
import static com.nishiyu.enderframelib.api.ui.style.Css.hex;
import static com.nishiyu.enderframelib.api.ui.style.Css.inline;
import static com.nishiyu.enderframelib.api.ui.style.Css.width;
import static com.nishiyu.enderframelib.demo.NoHtmlSiteStyles.*;

/**
 * HTML ファイルも HTML 文字列も CSS 文字列も一切使わず、Java の軽量 DOM ビルダー ({@link com.nishiyu.enderframelib.api.ui.UI})
 * と型付きスタイル ({@link com.nishiyu.enderframelib.api.ui.style}) だけで 1 から Web サイトを構築するデモアプリ。
 *
 * <p>見た目は {@link NoHtmlSiteStyles} に集め、ここは構造と状態だけを書く。テーマはトークン（CSS 変数）の組で、
 * 切り替えは body のクラス 1 つ、アクセント色は {@code ACCENT} トークン 1 つを変えるだけで全体に及ぶ。</p>
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

    private record PaletteCard(String name, Color color, String role) {}

    private static final List<PaletteCard> PALETTE = List.of(
            new PaletteCard("Sky Cyan", hex(0x38bdf8), "Primary / Highlights"),
            new PaletteCard("Violet Pulse", hex(0x8b5cf6), "Interactive Controls"),
            new PaletteCard("Emerald Mint", hex(0x10b981), "Success & Healthy"),
            new PaletteCard("Amber Glow", hex(0xf59e0b), "Warnings & Focus")
    );

    private NoHtmlSiteApp() {}

    public static void main(String[] args) throws Exception {
        StandaloneViewer.launchEmpty(args, NoHtmlSiteApp::wire);
    }

    static void wire(StandaloneView view) {
        Element body = view.document().getBody();

        var activeTab = State.of("dashboard");
        var darkMode = State.of(true);
        var accent = State.of(PALETTE.getFirst().color());
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

        // テーマ（トークンの組）とアクセント色を body に付ける。子孫はトークン経由で色を受け取る
        PAGE.apply(body);
        Theme.select(darkMode.map(d -> d ? DARK : LIGHT)).apply(body);
        ACCENT.bind(accent).apply(body);

        // 1 秒ごとに時計とライブメトリクスを更新
        view.timers().setInterval(() -> {
            clock.set(LocalTime.now().format(CLOCK_FMT));
            if (Boolean.TRUE.equals(autoTick.get())) {
                ticks.update(t -> t + 1);
            }
        }, 1000);

        render(body,
                div(id("app-shell"),
                        Header(activeTab, darkMode, clock, openCount),
                        div(id("workspace"), WORKSPACE,
                                Sidebar(activeTab, tasks, openCount, doneCount),
                                mainTag(id("content-scroll"), CONTENT,
                                        watch(activeTab, tab -> switch (tab) {
                                            case "tasks" -> TasksPage(tasks, filteredTasks, draftTitle, filterQuery, statusFilter, nextTaskId);
                                            case "palette" -> PalettePage(selectedPalette, accent);
                                            case "settings" -> SettingsPage(darkMode, autoTick);
                                            default -> DashboardPage(ticks, counter, openCount, doneCount, autoTick);
                                        })
                                )
                        ),
                        Footer(activeTab, ticks, darkMode, accent)
                )
        );
    }

    private static VNode Header(State<String> activeTab, State<Boolean> darkMode, State<String> clock, State<Integer> openCount) {
        return header(HEADER,
                div(DOT),
                div(column(0), GROW,
                        span(BRAND_TITLE, "EnderFrame Pure-Java Site"),
                        small(BRAND_SUB, "0 HTML files / 0 HTML strings / 0 CSS strings — built 100% from Java DSL")
                ),
                span(PILL, openCount.map(n -> n + " open tasks")),
                span(CLOCK, clock),
                nav(row(8),
                        NavButton("Dashboard", "dashboard", activeTab),
                        NavButton("Tasks", "tasks", activeTab),
                        NavButton("Palette", "palette", activeTab),
                        NavButton("Settings", "settings", activeTab)
                ),
                button(type("button"), THEME_BTN,
                        onClick(e -> darkMode.update(d -> !Boolean.TRUE.equals(d))),
                        darkMode.when("Light Mode", "Dark Mode"))
        );
    }

    private static VNode NavButton(String label, String key, State<String> activeTab) {
        return button(type("button"), NAV_BTN, NAV_BTN.is(ACTIVE, activeTab.map(key::equals)),
                onClick(e -> activeTab.set(key)),
                label);
    }

    private static VNode Sidebar(State<String> activeTab, State<List<TaskItem>> tasks,
                                 State<Integer> openCount, State<Integer> doneCount) {
        return aside(SIDEBAR,
                div(SECTION_LABEL, "WORKSPACE SUMMARY"),
                div(column(8),
                        StatRow("Total tasks", tasks.map(l -> String.valueOf(l.size()))),
                        StatRow("Remaining", openCount.map(String::valueOf)),
                        StatRow("Completed", doneCount.map(String::valueOf))
                ),
                div(DIVIDER),
                div(SECTION_LABEL, "QUICK JUMP"),
                div(column(6),
                        SidebarLink("Overview & Live Pulse", "dashboard", activeTab),
                        SidebarLink("Task Manager", "tasks", activeTab),
                        SidebarLink("Theme Palette", "palette", activeTab),
                        SidebarLink("App Preferences", "settings", activeTab)
                )
        );
    }

    private static VNode StatRow(String label, State<String> value) {
        return div(STAT_ROW,
                span(LABEL, label),
                span(STAT_VALUE, value));
    }

    private static VNode SidebarLink(String label, String key, State<String> activeTab) {
        return button(type("button"), SIDE_LINK, SIDE_LINK.is(ACTIVE, activeTab.map(key::equals)),
                onClick(e -> activeTab.set(key)),
                label);
    }

    private static VNode DashboardPage(State<Integer> ticks, State<Integer> counter, State<Integer> openCount,
                                       State<Integer> doneCount, State<Boolean> autoTick) {
        return section(column(18),
                // Hero panel
                div(CARD,
                        h1(TITLE, "Built Entirely Without HTML"),
                        p(LEAD, "This entire web interface is composed from pure-Java UI builders, State / State.derive, "
                                + "typed style classes and theme tokens, reactive watch() routing, and each() list rendering "
                                + "starting from an empty document."),
                        div(row(10),
                                button(type("button"), BTN_PRIMARY,
                                        onClick(e -> counter.update(c -> c + 1)),
                                        "Boost Scale (+1)"),
                                button(type("button"), BTN_GHOST,
                                        onClick(e -> counter.set(1)),
                                        "Reset Scale"),
                                button(type("button"), BTN_TOGGLE, BTN_TOGGLE.is(ON, autoTick),
                                        onClick(e -> autoTick.update(v -> !Boolean.TRUE.equals(v))),
                                        autoTick.when("Live Pulse: ON", "Live Pulse: PAUSED"))
                        )
                ),
                // KPI Cards
                div(row(14), WRAP,
                        KpiCard("Live Timer Ticks", ticks.map(t -> t + "s"), "setInterval(1000ms)"),
                        KpiCard("Interactive Scale", counter.map(c -> "x" + c), "State<Integer> binding"),
                        KpiCard("Open Tasks", openCount.map(String::valueOf), "Derived via State.derive"),
                        KpiCard("Completed Tasks", doneCount.map(String::valueOf), "Derived via State.derive")
                ),
                // Live signal bars
                div(CARD,
                        h2(HEADING, "Reactive Signal Channels"),
                        map(4, idx -> SignalBar("Channel " + (idx + 1), idx, ticks, counter))
                )
        );
    }

    private static VNode KpiCard(String title, State<String> value, String subtitle) {
        return div(KPI,
                span(KPI_LABEL, title),
                span(KPI_VALUE, value),
                small(CAPTION, subtitle));
    }

    private static VNode SignalBar(String label, int channel, State<Integer> ticks, State<Integer> counter) {
        var percent = State.derive(
                () -> 20 + ((ticks.get() * (channel + 2) * 11 + counter.get() * 17 + channel * 13) % 75),
                ticks, counter
        );
        return div(row(12),
                span(SIGNAL_LABEL, label),
                div(TRACK,
                        div(TRACK_FILL, inline(width(percent.map(Length::pct))))),
                span(SIGNAL_VALUE, percent.map(p -> p + "%"))
        );
    }

    private static VNode TasksPage(State<List<TaskItem>> tasks, State<List<TaskItem>> filteredTasks,
                                   State<String> draftTitle, State<String> filterQuery, State<String> statusFilter,
                                   AtomicInteger nextTaskId) {
        return section(column(16),
                div(CARD,
                        h2(HEADING, "Reactive Task Board"),
                        // 新規タスク追加フォーム
                        div(row(10),
                                input(id("new-task-input"), type("text"), INPUT, GROW,
                                        placeholder("新しいタスク名を入力..."),
                                        value(draftTitle),
                                        onInputValue(draftTitle::set)),
                                button(type("button"), BTN_PRIMARY,
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
                        div(row(10), WRAP,
                                input(id("task-search"), type("text"), SEARCH,
                                        placeholder("キーワードで絞り込み..."),
                                        value(filterQuery),
                                        onInputValue(filterQuery::set)),
                                FilterChip("All", "all", statusFilter),
                                FilterChip("Open", "open", statusFilter),
                                FilterChip("Done", "done", statusFilter)
                        )
                ),
                // タスク一覧 (each)
                div(column(10),
                        each(filteredTasks, item -> div(TASK_ROW,
                                button(type("button"), BTN_STATUS, BTN_STATUS.is(DONE, item.done()),
                                        onClick(e -> tasks.update(list -> list.stream()
                                                .map(t -> t.id() == item.id() ? t.toggle() : t)
                                                .toList())),
                                        item.done() ? "DONE" : "OPEN"),
                                div(column(2), GROW,
                                        span(TASK_TITLE, TASK_TITLE.is(DONE, item.done()), item.title()),
                                        small(CAPTION, "#" + item.id() + "  [" + item.tag() + "]")
                                ),
                                button(type("button"), BTN_DANGER,
                                        onClick(e -> tasks.update(list -> list.stream()
                                                .filter(t -> t.id() != item.id())
                                                .toList())),
                                        "Delete")
                        ))
                )
        );
    }

    private static VNode FilterChip(String label, String key, State<String> statusFilter) {
        return button(type("button"), FILTER_CHIP, FILTER_CHIP.is(ACTIVE, statusFilter.map(key::equals)),
                onClick(e -> statusFilter.set(key)),
                label);
    }

    private static VNode PalettePage(State<Integer> selectedPalette, State<Color> accent) {
        return section(column(16),
                h2(HEADING, "Interactive Accent Palette"),
                p(NOTE, "カードをクリックするとサイト全体のアクセントカラーが即座に切り替わります。"),
                div(row(14), WRAP,
                        map(PALETTE.size(), idx -> {
                            PaletteCard card = PALETTE.get(idx);
                            return div(SWATCH_CARD, SWATCH_CARD.is(ACTIVE, selectedPalette.map(s -> s == idx)),
                                    inline(SWATCH.is(card.color())),
                                    onClick(e -> {
                                        selectedPalette.set(idx);
                                        accent.set(card.color());
                                    }),
                                    div(SWATCH_PREVIEW),
                                    span(SWATCH_NAME, card.name()),
                                    code(SWATCH_CODE, card.color().hexString() + " — " + card.role())
                            );
                        })
                )
        );
    }

    private static VNode SettingsPage(State<Boolean> darkMode, State<Boolean> autoTick) {
        return section(CARD,
                h2(HEADING, "Preferences"),
                div(SPREAD,
                        div(column(2),
                                span(STRONG, "Appearance Theme"),
                                small(CAPTION, "Toggle dark/light theme tokens on <body>")
                        ),
                        button(type("button"), BTN_PRIMARY,
                                onClick(e -> darkMode.update(d -> !Boolean.TRUE.equals(d))),
                                darkMode.when("Switch to Light Theme", "Switch to Dark Theme"))
                ),
                div(SPREAD,
                        div(column(2),
                                span(STRONG, "Realtime Signal Timer"),
                                small(CAPTION, "Controls 1-second setInterval signal pulse updates")
                        ),
                        button(type("button"), BTN_TOGGLE, BTN_TOGGLE.is(ON, autoTick),
                                onClick(e -> autoTick.update(v -> !Boolean.TRUE.equals(v))),
                                autoTick.when("Pause Timer", "Resume Timer"))
                )
        );
    }

    private static VNode Footer(State<String> activeTab, State<Integer> ticks, State<Boolean> darkMode, State<Color> accent) {
        var status = State.derive(
                () -> "tab=" + activeTab.get()
                        + " | ticks=" + ticks.get()
                        + " | theme=" + (Boolean.TRUE.equals(darkMode.get()) ? "dark" : "light")
                        + " | accent=" + accent.get().hexString(),
                activeTab, ticks, darkMode, accent
        );
        return footer(FOOTER,
                span("EnderFrameLib Demo — No HTML Mode"),
                span(MONO, status)
        );
    }
}
