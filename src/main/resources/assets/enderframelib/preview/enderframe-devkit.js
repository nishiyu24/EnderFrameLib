/**
 * EnderFrameLib Browser Preview & Verification Runtime
 * Emulates W3C Custom Elements (<mc-slot>, <mc-slot-grid>, <mc-inventory>, <mc-bar>),
 * Reactive ReactiveStateStore (data-model, data-bind, data-if, data-for),
 * CommonMark/GFM <article data-markdown>, and F12 DevTools Inspector.
 */
(function () {
    const rawState = {
        overclock: true,
        powerLevel: 120,
        beamColor: '#89b4fa',
        reactorMode: 'balanced',
        // Mod Config Demo defaults
        enableDevToolsShortcut: true,
        defaultViewportScale: 0.94,
        defaultTheme: 'dark',
        accentColor: '#89b4fa',
        enableJeiBridge: true,
        maxUiPacketsPerTick: 16,
        cfgSearch: '',
        // data-for demo list
        modules: [
            { name: 'Flux Capacitor', cost: '120 FE/t', status: 'ONLINE' },
            { name: 'Quantum Stabilizer', cost: '80 FE/t', status: 'SYNCED' },
            { name: 'Coolant Injector', cost: '40 FE/t', status: 'WARM' }
        ]
    };

    const rpcLogs = [];

    const state = new Proxy(rawState, {
        set(target, key, value) {
            target[key] = value;
            syncStateToDom();
            return true;
        }
    });

    function logRpc(action, payload) {
        const time = new Date().toLocaleTimeString();
        const entry = `[${time}] ${action}` + (payload !== undefined && payload !== '' ? ` -> ${payload}` : '');
        rpcLogs.unshift(entry);
        if (rpcLogs.length > 15) rpcLogs.pop();
        const logBox = document.getElementById('rpc_log_output');
        if (logBox) {
            logBox.textContent = rpcLogs.join('\n');
        }
    }

    window.EnderFrame = {
        state,
        logRpc,
        set(k, v) { state[k] = v; },
        get(k) { return state[k]; },
        addModule() {
            const input = document.getElementById('new_module_name');
            const name = (input && input.value.trim()) ? input.value.trim() : ('Module #' + (rawState.modules.length + 1));
            rawState.modules = [...rawState.modules, { name, cost: '60 FE/t', status: 'ONLINE' }];
            if (input) input.value = '';
            syncStateToDom();
            logRpc('state:addModule', name);
        },
        removeLastModule() {
            if (rawState.modules.length > 0) {
                rawState.modules = rawState.modules.slice(0, -1);
                syncStateToDom();
                logRpc('state:removeLastModule');
            }
        },
        resetConfigDefaults() {
            state.enableDevToolsShortcut = true;
            state.defaultViewportScale = 0.94;
            state.defaultTheme = 'dark';
            state.accentColor = '#89b4fa';
            state.enableJeiBridge = true;
            state.maxUiPacketsPerTick = 16;
            syncInputsFromState();
            logRpc('config:reset_all', 'Restored @ConfigEntry defaults');
        }
    };

    // 1. Custom Elements (<mc-slot>, <mc-slot-grid>, <mc-inventory>, <mc-bar>)
    class McSlotElement extends HTMLElement {
        connectedCallback() {
            const idx = this.getAttribute('index') || this.getAttribute('slot') || '0';
            this.classList.add('mc-slot');
            this.dataset.slot = idx;
            if (!this.textContent.trim()) this.textContent = idx;
            if (!this.title) this.title = 'Slot #' + idx;
        }
    }

    class McSlotGridElement extends HTMLElement {
        connectedCallback() {
            const start = parseInt(this.getAttribute('start') || '0', 10);
            const cols = Math.max(1, parseInt(this.getAttribute('cols') || '3', 10));
            const rows = Math.max(1, parseInt(this.getAttribute('rows') || '3', 10));
            const gap = Math.max(0, parseInt(this.getAttribute('gap') || '2', 10));
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
            let html = `<div class="mc-inventory"><div style="display:flex;flex-direction:column;gap:2px;">`;
            for (let r = 0; r < 3; r++) {
                html += `<div class="mc-inventory-row">`;
                for (let c = 0; c < 9; c++) {
                    html += `<div class="mc-slot" data-slot="${idx}" title="Main Inventory Slot #${idx}">${idx++}</div>`;
                }
                html += `</div>`;
            }
            html += `</div><div class="mc-inventory-row">`;
            for (let c = 0; c < 9; c++) {
                html += `<div class="mc-slot" data-slot="${idx}" title="Hotbar Slot #${idx}">${idx++}</div>`;
            }
            html += `</div></div>`;
            this.innerHTML = html;
        }
    }

    class McBarElement extends HTMLElement {
        static get observedAttributes() { return ['value', 'max', 'label', 'color']; }
        connectedCallback() { this.render(); }
        attributeChangedCallback() { this.render(); }
        render() {
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

    // 2. CommonMark / GFM Markdown Renderer (mirrors MarkdownParser.java)
    function parseMarkdownToHtml(md) {
        if (!md) return '';
        const lines = md.replace(/\r\n/g, '\n').split('\n');
        let html = '';
        let i = 0;

        function inlineFmt(str) {
            return str
                .replace(/`([^`]+)`/g, '<code>$1</code>')
                .replace(/!\[([^\]]*)\]\(item:minecraft:([a-z0-9_]+)\)/g, '<span class="badge" title="item:minecraft:$2">💎 $1 ($2)</span>')
                .replace(/\[([^\]]+)\]\(([^)]+)\)/g, '<a href="$2">$1</a>')
                .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
                .replace(/~~(.+?)~~/g, '<del>$1</del>')
                .replace(/(?<!\*)\*(?!\*)(.+?)(?<!\*)\*(?!\*)/g, '<em>$1</em>');
        }

        while (i < lines.length) {
            const raw = lines[i];
            const t = raw.trim();
            if (!t) { i++; continue; }

            if (t.startsWith('```')) {
                const lang = t.slice(3).trim();
                let code = [];
                i++;
                while (i < lines.length && !lines[i].trim().startsWith('```')) {
                    code.push(lines[i].replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;'));
                    i++;
                }
                i++;
                html += `<pre><code class="language-${lang}">${code.join('\n')}</code></pre>`;
                continue;
            }

            const hm = t.match(/^(#{1,6})\s+(.+)$/);
            if (hm) {
                const lvl = hm[1].length;
                html += `<h${lvl} style="margin:0.4em 0;">${inlineFmt(hm[2])}</h${lvl}>`;
                i++;
                continue;
            }

            if (t.startsWith('>')) {
                let qLines = [];
                while (i < lines.length && lines[i].trim().startsWith('>')) {
                    qLines.push(lines[i].trim().replace(/^>\s?/, ''));
                    i++;
                }
                const first = qLines[0] || '';
                const alertM = first.match(/^\[!([A-Za-z]+)\]\s*(.*)$/);
                if (alertM) {
                    const type = alertM[1].toLowerCase();
                    const rest = qLines.slice(1).join(' ');
                    html += `<blockquote class="markdown-alert markdown-alert-${type}"><div class="markdown-alert-title">${alertM[1].toUpperCase()}</div><div>${inlineFmt(rest)}</div></blockquote>`;
                } else {
                    html += `<blockquote>${inlineFmt(qLines.join('<br>'))}</blockquote>`;
                }
                continue;
            }

            if (t.startsWith('|') && i + 1 < lines.length && lines[i + 1].includes('---')) {
                const headers = t.slice(1, -1).split('|').map(s => s.trim());
                i += 2;
                html += '<table><thead><tr>' + headers.map(h => `<th>${inlineFmt(h)}</th>`).join('') + '</tr></thead><tbody>';
                while (i < lines.length && lines[i].trim().startsWith('|')) {
                    const cells = lines[i].trim().slice(1, -1).split('|').map(s => s.trim());
                    html += '<tr>' + cells.map(c => `<td>${inlineFmt(c)}</td>`).join('') + '</tr>';
                    i++;
                }
                html += '</tbody></table>';
                continue;
            }

            if (t.startsWith('- ') || t.startsWith('* ')) {
                html += '<ul style="margin:0.4em 0;padding-left:20px;">';
                while (i < lines.length && (lines[i].trim().startsWith('- ') || lines[i].trim().startsWith('* '))) {
                    const item = lines[i].trim().slice(2).trim();
                    const tm = item.match(/^\[([ xX])\]\s+(.+)$/);
                    if (tm) {
                        const chk = tm[1].toLowerCase() === 'x' ? 'checked' : '';
                        html += `<li style="list-style:none;margin-left:-14px;"><input type="checkbox" disabled ${chk}/> ${inlineFmt(tm[2])}</li>`;
                    } else {
                        html += `<li>${inlineFmt(item)}</li>`;
                    }
                    i++;
                }
                html += '</ul>';
                continue;
            }

            html += `<p style="margin:0.4em 0;">${inlineFmt(t)}</p>`;
            i++;
        }
        return html;
    }

    // 3. Evaluate data-if condition (supports !key, ==, !=, >=, <=, >, <)
    function evaluateCondition(expr) {
        if (!expr) return true;
        let s = expr.trim();
        let neg = false;
        if (s.startsWith('!')) {
            neg = true;
            s = s.slice(1).trim();
        }
        const ops = ['==', '!=', '>=', '<=', '>', '<'];
        for (const op of ops) {
            const idx = s.indexOf(op);
            if (idx > 0) {
                const lhsKey = s.slice(0, idx).trim();
                const rhsRaw = s.slice(idx + op.length).trim().replace(/^['"]|['"]$/g, '');
                const lhsVal = rawState[lhsKey];
                const lNum = parseFloat(lhsVal);
                const rNum = parseFloat(rhsRaw);
                let res = false;
                if (!isNaN(lNum) && !isNaN(rNum)) {
                    if (op === '==') res = lNum === rNum;
                    else if (op === '!=') res = lNum !== rNum;
                    else if (op === '>=') res = lNum >= rNum;
                    else if (op === '<=') res = lNum <= rNum;
                    else if (op === '>') res = lNum > rNum;
                    else if (op === '<') res = lNum < rNum;
                } else {
                    if (op === '==') res = String(lhsVal) === rhsRaw;
                    else if (op === '!=') res = String(lhsVal) !== rhsRaw;
                }
                return neg ? !res : res;
            }
        }
        const val = rawState[s];
        const truthy = Boolean(val) && val !== 'false' && val !== '0';
        return neg ? !truthy : truthy;
    }

    // 4. Synchronize state to DOM (data-bind, data-if, data-for, meter)
    const forTemplates = new Map();

    function syncStateToDom() {
        document.querySelectorAll('[data-bind]').forEach(el => {
            const spec = el.getAttribute('data-bind').trim();
            const key = spec.includes(':') ? spec.split(':')[1].trim() : spec;
            if (key in rawState) {
                el.textContent = rawState[key];
            }
        });

        document.querySelectorAll('[data-if]').forEach(el => {
            const expr = el.getAttribute('data-if');
            el.hidden = !evaluateCondition(expr);
        });

        const meter = document.getElementById('core_load_meter');
        if (meter) meter.value = rawState.powerLevel;

        const bar = document.getElementById('live_power_bar');
        if (bar) {
            bar.setAttribute('value', rawState.powerLevel);
            bar.setAttribute('label', rawState.powerLevel + ' / 200 W');
            bar.setAttribute('color', rawState.beamColor || '#89b4fa');
        }

        // Render data-for lists
        document.querySelectorAll('[data-for-container]').forEach(container => {
            const expr = container.getAttribute('data-for-container');
            const [itemVar, listKey] = expr.split(/\s+in\s+/).map(s => s.trim());
            const list = rawState[listKey] || [];
            const tpl = forTemplates.get(container) || '';
            container.innerHTML = list.map((item, idx) => {
                return tpl
                    .replaceAll(`{{${itemVar}.name}}`, item.name)
                    .replaceAll(`{{${itemVar}.cost}}`, item.cost)
                    .replaceAll(`{{${itemVar}.status}}`, item.status)
                    .replaceAll(`{{index}}`, idx + 1);
            }).join('');
        });

        // Filter Config fields if cfgSearch is active
        const q = (rawState.cfgSearch || '').toLowerCase().trim();
        document.querySelectorAll('[data-config-row]').forEach(row => {
            const text = row.getAttribute('data-config-row').toLowerCase();
            row.hidden = q.length > 0 && !text.includes(q);
        });
    }

    function syncInputsFromState() {
        document.querySelectorAll('[data-model]').forEach(el => {
            const key = el.getAttribute('data-model').trim();
            if (key in rawState) {
                if (el.type === 'checkbox') el.checked = Boolean(rawState[key]);
                else el.value = rawState[key];
            }
        });
    }

    window.addEventListener('DOMContentLoaded', () => {
        // Store data-for templates
        document.querySelectorAll('[data-for-container]').forEach(container => {
            forTemplates.set(container, container.innerHTML);
        });

        // Render initial <article data-markdown> blocks
        document.querySelectorAll('[data-markdown]').forEach(el => {
            const raw = el.textContent.replace(/^\n+|\n+$/g, '');
            const lines = raw.split('\n');
            const minIndent = lines.filter(l => l.trim()).reduce((min, l) => Math.min(min, l.match(/^\s*/)[0].length), 99);
            const dedented = lines.map(l => l.slice(minIndent)).join('\n');
            el.innerHTML = parseMarkdownToHtml(dedented);
        });

        // Live Markdown Editor in Tab 3
        const mdEditor = document.getElementById('live_md_input');
        const mdPreview = document.getElementById('live_md_preview');
        if (mdEditor && mdPreview) {
            const updateMd = () => { mdPreview.innerHTML = parseMarkdownToHtml(mdEditor.value); };
            mdEditor.addEventListener('input', updateMd);
            updateMd();
        }

        // Bind [data-model] inputs
        document.querySelectorAll('[data-model]').forEach(el => {
            const key = el.getAttribute('data-model').trim();
            if (key in rawState) {
                if (el.type === 'checkbox') el.checked = Boolean(rawState[key]);
                else el.value = rawState[key];
            }
            el.addEventListener('input', () => {
                state[key] = (el.type === 'checkbox') ? el.checked : el.value;
                if (key === 'defaultTheme') {
                    document.documentElement.dataset.theme = el.value;
                    const topSel = document.getElementById('top_theme_select');
                    if (topSel) topSel.value = el.value;
                }
            });
        });

        // Intercept server: RPC buttons for live logging
        document.addEventListener('click', e => {
            const btn = e.target.closest('[data-rpc]');
            if (btn) {
                logRpc(btn.getAttribute('data-rpc'), JSON.stringify({
                    overclock: rawState.overclock,
                    powerLevel: rawState.powerLevel,
                    reactorMode: rawState.reactorMode
                }));
            }
        });

        // F12 DevTools Inspector emulation inside browser
        const inspectorOverlay = document.getElementById('devtools_inspector_badge');
        let devToolsOn = false;
        window.toggleDevToolsOverlay = function () {
            devToolsOn = !devToolsOn;
            document.body.classList.toggle('devtools-active', devToolsOn);
            if (inspectorOverlay) inspectorOverlay.hidden = !devToolsOn;
            logRpc('devtools:toggle', devToolsOn ? 'ENABLED (Hover any element)' : 'DISABLED');
        };

        window.addEventListener('keydown', e => {
            if (e.key === 'F12') {
                // Allow toggling in-page inspector with Alt+F12 or F12 button
            }
        });

        document.addEventListener('mousemove', e => {
            if (!devToolsOn || !inspectorOverlay) return;
            const el = e.target;
            const rect = el.getBoundingClientRect();
            const tag = el.tagName ? el.tagName.toLowerCase() : 'node';
            const id = el.id ? '#' + el.id : '';
            const cls = (el.className && typeof el.className === 'string') ? '.' + el.className.trim().replace(/\s+/g, '.') : '';
            inspectorOverlay.textContent = `[F12 Inspector] <${tag}>${id}${cls} | ${Math.round(rect.width)}x${Math.round(rect.height)} at (${Math.round(rect.left)}, ${Math.round(rect.top)})`;
        });

        syncStateToDom();
        logRpc('EnderFrameLib', 'Initialized Phase 1–3 Web Standard Test Suite');
    });
})();