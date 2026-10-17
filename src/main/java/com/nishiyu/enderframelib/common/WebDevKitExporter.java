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

    /**
     * プレビュー用スタイルシート。
     * 本体は resources/assets/enderframelib/preview/enderframe-devkit.css が単一の出所で、
     * 以前はここに 170 行以上のコピーが埋め込まれていた（focus-visible 宣言の 4 つ目のコピー）。
     */
    private static String buildDevKitCss() {
        return CssResources.load(CssResources.DEV_KIT);
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