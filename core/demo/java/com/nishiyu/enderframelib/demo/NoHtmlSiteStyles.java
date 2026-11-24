package com.nishiyu.enderframelib.demo;

import com.nishiyu.enderframelib.api.ui.style.Color;
import com.nishiyu.enderframelib.api.ui.style.Modifier;
import com.nishiyu.enderframelib.api.ui.style.StyleClass;
import com.nishiyu.enderframelib.api.ui.style.Theme;
import com.nishiyu.enderframelib.api.ui.style.Token;

import static com.nishiyu.enderframelib.api.ui.style.Css.*;
import static com.nishiyu.enderframelib.api.ui.style.Keyword.*;

/**
 * {@link NoHtmlSiteApp} の見た目（Java で書いたスタイルシート）。色はトークン、見た目はクラス、状態は {@link Modifier}。
 * コンポーネント側は構造と状態だけを持ち、色や寸法を知らない。
 */
final class NoHtmlSiteStyles {

    private NoHtmlSiteStyles() {}

    // ---- トークンとテーマ

    static final Token<Color> BG = Token.of("bg");
    static final Token<Color> SURFACE = Token.of("surface");
    static final Token<Color> SURFACE_MUTED = Token.of("surface-muted");
    static final Token<Color> BORDER = Token.of("border");
    static final Token<Color> TEXT = Token.of("text");
    static final Token<Color> TEXT_MUTED = Token.of("text-muted");
    static final Token<Color> HEADER_BG = Token.of("header-bg");
    static final Token<Color> FOOTER_BG = Token.of("footer-bg");
    static final Token<Color> ROW_BG = Token.of("row-bg");
    static final Token<Color> LINK_TEXT = Token.of("link-text");
    static final Token<Color> LINK_ACTIVE_BG = Token.of("link-active-bg");
    /** アクセント色（パレットで選んだ色を {@code Token.bind} で配る）。 */
    static final Token<Color> ACCENT = Token.of("accent");
    /** パレットのカードごとの見本色（カードの要素にインラインで与える）。 */
    static final Token<Color> SWATCH = Token.of("swatch");

    static final Theme DARK = Theme.of("dark",
            BG.is(hex(0x0f172a)), SURFACE.is(hex(0x1e293b)), SURFACE_MUTED.is(hex(0x0b1220)),
            BORDER.is(hex(0x334155)), TEXT.is(hex(0xf8fafc)), TEXT_MUTED.is(hex(0x94a3b8)),
            HEADER_BG.is(hex(0x020617)), FOOTER_BG.is(hex(0x020617)), ROW_BG.is(hex(0x0f172a)),
            LINK_TEXT.is(hex(0xcbd5e1)), LINK_ACTIVE_BG.is(hex(0x334155)));

    static final Theme LIGHT = Theme.of("light",
            BG.is(hex(0xf1f5f9)), SURFACE.is(hex(0xffffff)), SURFACE_MUTED.is(hex(0xe2e8f0)),
            BORDER.is(hex(0xcbd5e1)), TEXT.is(hex(0x0f172a)), TEXT_MUTED.is(hex(0x475569)),
            HEADER_BG.is(hex(0x1e293b)), FOOTER_BG.is(hex(0xe2e8f0)), ROW_BG.is(hex(0xf8fafc)),
            LINK_TEXT.is(hex(0x334155)), LINK_ACTIVE_BG.is(hex(0xe2e8f0)));

    private static final Color INK = hex(0x0f172a);
    private static final Color SUCCESS = hex(0x10b981);

    // ---- 状態

    static final Modifier ACTIVE = Modifier.of("active");
    static final Modifier ON = Modifier.of("on");
    static final Modifier DONE = Modifier.of("done");

    // ---- 並べ方（同じ間隔なら同じクラスを共有する）

    static StyleClass column(int gapPx) {
        return StyleClass.of("col-" + gapPx, display(Display.FLEX), flexDirection(FlexDirection.COLUMN), gap(px(gapPx)));
    }

    static StyleClass row(int gapPx) {
        return StyleClass.of("row-" + gapPx, display(Display.FLEX), alignItems(Alignment.CENTER), gap(px(gapPx)));
    }

    static final StyleClass WRAP = StyleClass.of("wrap", flexWrap(FlexWrap.WRAP));
    static final StyleClass GROW = StyleClass.of("grow", flex(1));
    static final StyleClass SPREAD = StyleClass.of("spread", display(Display.FLEX), alignItems(Alignment.CENTER),
            justifyContent(Alignment.SPACE_BETWEEN));

    // ---- 骨格

    static final StyleClass PAGE = StyleClass.of("page", margin(ZERO), fontFamily(FontFamily.SANS_SERIF),
            background(BG), color(TEXT));

    static final StyleClass HEADER = StyleClass.of("site-header", row(12),
            position(Position.ABSOLUTE), left(ZERO), top(ZERO), right(ZERO), height(px(64)), padding(ZERO, px(24)),
            background(HEADER_BG), color(hex(0xf8fafc)), borderBottom(px(1), BorderStyle.SOLID, hex(0x334155)));

    static final StyleClass WORKSPACE = StyleClass.of("workspace", display(Display.FLEX), overflow(Overflow.HIDDEN),
            position(Position.ABSOLUTE), left(ZERO), top(px(64)), right(ZERO), bottom(px(36)));

    static final StyleClass CONTENT = StyleClass.of("content", column(20), flex(1), overflowY(Overflow.AUTO), padding(px(24)));

    static final StyleClass SIDEBAR = StyleClass.of("sidebar", column(16), width(px(230)), flex(NONE),
            padding(px(20), px(16)), background(SURFACE), borderRight(px(1), BorderStyle.SOLID, BORDER));

    static final StyleClass FOOTER = StyleClass.of("site-footer", SPREAD,
            position(Position.ABSOLUTE), left(ZERO), right(ZERO), bottom(ZERO), height(px(36)), padding(ZERO, px(24)),
            fontSize(px(12)), background(FOOTER_BG), borderTop(px(1), BorderStyle.SOLID, BORDER), color(TEXT_MUTED));

    // ---- ヘッダの部品

    static final StyleClass DOT = StyleClass.of("dot", width(px(12)), height(px(12)), borderRadius(px(999)), background(ACCENT));
    static final StyleClass BRAND_TITLE = StyleClass.of("brand-title", fontSize(px(17)), fontWeight(FontWeight.BOLD));
    static final StyleClass BRAND_SUB = StyleClass.of("brand-sub", fontSize(px(11)), color(hex(0x94a3b8)));
    static final StyleClass PILL = StyleClass.of("pill", fontSize(px(12)), padding(px(4), px(10)), borderRadius(px(999)),
            background(hex(0x1e293b)), color(hex(0xcbd5e1)), border(px(1), BorderStyle.SOLID, hex(0x334155)));
    static final StyleClass CLOCK = StyleClass.of("clock", fontSize(px(12)), fontFamily(FontFamily.MONOSPACE),
            color(hex(0xcbd5e1)), padding(ZERO, px(8)));

    // ---- 文字

    static final StyleClass TITLE = StyleClass.of("title", margin(ZERO), fontSize(px(24)));
    static final StyleClass HEADING = StyleClass.of("heading", margin(ZERO), fontSize(px(18)));
    static final StyleClass LEAD = StyleClass.of("lead", margin(ZERO), fontSize(px(14)), color(TEXT_MUTED), lineHeight(1.6));
    static final StyleClass NOTE = StyleClass.of("note", margin(ZERO), fontSize(px(13)), color(TEXT_MUTED));
    static final StyleClass CAPTION = StyleClass.of("caption", fontSize(px(11)), color(TEXT_MUTED));
    static final StyleClass LABEL = StyleClass.of("label", fontSize(px(13)), color(TEXT_MUTED));
    static final StyleClass STRONG = StyleClass.of("strong", fontWeight(FontWeight.BOLD));
    static final StyleClass MONO = StyleClass.of("mono", fontFamily(FontFamily.MONOSPACE));
    static final StyleClass SECTION_LABEL = StyleClass.of("section-label", fontSize(px(11)), fontWeight(FontWeight.BOLD),
            letterSpacing(em(0.08)), color(TEXT_MUTED));

    // ---- 面

    static final StyleClass CARD = StyleClass.of("card", column(12), padding(px(20)), borderRadius(px(14)),
            background(SURFACE), border(px(1), BorderStyle.SOLID, BORDER));
    static final StyleClass DIVIDER = StyleClass.of("divider", height(px(1)), background(BORDER));

    // ---- ボタン

    static final StyleClass BTN = StyleClass.of("btn", padding(px(9), px(16)), border(NONE), borderRadius(px(8)),
            fontWeight(FontWeight.BOLD), cursor(Cursor.POINTER));
    static final StyleClass BTN_PRIMARY = StyleClass.of("btn-primary", BTN, background(ACCENT), color(INK));
    static final StyleClass BTN_GHOST = StyleClass.of("btn-ghost", BTN, background(SURFACE_MUTED), color(hex(0x94a3b8)),
            border(px(1), BorderStyle.SOLID, BORDER));
    /** 入切のボタン（{@code ON} で緑）。 */
    static final StyleClass BTN_TOGGLE = StyleClass.of("btn-toggle", BTN, background(hex(0x64748b)), color(Color.WHITE),
            when(ON, background(SUCCESS)));

    static final StyleClass BTN_SMALL = StyleClass.of("btn-small", padding(px(6), px(10)), border(NONE), borderRadius(px(6)),
            fontSize(px(12)), cursor(Cursor.POINTER), color(Color.WHITE));
    static final StyleClass BTN_DANGER = StyleClass.of("btn-danger", BTN_SMALL, background(hex(0xef4444)));
    static final StyleClass BTN_STATUS = StyleClass.of("btn-status", BTN_SMALL, padding(px(6), px(12)),
            fontWeight(FontWeight.BOLD), background(hex(0x334155)), when(DONE, background(SUCCESS)));

    static final StyleClass NAV_BTN = StyleClass.of("nav-btn", padding(px(8), px(14)), border(NONE), borderRadius(px(8)),
            fontSize(px(13)), fontWeight(FontWeight.BOLD), cursor(Cursor.POINTER),
            background(hex(0x1e293b)), color(hex(0xe2e8f0)),
            when(ACTIVE, background(ACCENT), color(INK)));
    static final StyleClass THEME_BTN = StyleClass.of("theme-btn", padding(px(7), px(12)), borderRadius(px(8)),
            border(px(1), BorderStyle.SOLID, hex(0x475569)), background(INK), color(hex(0xf8fafc)),
            fontSize(px(12)), cursor(Cursor.POINTER));
    static final StyleClass SIDE_LINK = StyleClass.of("side-link", textAlign(TextAlign.LEFT), padding(px(9), px(12)),
            border(NONE), borderRadius(px(8)), fontSize(px(13)), cursor(Cursor.POINTER),
            background(Color.TRANSPARENT), color(LINK_TEXT), fontWeight(FontWeight.NORMAL),
            when(ACTIVE, background(LINK_ACTIVE_BG), color(ACCENT), fontWeight(FontWeight.BOLD)));
    static final StyleClass FILTER_CHIP = StyleClass.of("filter-chip", padding(px(7), px(14)), border(NONE),
            borderRadius(px(999)), fontSize(px(12)), fontWeight(FontWeight.BOLD), cursor(Cursor.POINTER),
            background(SURFACE_MUTED), color(TEXT),
            when(ACTIVE, background(ACCENT), color(INK)));

    // ---- 入力

    static final StyleClass INPUT = StyleClass.of("input", padding(px(9), px(12)), borderRadius(px(8)),
            border(px(1), BorderStyle.SOLID, BORDER), background(SURFACE_MUTED), color(TEXT));
    static final StyleClass SEARCH = StyleClass.of("search", INPUT, width(px(260)));

    // ---- ダッシュボード

    static final StyleClass STAT_ROW = StyleClass.of("stat-row", SPREAD, padding(px(8), px(10)), borderRadius(px(8)),
            background(ROW_BG));
    static final StyleClass STAT_VALUE = StyleClass.of("stat-value", fontSize(px(14)), fontWeight(FontWeight.BOLD));
    static final StyleClass KPI = StyleClass.of("kpi", column(6), flex(1), minWidth(px(190)), padding(px(16)),
            borderRadius(px(12)), background(SURFACE), border(px(1), BorderStyle.SOLID, BORDER));
    static final StyleClass KPI_LABEL = StyleClass.of("kpi-label", fontSize(px(12)), color(TEXT_MUTED));
    static final StyleClass KPI_VALUE = StyleClass.of("kpi-value", fontSize(px(26)), fontWeight(FontWeight.BOLD), color(ACCENT));
    static final StyleClass SIGNAL_LABEL = StyleClass.of("signal-label", LABEL, width(px(92)));
    static final StyleClass TRACK = StyleClass.of("track", flex(1), height(px(12)), borderRadius(px(999)),
            background(SURFACE_MUTED), overflow(Overflow.HIDDEN));
    /** 幅は要素ごと（{@code inline(width(...))}）。 */
    static final StyleClass TRACK_FILL = StyleClass.of("track-fill", height(pct(100)), borderRadius(px(999)), background(ACCENT));
    static final StyleClass SIGNAL_VALUE = StyleClass.of("signal-value", MONO, width(px(44)), textAlign(TextAlign.RIGHT),
            fontSize(px(12)));

    // ---- タスク

    static final StyleClass TASK_ROW = StyleClass.of("task-row", row(12), padding(px(14), px(16)), borderRadius(px(12)),
            background(SURFACE), border(px(1), BorderStyle.SOLID, BORDER));
    static final StyleClass TASK_TITLE = StyleClass.of("task-title", fontSize(px(14)), fontWeight(FontWeight.BOLD), color(TEXT),
            when(DONE, color(TEXT_MUTED)));

    // ---- パレット（見本色は要素ごとに SWATCH で与える）

    static final StyleClass SWATCH_CARD = StyleClass.of("swatch-card", column(10), width(px(220)), padding(px(16)),
            borderRadius(px(12)), cursor(Cursor.POINTER), background(SURFACE), border(px(1), BorderStyle.SOLID, BORDER),
            when(ACTIVE, border(px(2), BorderStyle.SOLID, SWATCH)));
    static final StyleClass SWATCH_PREVIEW = StyleClass.of("swatch-preview", height(px(72)), borderRadius(px(8)), background(SWATCH));
    static final StyleClass SWATCH_NAME = StyleClass.of("swatch-name", fontSize(px(15)), fontWeight(FontWeight.BOLD), color(TEXT));
    static final StyleClass SWATCH_CODE = StyleClass.of("swatch-code", fontSize(px(12)), color(TEXT_MUTED));
}
