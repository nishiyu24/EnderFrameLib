package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.EnderFrameLib;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Web開発者向け「ブラウザプレビュー＆Web標準DevKit」エクスポーター。
 *
 * Chrome / Edge / Firefox および VS Code Live Server 上で、Minecraft を起動することなく
 * 標準 HTML5 / CSS3 と EnderFrameLib の拡張カスタム要素 (<mc-slot>, <mc-inventory>, <mc-bar>)、
 * リアクティブ属性 (data-model, data-bind, data-if, data-for) を完全再現する
 * スタンドアロン開発キットを出力する。
 */
public final class WebDevKitExporter {

    private WebDevKitExporter() {}

    /**
     * 指定ディレクトリ（通常は <gameDir>/enderframe_devkit）へ DevKit 一式を出力する。
     */
    public static Path exportDevKit(Path baseDir) throws IOException {
        Path outDir = baseDir.resolve("enderframe_devkit");
        Files.createDirectories(outDir);

        Files.writeString(outDir.resolve("enderframe-devkit.css"), buildDevKitCss(), StandardCharsets.UTF_8);
        Files.writeString(outDir.resolve("enderframe-devkit.js"), buildDevKitJs(), StandardCharsets.UTF_8);
        Files.writeString(outDir.resolve("preview-playground.html"), buildPlaygroundHtml(), StandardCharsets.UTF_8);

        EnderFrameLib.LOGGER.info("[EnderFrameLib] Exported Web Developer Kit to: {}", outDir.toAbsolutePath());
        return outDir;
    }

    private static String buildDevKitCss() {
        return """
                /* =========================================================================
                 * EnderFrameLib Web Standard Developer Kit Stylesheet (W3C HTML5 / CSS3)
                 * Link this file in your HTML to preview UIs directly in Chrome/Edge/Firefox
                 * ========================================================================= */
                :root, [data-theme="dark"] {
                    --bg-base: #1e1e2e;
                    --bg-surface: #313244;
                    --bg-elevated: #45475a;
                    --text-main: #cdd6f4;
                    --text-sub: #a6adc8;
                    --accent: #89b4fa;
                    --success: #a6e3a1;
                    --danger: #f38ba8;
                    --warning: #f9e2af;
                    font-family: 'Segoe UI', system-ui, -apple-system, sans-serif;
                    font-size: 14px;
                }
                [data-theme="vanilla"] {
                    --bg-base: #c6c6c6;
                    --bg-surface: #8b8b8b;
                    --bg-elevated: #a0a0a0;
                    --text-main: #3f3f3f;
                    --text-sub: #555555;
                    --accent: #5555ff;
                }
                [data-theme="brass"] {
                    --bg-base: #3b2d22;
                    --bg-surface: #261c15;
                    --bg-elevated: #5c4328;
                    --text-main: #f5e0dc;
                    --text-sub: #d4af37;
                    --accent: #e5c890;
                }
                * { box-sizing: border-box; }
                body, html {
                    background-color: #181825;
                    color: var(--text-main);
                    margin: 0;
                    padding: 0;
                    width: 100%;
                    min-height: 100%;
                }
                *[hidden] { display: none !important; }
                .mc-panel {
                    background-color: var(--bg-base);
                    border: 1px solid var(--bg-elevated);
                    border-radius: 6px;
                    padding: 12px;
                    box-shadow: 0 4px 8px rgba(0,0,0,0.4);
                }
                .mc-card {
                    background-color: var(--bg-surface);
                    border: 1px solid var(--bg-elevated);
                    border-radius: 4px;
                    padding: 8px;
                }
                .mc-title {
                    color: var(--accent);
                    font-weight: bold;
                    font-size: 1.1rem;
                    margin-bottom: 6px;
                }
                .mc-btn, button {
                    background-color: var(--bg-surface);
                    color: var(--text-main);
                    border: 1px solid #585b70;
                    border-radius: 4px;
                    padding: 4px 10px;
                    cursor: pointer;
                    transition: 0.12s;
                }
                .mc-btn:hover, button:hover {
                    background-color: var(--bg-elevated);
                    color: #ffffff;
                }
                .mc-btn:active, button:active {
                    transform: scale(0.96);
                }
                .mc-btn-primary {
                    background-color: var(--accent);
                    color: #11111b;
                    border: 1px solid #b4befe;
                    font-weight: bold;
                }
                .mc-slot {
                    width: 18px;
                    height: 18px;
                    background-color: #11111b;
                    border: 1px solid #45475a;
                    border-radius: 2px;
                    display: inline-flex;
                    align-items: center;
                    justify-content: center;
                    font-size: 9px;
                    color: #6c7086;
                    user-select: none;
                    transition: 0.1s;
                }
                .mc-slot:hover {
                    background-color: #313244;
                    border-color: var(--accent);
                }
                .mc-inventory {
                    display: flex;
                    flex-direction: column;
                    gap: 4px;
                    width: 162px;
                }
                .mc-inventory-row {
                    display: flex;
                    flex-direction: row;
                    gap: 0px;
                }
                .mc-bar {
                    position: relative;
                    display: inline-block;
                    width: 140px;
                    height: 14px;
                    background-color: #181825;
                    border: 1px solid #45475a;
                    border-radius: 4px;
                    overflow: hidden;
                }
                fieldset {
                    border: 1px solid #585b70;
                    border-radius: 4px;
                    padding: 8px 12px;
                    margin: 4px 0;
                }
                legend {
                    font-weight: bold;
                    padding: 0 4px;
                    color: var(--accent);
                }
                details > summary {
                    cursor: pointer;
                    font-weight: bold;
                    user-select: none;
                }
                input, select, textarea {
                    background-color: #181825;
                    color: var(--text-main);
                    border: 1px solid #585b70;
                    border-radius: 4px;
                    padding: 4px 6px;
                }
                button:focus-visible, input:focus-visible, select:focus-visible, textarea:focus-visible, summary:focus-visible, a:focus-visible {
                    outline: 2px solid var(--accent);
                    outline-offset: 1px;
                }
                blockquote {
                    border-left: 3px solid var(--accent);
                    background-color: rgba(137, 180, 250, 0.08);
                    padding: 6px 10px;
                    margin: 6px 0;
                    border-radius: 2px;
                }
                table {
                    width: 100%;
                    border-collapse: collapse;
                    border: 1px solid #45475a;
                    margin: 6px 0;
                }
                th, td {
                    padding: 4px 8px;
                    border-bottom: 1px solid #313244;
                }
                th {
                    background-color: rgba(137, 180, 250, 0.12);
                    color: var(--accent);
                    text-align: left;
                }
                """;
    }

    private static String buildDevKitJs() {
        return """
                /**
                 * EnderFrameLib Browser Preview Runtime (W3C Web Components + Reactive State Store)
                 */
                (function () {
                    const state = new Proxy({}, {
                        set(target, key, value) {
                            target[key] = value;
                            syncStateToDom();
                            return true;
                        }
                    });

                    window.EnderFrame = {
                        state,
                        set(key, val) { state[key] = val; },
                        get(key) { return state[key]; }
                    };

                    // 1. W3C Custom Elements (<mc-slot>, <mc-slot-grid>, <mc-inventory>, <mc-bar>)
                    class McSlotElement extends HTMLElement {
                        connectedCallback() {
                            const idx = this.getAttribute('index') || this.getAttribute('slot') || '0';
                            this.classList.add('mc-slot');
                            this.dataset.slot = idx;
                            if (!this.title) this.title = 'Slot #' + idx;
                        }
                    }
                    class McSlotGridElement extends HTMLElement {
                        connectedCallback() {
                            const start = parseInt(this.getAttribute('start') || '0', 10);
                            const cols = Math.max(1, parseInt(this.getAttribute('cols') || '3', 10));
                            const rows = Math.max(1, parseInt(this.getAttribute('rows') || '3', 10));
                            const gap = Math.max(0, parseInt(this.getAttribute('gap') || '0', 10));
                            let idx = start;
                            let html = `<div style="display:flex;flex-direction:column;gap:${gap}px;">`;
                            for (let r = 0; r < rows; r++) {
                                html += `<div style="display:flex;flex-direction:row;gap:${gap}px;">`;
                                for (let c = 0; c < cols; c++) {
                                    html += `<div class="mc-slot" data-slot="${idx}" title="Slot #${idx}">${idx++}</div>`;
                                }
                                html += `</div>`;
                            }
                            html += `</div>`;
                            this.innerHTML = html;
                        }
                    }
                    class McInventoryElement extends HTMLElement {
                        connectedCallback() {
                            const start = parseInt(this.getAttribute('start') || '0', 10);
                            let idx = start;
                            let html = `<div class="mc-inventory"><div style="display:flex;flex-direction:column;">`;
                            for (let r = 0; r < 3; r++) {
                                html += `<div class="mc-inventory-row">`;
                                for (let c = 0; c < 9; c++) {
                                    html += `<div class="mc-slot" data-slot="${idx}" title="Slot #${idx}"></div>`;
                                    idx++;
                                }
                                html += `</div>`;
                            }
                            html += `</div><div class="mc-inventory-row">`;
                            for (let c = 0; c < 9; c++) {
                                html += `<div class="mc-slot" data-slot="${idx}" title="Hotbar #${idx}"></div>`;
                                idx++;
                            }
                            html += `</div></div>`;
                            this.innerHTML = html;
                        }
                    }
                    class McBarElement extends HTMLElement {
                        connectedCallback() {
                            const val = parseFloat(this.getAttribute('value') || '0');
                            const max = Math.max(1, parseFloat(this.getAttribute('max') || '100'));
                            const color = this.getAttribute('color') || '#89b4fa';
                            const bg = this.getAttribute('bg') || '#181825';
                            const label = this.getAttribute('label') || '';
                            const pct = Math.min(100, Math.max(0, (val / max) * 100));
                            this.classList.add('mc-bar');
                            this.style.backgroundColor = bg;
                            this.innerHTML = `
                                <div style="width:${pct}%;height:100%;background:${color};transition:width 0.15s;"></div>
                                <span style="position:absolute;inset:0;display:flex;align-items:center;justify-content:center;color:#fff;font-size:11px;text-shadow:1px 1px 0 #000;">${label}</span>
                            `;
                        }
                    }

                    if (!customElements.get('mc-slot')) customElements.define('mc-slot', McSlotElement);
                    if (!customElements.get('mc-slot-grid')) customElements.define('mc-slot-grid', McSlotGridElement);
                    if (!customElements.get('mc-inventory')) customElements.define('mc-inventory', McInventoryElement);
                    if (!customElements.get('mc-bar')) customElements.define('mc-bar', McBarElement);

                    // 2. Two-way data-model & data-bind & data-if synchronization
                    function initDataModels() {
                        document.querySelectorAll('[data-model]').forEach(el => {
                            const key = el.getAttribute('data-model').trim();
                            if (!(key in state)) {
                                if (el.type === 'checkbox') state[key] = el.checked;
                                else state[key] = el.value || '';
                            }
                            el.addEventListener('input', () => {
                                state[key] = (el.type === 'checkbox') ? el.checked : el.value;
                            });
                        });
                    }

                    function syncStateToDom() {
                        document.querySelectorAll('[data-bind]').forEach(el => {
                            const key = el.getAttribute('data-bind').trim();
                            if (key in state) el.textContent = state[key];
                        });
                        document.querySelectorAll('[data-if]').forEach(el => {
                            const expr = el.getAttribute('data-if').trim();
                            const neg = expr.startsWith('!');
                            const key = neg ? expr.slice(1).trim() : expr;
                            const truthy = Boolean(state[key]) && state[key] !== 'false' && state[key] !== '0';
                            el.hidden = neg ? truthy : !truthy;
                        });
                    }

                    window.addEventListener('DOMContentLoaded', () => {
                        initDataModels();
                        syncStateToDom();
                    });
                })();
                """;
    }

    private static String buildPlaygroundHtml() {
        return """
                <!DOCTYPE html>
                <html lang="ja" data-theme="dark">
                <head>
                    <meta charset="UTF-8" />
                    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
                    <title>EnderFrameLib - Web Standard UI Playground</title>
                    <link rel="stylesheet" href="enderframe-devkit.css" />
                    <script src="enderframe-devkit.js" defer></script>
                </head>
                <body style="padding: 20px; display: flex; flex-direction: column; gap: 14px; max-width: 854px; margin: 0 auto;">
                    <header class="mc-panel" style="display: flex; justify-content: space-between; align-items: center;">
                        <div>
                            <div class="mc-title" style="margin: 0;">EnderFrameLib Browser DevKit</div>
                            <small style="color: var(--text-sub);">W3C HTML5 / CSS3 Standard UI Authoring Preview</small>
                        </div>
                        <div style="display: flex; gap: 8px; align-items: center;">
                            <label for="theme_sel">Theme:</label>
                            <select id="theme_sel" onchange="document.documentElement.dataset.theme = this.value">
                                <option value="dark">Dark (Catppuccin)</option>
                                <option value="vanilla">Minecraft Vanilla</option>
                                <option value="brass">Create Brass</option>
                            </select>
                        </div>
                    </header>

                    <main style="display: flex; gap: 14px; align-items: flex-start;">
                        <section class="mc-panel" style="flex: 1; display: flex; flex-direction: column; gap: 10px;">
                            <fieldset>
                                <legend>Standard HTML5 Reactive Form</legend>
                                <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px;">
                                    <label for="power_toggle">Machine Active</label>
                                    <input id="power_toggle" type="checkbox" data-model="machineActive" checked />
                                </div>
                                <div style="display: flex; justify-content: space-between; align-items: center;" data-if="machineActive">
                                    <label for="speed_range">Processing Speed (<output data-bind="speed">100</output>%)</label>
                                    <input id="speed_range" type="range" min="0" max="200" value="100" data-model="speed" />
                                </div>
                            </fieldset>

                            <details open class="mc-card">
                                <summary>Machine Inventory &amp; Slots</summary>
                                <div style="display: flex; gap: 16px; align-items: center; margin-top: 8px;">
                                    <mc-slot-grid start="0" cols="3" rows="3" gap="2"></mc-slot-grid>
                                    <mc-bar value="72" max="100" label="72%"></mc-bar>
                                    <mc-slot index="9"></mc-slot>
                                </div>
                                <div style="margin-top: 10px;">
                                    <mc-inventory start="10"></mc-inventory>
                                </div>
                            </details>
                        </section>
                    </main>
                </body>
                </html>
                """;
    }
}