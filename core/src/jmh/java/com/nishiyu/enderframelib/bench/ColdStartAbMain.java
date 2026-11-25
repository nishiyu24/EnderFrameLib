package com.nishiyu.enderframelib.bench;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * コールド起動の交互 A/B（Track P1b）。{@link ColdStartMain} を新しい JVM で何度も起動し、構成ごとの中央値を出す。
 *
 * <ul>
 *   <li>{@code jdk21}: JDK 21（エンジンの対象）そのまま</li>
 *   <li>{@code jdk25}: JDK 25 そのまま（JDK を上げただけの差を分ける）</li>
 *   <li>{@code jdk25+aot}: JDK 25 + AOT キャッシュ（JEP 483 / 514。訓練は 1 回、{@code -XX:AOTCacheOutput}）</li>
 * </ul>
 * 構成は 1 回ずつ順に回して 1 巡とし、巡を重ねる（同じセッションの交互の比較。絶対値はセッションでずれるので、比で見る）。
 * AOT キャッシュはクラスパスが jar だけのときしか使えないので、Gradle の {@code :core:coldStartAb} が jar のクラスパスを渡す。
 * キャッシュが使えないときに黙って素の起動になると比較を誤るので、計測は {@code -XX:AOTMode=on}（使えなければ起動しない）で行う。
 *
 * <p>システムプロパティ: {@code ab.java21}・{@code ab.java25}（java の実行ファイル）、{@code ab.cp}（jar のクラスパス）、
 * {@code ab.aot}（キャッシュのファイル）、{@code ab.rounds}（巡の数。既定 5）、{@code ab.doc}（既定 showcase）、
 * {@code ab.fonts}（{@link ColdStartMain} の {@code cold.fonts}。既定 system）、{@code ab.retrain}（true で訓練し直す）、
 * {@code ab.train}（訓練で代表文書を開く回数。{@link ColdStartMain} の {@code cold.train}。既定 0。厚くしても速くならなかった）。
 */
public final class ColdStartAbMain {

    private static final Pattern TOTAL = Pattern.compile("total\\(open\\+first\\)=([0-9.]+)");
    private static final Pattern PROC = Pattern.compile("processToFirstFrame=([0-9]+)");

    private ColdStartAbMain() { }

    record Config(String name, String java, List<String> jvmArgs) {}

    public static void main(String[] args) throws Exception {
        String java21 = require("ab.java21");
        String java25 = require("ab.java25");
        String cp = require("ab.cp");
        Path aot = Path.of(require("ab.aot")).toAbsolutePath();
        int rounds = Integer.getInteger("ab.rounds", 5);
        String doc = System.getProperty("ab.doc", Workloads.SHOWCASE);
        String fonts = System.getProperty("ab.fonts", "system");

        // 書体の目録を作っておく（初回の全走査を計測に混ぜない。P1a）
        run(new Config("prime", java21, List.of()), cp, doc, fonts);

        if (Boolean.getBoolean("ab.retrain") || !Files.isRegularFile(aot)) {
            Files.createDirectories(aot.getParent());
            Files.deleteIfExists(aot);
            long t0 = System.nanoTime();
            int train = Integer.getInteger("ab.train", 0);
            run(new Config("train", java25, List.of("-XX:AOTCacheOutput=" + aot, "-Dcold.train=" + train)), cp, doc, fonts);
            if (!Files.isRegularFile(aot)) throw new IllegalStateException("AOT キャッシュが作られなかった: " + aot);
            System.out.printf("AOT trained %s (%.1f MB) in %.1f s%n", aot, Files.size(aot) / 1e6, (System.nanoTime() - t0) / 1e9);
        }

        List<Config> configs = List.of(
                new Config("jdk21", java21, List.of()),
                new Config("jdk25", java25, List.of()),
                new Config("jdk25+aot", java25, List.of("-XX:AOTCache=" + aot, "-XX:AOTMode=on")));
        Map<String, List<Double>> totals = new LinkedHashMap<>();
        Map<String, List<Double>> procs = new LinkedHashMap<>();
        for (Config c : configs) {
            totals.put(c.name(), new ArrayList<>());
            procs.put(c.name(), new ArrayList<>());
        }
        for (int r = 0; r < rounds; r++) {
            for (Config c : configs) {
                String out = run(c, cp, doc, fonts);
                double total = parse(TOTAL, out, c);
                double proc = parse(PROC, out, c);
                totals.get(c.name()).add(total);
                procs.get(c.name()).add(proc);
                System.out.printf("round %d %-10s open+first=%7.1f ms  process->first frame=%5.0f ms%n", r + 1, c.name(), total, proc);
            }
        }
        double base = median(totals.get("jdk21"));
        double baseProc = median(procs.get("jdk21"));
        for (Config c : configs) {
            double t = median(totals.get(c.name()));
            double p = median(procs.get(c.name()));
            System.out.printf("COLDAB doc=%s fonts=%s %-10s median open+first=%7.1f ms (%.2fx)  process->first frame=%5.0f ms (%.2fx)%n",
                    doc, fonts, c.name(), t, t / base, p, p / baseProc);
        }
    }

    private static String run(Config c, String cp, String doc, String fonts) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(c.java());
        cmd.addAll(c.jvmArgs());
        cmd.add("-Dcold.fonts=" + fonts);
        cmd.add("-Dfile.encoding=UTF-8");
        cmd.add("-Dstdout.encoding=UTF-8");
        cmd.add("-cp");
        cmd.add(cp);
        cmd.add(ColdStartMain.class.getName());
        cmd.add(doc);
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();
        if (code != 0) throw new IllegalStateException(c.name() + " が終了コード " + code + " で終わった:\n" + out);
        return out;
    }

    private static double parse(Pattern pattern, String out, Config c) {
        Matcher m = pattern.matcher(out);
        if (!m.find()) throw new IllegalStateException(c.name() + " の出力に " + pattern + " が無い:\n" + out);
        return Double.parseDouble(m.group(1));
    }

    static double median(List<Double> values) {
        List<Double> s = new ArrayList<>(values);
        s.sort(null);
        int n = s.size();
        return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2;
    }

    private static String require(String key) {
        String v = System.getProperty(key);
        if (v == null || v.isBlank()) throw new IllegalArgumentException("システムプロパティ " + key + " が要る（:core:coldStartAb から起動する）");
        return v;
    }
}
