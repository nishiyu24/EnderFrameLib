package com.nishiyu.enderframelib.bench;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 時間に依らない量（1 フレームのヒープ確保と、近道の経路の回数）で退行を判定する回帰ゲート（ENGINE_REARCHITECTURE_PLAN A1）。
 *
 * <p>時間は同じマシンでも ±10〜15% 揺れ、CI の機械ではさらに揺れるので合否に使えない。確保量と経路の回数は、
 * 新しい JVM・ワーカー 1 本・内蔵の簡易書体・描画なし（{@code null} バックエンド。{@code cpu} の行だけソフトウェア描画）で測れば実行ごとにほぼ同じ値になる。
 * シナリオごとに {@link FineProfileMain} を別の JVM で走らせ、その {@code COST} 行を基準値ファイルと比べる:
 * 経路の回数は完全一致、確保量は ±{@code costgate.tolerance}（既定 5%。小さい値には {@value #ALLOC_SLACK_BYTES} B の余裕）。</p>
 *
 * <pre>
 * ./gradlew :core:costGate                        判定（赤なら終了コード 1）
 * ./gradlew :core:costGate -Pcostgate.update      基準値を書き換える（git diff を目で確認してからコミット）
 * ./gradlew :core:costGate -Pcostgate.jvmArgs="-Denderframe.layout.nofitreuse=true"   子 JVM へ引数を足す（ゲートが効くことの確認用）
 * </pre>
 * 確保量が減った場合も赤にする（基準値を更新して、良くなった値を固定するため）。
 *
 * <p><b>記憶の増幅率</b>（C 系の最終目標。計画書 §3 冒頭）: {@code COST} 行の {@code memo*} は、開いた後の 1,200 フレームで再計算した箱の数（style・測定・配置・断片）。
 * シナリオごとの変更元の数（{@link #CHANGE_SOURCES}）で割った 1 フレームあたりの比の最悪値が {@value #AMP_LIMIT} を超えたら赤にする。
 * 既に超えている行は基準値の {@code memoCeil=N} を上限にし、更新は上限を下げる方向にだけ許す（上げるには {@code -Pcostgate.allowAmpRise}）。</p>
 */
public final class CostGateMain {
    private CostGateMain() { }

    static final Path BASELINE = Path.of("core/src/bench-crossengine/costgate-baseline.txt");
    static final long ALLOC_SLACK_BYTES = 256;
    /** 基準値の行ごとの allocBytes の許容（%）。Windows と Linux で JIT の違いから確保量が揺れる行にだけ付ける（経路の回数は完全一致のまま）。 */
    static final String ALLOC_TOL_KEY = "allocTol";
    private static final int FRAMES = 600;
    /** 開く以外のシナリオのウォームアップ（{@code FineProfileMain} と同じ値）。{@code memo*} は FRAMES + これのフレームぶん。 */
    private static final int WARMUP = 600;
    /** 増幅率（変更元 1 つあたりの再計算数 / フレーム）の合否線。 */
    static final double AMP_LIMIT = 10;
    /** 基準値の行ごとの増幅率の上限（{@link #AMP_LIMIT} を超えている既知の行だけに付く）。 */
    static final String AMP_CEIL_KEY = "memoCeil";
    /** シナリオごとの変更元の数。ここに無いシナリオ（全箱が本当に変わる inherited・fontsize・resizeexact・open）は増幅率の対象外。0 は再計算数そのものを見る。 */
    private static final Map<String, Integer> CHANGE_SOURCES = Map.of("idle", 0, "scroll", 0, "hover", 2, "style", 1, "text", 1, "child", 2);
    private static final String[] AMP_KEYS = {"memoStyleRecomputed", "memoMeasureComputed", "memoPlaced", "memoFragMisses"};

    /** 文書・シナリオ［・バックエンド］。バックエンドの既定は null（パイプラインだけ）。cpu 行は名前に {@code " cpu"} が付く。 */
    private static final String[][] RUNS = {
            {"showcase", "idle"}, {"showcase", "hover"}, {"showcase", "text"}, {"showcase", "style"},
            {"showcase", "scroll"}, {"showcase", "inherited"}, {"showcase", "resizeexact"}, {"showcase", "open"},
            {"large-list", "hover"}, {"large-list", "text"}, {"large-list", "style"}, {"large-list", "scroll"},
            {"large-list", "child"}, {"large-list", "fontsize"},
            {"varied-list-500", "inherited"}, {"varied-list-500", "child"}, {"varied-list-500", "fontsize"},
            // ソフトウェア描画（cpu バックエンド。損傷範囲・タイルのラスタ）。確保量は実行ごとに ±0.1% 以内で安定する
            {"showcase", "hover", "cpu"}, {"showcase", "text", "cpu"}, {"showcase", "style", "cpu"},
            {"showcase", "scroll", "cpu"}, {"large-list", "hover", "cpu"},
            // T2: 同梱の実文書（B12 で差分の経路に乗せたもの）。変化の起きない組み合わせ（hover の規則が無い・(50,50) にスクロールする箱が無い）は除く。
            // 描画なし: 経路の回数と確保量。voidwave の 3 行は B12（部分木の組み直し・sticky を含むスクロールの節）、ほかは文字・ホバーの局所パッチ
            {"voidwave.html", "hover"}, {"voidwave.html", "scroll"}, {"voidwave.html", "text"},
            {"surface_test.html", "hover"}, {"surface_test.html", "scroll"}, {"surface_test.html", "text"},
            {"animation_test.html", "hover"}, {"animation_test.html", "text"},
            {"media_test.html", "text"}, {"hud_test.html", "text"}, {"item_block_test.html", "text"},
            {"machine_ui.html", "scroll"}, {"machine_ui.html", "text"}, {"guidebook", "text"},
            // 開く（解析 + 初回フレーム。B13 の確保量）
            {"voidwave.html", "open"}, {"surface_test.html", "open"}, {"animation_test.html", "open"}, {"media_test.html", "open"},
            {"hud_test.html", "open"}, {"item_block_test.html", "open"}, {"machine_ui.html", "open"}, {"guidebook", "open"},
            // ソフトウェア描画（損傷範囲・タイルのラスタ）。voidwave は常時アニメーションで毎フレーム全面（B10 の残り②）なので、時間の掛かる 3 行のうち 1 行だけ
            {"voidwave.html", "hover", "cpu"},
            {"surface_test.html", "hover", "cpu"}, {"surface_test.html", "scroll", "cpu"}, {"surface_test.html", "text", "cpu"},
            {"animation_test.html", "hover", "cpu"}, {"animation_test.html", "text", "cpu"},
            {"media_test.html", "text", "cpu"}, {"hud_test.html", "text", "cpu"}, {"item_block_test.html", "text", "cpu"},
            {"machine_ui.html", "scroll", "cpu"}, {"machine_ui.html", "text", "cpu"}, {"guidebook", "text", "cpu"},
    };

    public static void main(String[] args) throws Exception {
        boolean update = Boolean.getBoolean("costgate.update");
        double tolerance = Double.parseDouble(System.getProperty("costgate.tolerance", "0.05"));
        String extra = System.getProperty("costgate.jvmArgs", "").trim();
        // -Pcostgate.only=voidwave: 名前にこの文字列を含むシナリオだけ回す（手元の確認用。基準値の書き換えとは併用できない）
        String only = System.getProperty("costgate.only", "").trim();
        if (update && !only.isEmpty()) throw new IllegalArgumentException("costgate.only と costgate.update は併用できない（基準値の行が欠ける）");

        // 子 JVM を同時に何本走らせるか（-Pcostgate.parallel=N。既定 1 = 直列）。途中の確認用: 24 コアで 4 本にすると 205 → 78 秒。
        // 経路の回数は直列と全行一致するが、確保量は小さい行で最大 -20% ずれる（同時に走る JVM で JIT の進みが変わる。§6-116）。
        // 合否（push 前・CI）は直列で取る
        int parallel = Math.max(1, Integer.getInteger("costgate.parallel", 1));
        Map<String, Future<Map<String, Long>>> pending = new LinkedHashMap<>();
        long started = System.nanoTime();
        ExecutorService pool = Executors.newFixedThreadPool(parallel);
        for (String[] run : RUNS) {
            String backend = run.length > 2 ? run[2] : "null";
            String key = run[0] + " " + run[1] + (backend.equals("null") ? "" : " " + backend);
            if (!only.isEmpty() && !key.contains(only)) continue;
            pending.put(key, pool.submit(() -> {
                long t0 = System.nanoTime();
                Map<String, Long> m = measure(run[0], run[1], backend, extra);
                System.out.printf("measured %s (%.1f s) %s%n", key, (System.nanoTime() - t0) / 1e9, format(m));
                return m;
            }));
        }
        pool.shutdown();
        Map<String, Map<String, Long>> measured = new LinkedHashMap<>();
        try {
            for (var e : pending.entrySet()) {
                try {
                    measured.put(e.getKey(), e.getValue().get());
                } catch (ExecutionException ex) {
                    throw ex.getCause() instanceof Exception c ? c : ex;
                }
            }
        } finally {
            pool.shutdownNow();
        }
        System.out.printf("COSTGATE %d scenarios in %.0f s (parallel %d)%n", measured.size(), (System.nanoTime() - started) / 1e9, parallel);

        if (update) {
            Map<String, Map<String, Long>> old = readBaseline();
            StringBuilder sb = new StringBuilder();
            sb.append("# CostGateMain の基準値。./gradlew :core:costGate -Pcostgate.update で書き換える（差分を目で確認してからコミット）。\n");
            sb.append("# 条件: 新しい JVM・ワーカー 1 本・FontResolver.FALLBACK・null バックエンド（cpu の行だけソフトウェア描画）・ウォームアップ 600 + 計測 ").append(FRAMES).append(" フレーム。\n");
            sb.append("# allocBytes は ±5%（+").append(ALLOC_SLACK_BYTES).append(" B の余裕）、ほかの項目は完全一致で比べる。\n");
            sb.append("# ").append(ALLOC_TOL_KEY).append("=N の行は allocBytes を ±N% で比べる（OS・JDK の JIT の違いで確保量が揺れる小さな行。経路の回数は同じ）。更新しても保つ。\n");
            sb.append("# memo* は開いた後の再計算数（完全一致）。memoCeil=N の行は記憶の増幅率が合否線 ").append((long) AMP_LIMIT).append(" を超える既知の行で、N を上限にする（下げる方向にだけ更新できる）。\n");
            for (var e : measured.entrySet()) {
                sb.append(e.getKey()).append(" | ").append(format(e.getValue()));
                Long tol = old.getOrDefault(e.getKey(), Map.of()).get(ALLOC_TOL_KEY);
                if (tol != null) sb.append(' ').append(ALLOC_TOL_KEY).append('=').append(tol);
                double amp = worstAmplification(scenarioOf(e.getKey()), e.getValue());
                if (amp > AMP_LIMIT) {
                    long ceil = (long) Math.ceil(amp);
                    Long oldCeil = old.getOrDefault(e.getKey(), Map.of()).get(AMP_CEIL_KEY);
                    if ((oldCeil == null || ceil > oldCeil) && !Boolean.getBoolean("costgate.allowAmpRise")) {
                        throw new IllegalStateException(e.getKey() + ": 増幅率 " + String.format("%.1f", amp) + " が " + (oldCeil == null ? "合否線 " + (long) AMP_LIMIT : "上限 " + oldCeil)
                                + " を超える（上げる更新は -Pcostgate.allowAmpRise で明示する）");
                    }
                    sb.append(' ').append(AMP_CEIL_KEY).append('=').append(oldCeil == null ? ceil : Math.min(oldCeil, ceil));
                }
                sb.append('\n');
            }
            Files.writeString(BASELINE, sb.toString(), StandardCharsets.UTF_8);
            System.out.println("COSTGATE baseline updated: " + BASELINE + " (" + measured.size() + " scenarios)");
            return;
        }

        Map<String, Map<String, Long>> baseline = readBaseline();
        List<String> failures = new ArrayList<>();
        for (var e : measured.entrySet()) {
            Map<String, Long> base = baseline.get(e.getKey());
            if (base == null) {
                failures.add(e.getKey() + ": 基準値が無い（-Pcostgate.update で足す）");
                continue;
            }
            for (var m : e.getValue().entrySet()) {
                Long want = base.get(m.getKey());
                if (want == null) continue; // 後から足した項目は、基準値を更新するまで比べない
                long got = m.getValue();
                if (m.getKey().equals("allocBytes")) {
                    Long rowTol = base.get(ALLOC_TOL_KEY);
                    // 行ごとの許容は既定より広いときだけ効く（CI は既定を広げて渡す。build.yml の -Pcostgate.tolerance）
                    double pct = rowTol != null ? Math.max(rowTol / 100.0, tolerance) : tolerance;
                    double slack = Math.max(want * pct, ALLOC_SLACK_BYTES);
                    if (Math.abs(got - want) > slack) {
                        failures.add(String.format("%s: allocBytes %d → %d（%+.1f%%、許容 ±%.0f B）", e.getKey(), want, got,
                                want == 0 ? 0.0 : 100.0 * (got - want) / want, slack));
                    }
                } else if (got != want) {
                    failures.add(e.getKey() + ": " + m.getKey() + " " + want + " → " + got);
                }
            }
        }
        for (var e : measured.entrySet()) {
            double amp = worstAmplification(scenarioOf(e.getKey()), e.getValue());
            Long ceil = baseline.getOrDefault(e.getKey(), Map.of()).get(AMP_CEIL_KEY);
            double limit = ceil != null ? Math.max(ceil, AMP_LIMIT) : AMP_LIMIT;
            if (amp > limit) failures.add(String.format("%s: 記憶の増幅率 %.1f > %s（変更元あたりの再計算数。計画書 §3 C 系の最終目標）", e.getKey(), amp, ceil != null ? "上限 " + ceil : "合否線 " + (long) AMP_LIMIT));
        }
        if (only.isEmpty()) for (String key : baseline.keySet()) if (!measured.containsKey(key)) failures.add(key + ": 基準値にあるが測っていない");
        if (failures.isEmpty()) {
            System.out.println("COSTGATE OK (" + measured.size() + " scenarios)");
            return;
        }
        System.out.println("COSTGATE FAILED (" + failures.size() + ")");
        for (String f : failures) System.out.println("  " + f);
        // GitHub Actions では、ログを開かなくても（認証なしの annotations API でも）読めるよう、失敗の一覧を 1 つの注記にする
        if ("true".equals(System.getenv("GITHUB_ACTIONS"))) {
            StringBuilder ann = new StringBuilder("::error title=costGate FAILED (" + failures.size() + ")::");
            for (String f : failures) ann.append(f.replace("%", "%25").replace("\r", "").replace("\n", " ")).append("%0A");
            System.out.println(ann);
        }
        System.out.println("意図した変化なら ./gradlew :core:costGate -Pcostgate.update で基準値を更新する。");
        System.exit(1);
    }

    /** 変更元あたりの再計算数（1 フレーム）の最悪値。増幅率の対象外のシナリオは -1。 */
    static double worstAmplification(String scenario, Map<String, Long> m) {
        Integer src = CHANGE_SOURCES.get(scenario);
        if (src == null) return -1;
        double worst = 0;
        for (String k : AMP_KEYS) {
            Long v = m.get(k);
            if (v != null) worst = Math.max(worst, v / ((double) (FRAMES + WARMUP) * Math.max(src, 1)));
        }
        return worst;
    }

    private static String scenarioOf(String key) {
        String[] p = key.split(" ");
        return p.length > 1 ? p[1] : "";
    }

    /** 新しい JVM で 1 シナリオを走らせて COST 行を読む。 */
    private static Map<String, Long> measure(String doc, String scenario, String backend, String extraJvmArgs) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        cmd.add("-Dfile.encoding=UTF-8");
        cmd.add("-Dstdout.encoding=UTF-8");
        cmd.add("-Denderframe.threads=1");
        cmd.add("-Denderframe.kernel.stats=true"); // 記憶の個数（memo*）を数える計器を立てる（K0 ②。既定オフ）
        cmd.add("-Xmx1g");
        if (!extraJvmArgs.isEmpty()) cmd.addAll(List.of(extraJvmArgs.split("\\s+")));
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add(FineProfileMain.class.getName());
        cmd.addAll(List.of(scenario, doc, String.valueOf(FRAMES), backend));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String cost = null;
        StringBuilder tail = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) {
                if (line.startsWith("COST ")) cost = line.substring(5);
                else if (line.contains("Exception") || line.startsWith("\tat ")) tail.append(line).append('\n');
            }
        }
        int exit = p.waitFor();
        if (exit != 0 || cost == null) {
            throw new IllegalStateException("計測に失敗: " + doc + " " + scenario + " (exit " + exit + ")\n" + tail);
        }
        return parse(cost);
    }

    private static Map<String, Long> parse(String fields) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (String f : fields.trim().split("\\s+")) {
            int eq = f.indexOf('=');
            if (eq > 0) out.put(f.substring(0, eq), Long.parseLong(f.substring(eq + 1)));
        }
        return out;
    }

    private static String format(Map<String, Long> m) {
        StringBuilder sb = new StringBuilder();
        for (var e : m.entrySet()) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    private static Map<String, Map<String, Long>> readBaseline() throws IOException {
        Map<String, Map<String, Long>> out = new LinkedHashMap<>();
        if (!Files.exists(BASELINE)) return out;
        for (String line : Files.readAllLines(BASELINE, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            int bar = line.indexOf('|');
            if (bar < 0) continue;
            out.put(line.substring(0, bar).trim(), parse(line.substring(bar + 1)));
        }
        return out;
    }
}
