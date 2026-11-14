package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.paint.RenderBackend;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.raster.Pixmap;
import com.nishiyu.enderframelib.core.text.model.FontResolver;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * エンジンの一度きりの初期化（クラスのロード・静的表・UA スタイルシートの解析・各段のコードの温め）を、
 * 画面を開く前に別スレッドで済ませる。コールド JVM の最初の {@code open} は、処理の大半がこの一度きりの仕事
 * （インタープリタで走る初回の実行）で、showcase で約 450 ms かかる。ゲームの起動時やロード画面の裏で
 * {@link #start()} を呼んでおけば、最初の画面はウォームな状態から開く。
 *
 * <p>代表的な文書（見出し・段落・リスト・flex・grid・フォーム・遷移・スクロール）を小さな表示で数フレーム描くだけで、
 * 状態は共有キャッシュ（スタイルシートの雛形・語の表・フォントの幅）にだけ残り、画面は 1 つも残さない。
 * 失敗しても本体の動作には影響しないので、例外は握りつぶして完了扱いにする。
 */
public final class EnginePrewarm {
    private EnginePrewarm() { }

    private static final Object LOCK = new Object();
    private static CompletableFuture<Void> started;

    /** 温めを別スレッド（デーモン）で始める。2 回目以降は、同じ温めの完了を表す Future を返す。 */
    public static CompletableFuture<Void> start() {
        synchronized (LOCK) {
            if (started == null) {
                CompletableFuture<Void> f = new CompletableFuture<>();
                Thread t = new Thread(() -> {
                    try {
                        runNow();
                    } catch (Throwable ignored) {
                        // 温めは最適化だけ。失敗しても本体は初回に自分で初期化する
                    } finally {
                        f.complete(null);
                    }
                }, "enderframe-prewarm");
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 1);
                started = f;
                t.start();
            }
            return started;
        }
    }

    /** 呼び出したスレッドで温める（テスト・計測用）。 */
    public static void runNow() {
        StandaloneView v = new StandaloneView(HTML, 800, 600, FontResolver.FALLBACK);
        try {
            NullBackend b = new NullBackend();
            long t = 1000;
            v.frame(t, b, true);
            v.mouseMove(60, 40, "prewarm");
            for (int i = 0; i < 4; i++) v.frame(t += 16, b, true);
            v.mouseMove(300, 200, "prewarm");
            for (int i = 0; i < 4; i++) v.frame(t += 16, b, true);
            v.scroll(0, -3, "prewarm");
            for (int i = 0; i < 2; i++) v.frame(t += 16, b, true);
        } finally {
            v.close();
        }
    }

    private static final class NullBackend implements RenderBackend {
        @Override public void render(List<DisplayItem> items, long nowMs) { }
        @Override public Pixmap pixmap() { throw new UnsupportedOperationException(); }
        @Override public float devicePixelRatio() { return 1f; }
    }

    private static final String HTML = """
            <html><head><style>
            body { margin: 8px; font-size: 14px; color: #222; background: #fff; }
            h1 { font-size: 22px; margin: 0 0 8px; } p { margin: 4px 0; line-height: 1.4; }
            .bar { display: flex; gap: 8px; align-items: center; padding: 6px; border: 1px solid #ccc; border-radius: 6px; }
            .bar button { padding: 4px 10px; background: #eef; border: 1px solid #99c; border-radius: 4px; transition: background-color 120ms, transform 120ms; }
            .bar button:hover { background: #cce; transform: translateY(-1px); }
            .grid { display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 6px; margin: 8px 0; }
            .card { padding: 6px; background: #f6f6f6; box-shadow: 0 1px 3px rgba(0,0,0,.2); }
            ul { list-style: none; padding: 0; } li { padding: 3px; border-bottom: 1px solid #ddd; }
            #scroller { height: 120px; overflow-y: auto; }
            </style></head><body>
            <h1>Heading <em>with</em> <strong>inline</strong> text</h1>
            <p>A paragraph of text that wraps over a couple of lines so that line breaking and shaping run, with <a href="#x">a link</a>.</p>
            <div class="bar"><button>One</button><button>Two</button><input type="text" value="field"><select><option>a</option></select></div>
            <div class="grid"><div class="card">A</div><div class="card">B</div><div class="card">C</div></div>
            <div id="scroller"><ul>
            <li>Row 1 with description</li><li>Row 2 with description</li><li>Row 3 with description</li><li>Row 4 with description</li>
            <li>Row 5 with description</li><li>Row 6 with description</li><li>Row 7 with description</li><li>Row 8 with description</li>
            </ul></div>
            </body></html>
            """;
}
