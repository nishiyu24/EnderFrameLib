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

/**
 * 時間に依らない量（1 フレームのヒープ確保と、近道の経路の回数）で退行を判定する回帰ゲート（ENGINE_REARCHITECTURE_PLAN A1）。
 *
 * <p>時間は同じマシンでも ±10〜15% 揺れ、CI の機械ではさらに揺れるので合否に使えない。確保量と経路の回数は、
 * 新しい JVM・ワーカー 1 本・内蔵の簡易書体・描画なし（{@code null} バックエンド）で測れば実行ごとにほぼ同じ値になる。
 * シナリオごとに {@link FineProfileMain} を別の JVM で走らせ、その {@code COST} 行を基準値ファイルと比べる:
 * 経路の回数は完全一致、確保量は ±{@code costgate.tolerance}（既定 5%。小さい値には {@value #ALLOC_SLACK_BYTES} B の余裕）。</p>
 *
 * <pre>
 * ./gradlew :core:costGate                        判定（赤なら終了コード 1）
 * ./gradlew :core:costGate -Pcostgate.update      基準値を書き換える（git diff を目で確認してからコミット）
 * ./gradlew :core:costGate -Pcostgate.jvmArgs="-Denderframe.layout.nofitreuse=true"   子 JVM へ引数を足す（ゲートが効くことの確認用）
 * </pre>
 * 確保量が減った場合も赤にする（基準値を更新して、良くなった値を固定するため）。
 */
public final class CostGateMain {
    private CostGateMain() { }

    static final Path BASELINE = Path.of("core/src/bench-crossengine/costgate-baseline.txt");
    static final long ALLOC_SLACK_BYTES = 256;
    private static final int FRAMES = 600;

    /** 文書・シナリオ（バックエンドは常に null＝パイプラインだけ）。 */
    private static final String[][] RUNS = {
            {"showcase", "idle"}, {"showcase", "hover"}, {"showcase", "text"}, {"showcase", "style"},
            {"showcase", "scroll"}, {"showcase", "inherited"}, {"showcase", "resizeexact"}, {"showcase", "open"},
            {"large-list", "hover"}, {"large-list", "text"}, {"large-list", "style"}, {"large-list", "scroll"},
            {"large-list", "child"}, {"large-list", "fontsize"},
            {"varied-list-500", "inherited"}, {"varied-list-500", "child"}, {"varied-list-500", "fontsize"},
    };

    public static void main(String[] args) throws Exception {
        boolean update = Boolean.getBoolean("costgate.update");
        double tolerance = Double.parseDouble(System.getProperty("costgate.tolerance", "0.05"));
        String extra = System.getProperty("costgate.jvmArgs", "").trim();

        Map<String, Map<String, Long>> measured = new LinkedHashMap<>();
        for (String[] run : RUNS) {
            String key = run[0] + " " + run[1];
            measured.put(key, measure(run[0], run[1], extra));
            System.out.println("measured " + key + " " + format(measured.get(key)));
        }

        if (update) {
            StringBuilder sb = new StringBuilder();
            sb.append("# CostGateMain の基準値。./gradlew :core:costGate -Pcostgate.update で書き換える（差分を目で確認してからコミット）。\n");
            sb.append("# 条件: 新しい JVM・ワーカー 1 本・FontResolver.FALLBACK・null バックエンド・ウォームアップ 600 + 計測 ").append(FRAMES).append(" フレーム。\n");
            sb.append("# allocBytes は ±5%（+").append(ALLOC_SLACK_BYTES).append(" B の余裕）、ほかの項目は完全一致で比べる。\n");
            for (var e : measured.entrySet()) sb.append(e.getKey()).append(" | ").append(format(e.getValue())).append('\n');
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
                    double slack = Math.max(want * tolerance, ALLOC_SLACK_BYTES);
                    if (Math.abs(got - want) > slack) {
                        failures.add(String.format("%s: allocBytes %d → %d（%+.1f%%、許容 ±%.0f B）", e.getKey(), want, got,
                                want == 0 ? 0.0 : 100.0 * (got - want) / want, slack));
                    }
                } else if (got != want) {
                    failures.add(e.getKey() + ": " + m.getKey() + " " + want + " → " + got);
                }
            }
        }
        for (String key : baseline.keySet()) if (!measured.containsKey(key)) failures.add(key + ": 基準値にあるが測っていない");
        if (failures.isEmpty()) {
            System.out.println("COSTGATE OK (" + measured.size() + " scenarios)");
            return;
        }
        System.out.println("COSTGATE FAILED (" + failures.size() + ")");
        for (String f : failures) System.out.println("  " + f);
        System.out.println("意図した変化なら ./gradlew :core:costGate -Pcostgate.update で基準値を更新する。");
        System.exit(1);
    }

    /** 新しい JVM で 1 シナリオを走らせて COST 行を読む。 */
    private static Map<String, Long> measure(String doc, String scenario, String extraJvmArgs) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        cmd.add("-Dfile.encoding=UTF-8");
        cmd.add("-Dstdout.encoding=UTF-8");
        cmd.add("-Denderframe.threads=1");
        cmd.add("-Xmx1g");
        if (!extraJvmArgs.isEmpty()) cmd.addAll(List.of(extraJvmArgs.split("\\s+")));
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add(FineProfileMain.class.getName());
        cmd.addAll(List.of(scenario, doc, String.valueOf(FRAMES), "null"));
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
