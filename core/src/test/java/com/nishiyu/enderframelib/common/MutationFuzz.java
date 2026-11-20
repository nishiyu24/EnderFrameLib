package com.nishiyu.enderframelib.common;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 差分ファズ（ENGINE_REARCHITECTURE_PLAN T3 ③）: 乱数の変更列（{@link MutationScenario}）の毎手で、増分で作った表示リストが同じ時刻で
 * 測定の記憶を全部捨てて（{@code LayoutBox.invalidateAllMeasurements}）全面を組み直した結果と一致するかを比べる。ずれた種は {@code mutation-seeds.txt} に足し、以後は
 * {@link MutationSeedRegressionTest} が毎回の CI で固定の種として回す（見つかった不具合がそのまま回帰テストになる）。
 *
 * <p>検証モード（{@code VERIFY_MEMO}・{@code VERIFY_BOX_MEMO}。{@code mutationFuzz} では既定で有効）も併走させる。記憶が当たった所を計算し直して
 * 比べる検証が赤になった手は、増分と全面の食い違いとは別に「鍵の漏れ[記憶の種類]」として報告する（どの記憶の鍵が漏れたかが分かる。
 * 古い値を持つ箱が測り直しの入口に来ない型〔§6-128〕は、従来どおり増分と全面の食い違いで見つかる）。
 *
 * <p>夜間（{@code full-tests.yml}）は {@code ./gradlew :core:mutationFuzz} で新しい種を回す。種の始まりは日付から決めるので、毎晩違う種になる。
 * <ul>
 *   <li>{@code -Pfuzz.seeds=N}: 文書ごとに回す種の数（既定 24）</li>
 *   <li>{@code -Pfuzz.start=S}: 最初の種（既定は日付から）</li>
 *   <li>{@code -Pfuzz.record}: ずれた種を {@code core/src/test/resources/mutation-seeds.txt} へ直接足す（手元で使う）</li>
 * </ul>
 * ずれた種は {@code core/build/fuzz/new-seeds.txt} にも、表へそのまま貼れる形で書く（CI の成果物）。1 つでもずれたら終了コード 1。</p>
 */
public final class MutationFuzz {
    private MutationFuzz() { }

    /** 変更列を流す文書。 */
    public record Doc(String name, String html, String[] classes, String[] styles, int steps) { }

    public static final Map<String, Doc> DOCS = new LinkedHashMap<>();
    static {
        DOCS.put("layouts", new Doc("layouts", ShortcutMatrixMain.LAYOUTS, ShortcutMatrixMain.LAYOUT_CLASSES, ShortcutMatrixMain.LAYOUT_STYLES, 45));
        DOCS.put("cards", new Doc("cards", ShortcutMatrixMain.CARDS, ShortcutMatrixMain.CARD_CLASSES, ShortcutMatrixMain.CARD_STYLES, 45));
        DOCS.put("nested", new Doc("nested", ShortcutMatrixMain.NESTED, ShortcutMatrixMain.NESTED_CLASSES, ShortcutMatrixMain.NESTED_STYLES, 60));
    }

    /** テスト用: 各手の全面との比較のあとに呼ばれる（記憶を壊して、検証が鍵の漏れとして報告することを確かめる）。 */
    static java.util.function.BiConsumer<Integer, StandaloneView> TAMPER;

    static final Path SEEDS_FILE = Path.of("core/src/test/resources/mutation-seeds.txt");

    private static List<String> sigs(StandaloneView v) {
        List<String> out = new ArrayList<>();
        for (var it : v.items()) out.add(MutationScenario.sig(it));
        return out;
    }

    /**
     * 種 {@code seed} の変更列を {@code steps} 手流し、最初に増分と全面が食い違った手の説明を返す（一致すれば null）。
     * 全面は同じ時刻（{@code StandaloneView.lastNow}）で回す（時間が進むとトランジションが進んで近道と無関係な差が出る。§6-79）。
     */
    public static String firstMismatch(Doc doc, long seed, int steps) {
        String[] found = {null};
        var saved = MutationScenario.STEP_HOOK;
        MutationScenario.STEP_HOOK = (step, view) -> {
            if (found[0] != null) return;
            List<String> live = sigs(view);
            // 全面の再レイアウトは測定の記憶（measure の結果・仮の測定・fit の記録）を使い回すので、増分と同じ古い値を返す形がある
            // （§6-117）。測定の世代を進めて全部測り直させ、記憶に頼らない結果と比べる
            com.nishiyu.enderframelib.core.layout.LayoutBox.invalidateAllMeasurements();
            view.ui().markInvalidation(UiView.InvalidationLevel.RELAYOUT);
            try (var be = new MutationScenario.NoDraw()) {
                var f = StandaloneView.class.getDeclaredField("lastNow");
                f.setAccessible(true);
                view.frame(f.getLong(view), be, true);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
            List<String> full = sigs(view);
            if (!live.equals(full)) {
                String detail = live.size() != full.size() ? "（項目数 " + live.size() + " ≠ " + full.size() + "）" : "";
                for (int i = 0; i < Math.min(live.size(), full.size()); i++) {
                    if (!live.get(i).equals(full.get(i))) { detail = "（" + live.get(i) + " ≠ " + full.get(i) + "）"; break; }
                }
                found[0] = doc.name() + "/" + seed + " 手 " + step + detail;
            }
            if (TAMPER != null) TAMPER.accept(step, view);
        };
        try {
            MutationScenario.run(doc.html(), doc.classes(), doc.styles(), seed, steps, false);
        } catch (AssertionError e) {
            String kind = leakKind(e.getMessage());
            if (kind == null) throw e; // 検証以外の失敗（項目の id の重複など）は従来どおり
            if (found[0] == null) found[0] = keyLeak(doc, seed, MutationScenario.LOG.size() - 1, kind, e.getMessage());
        } finally {
            MutationScenario.STEP_HOOK = saved;
        }
        return found[0];
    }

    /** 検証モードの {@link AssertionError} の文面から、漏れた記憶の種類（検証でなければ null）。 */
    static String leakKind(String message) {
        if (message == null) return null;
        if (message.startsWith("測定の記憶")) return "測定の記憶";
        if (message.startsWith("箱の項目の記憶")) return "箱の項目の記憶";
        if (message.startsWith("項目の再生")) return "項目の再生";
        if (message.startsWith("姿勢の使い回し")) return "姿勢の使い回し";
        if (message.startsWith("float メモ")) return "float メモ";
        return null;
    }

    /** 検証が赤にした手の報告（増分と全面の食い違いと区別できる印 {@code 鍵の漏れ[…]} を付ける）。 */
    static String keyLeak(Doc doc, long seed, int step, String kind, String message) {
        String m = message.length() > 220 ? message.substring(0, 220) + "…" : message;
        return doc.name() + "/" + seed + " 手 " + step + " 鍵の漏れ[" + kind + "]: " + m;
    }

    /** 表の 1 行（文書 | 種 | 手数 | 由来）。由来の中の区切り文字は置き換え、長い説明は切る。 */
    static String seedLine(Doc doc, long seed, String origin) {
        String o = origin.replace('|', '/');
        if (o.length() > 160) o = o.substring(0, 160) + "…";
        return doc.name() + " | " + seed + " | " + doc.steps() + " | " + o;
    }

    /** 表に載っている種（文書名/種）。 */
    static java.util.Set<String> recordedSeeds() throws IOException {
        java.util.Set<String> out = new java.util.HashSet<>();
        if (!Files.exists(SEEDS_FILE)) return out;
        for (String l : Files.readAllLines(SEEDS_FILE, StandardCharsets.UTF_8)) {
            if (l.isBlank() || l.startsWith("#")) continue;
            String[] f = l.split("\\s*\\|\\s*", 3);
            if (f.length >= 2) out.add(f[0].trim() + "/" + f[1].trim());
        }
        return out;
    }

    public static void main(String[] args) throws IOException {
        int seeds = Integer.getInteger("fuzz.seeds", 24);
        long start = Long.getLong("fuzz.start", 1_000_000L + LocalDate.now().toEpochDay() * 1_000L);
        boolean record = Boolean.getBoolean("fuzz.record");
        boolean gha = System.getenv("GITHUB_ACTIONS") != null;
        List<String> found = new ArrayList<>();
        int runs = 0;
        for (Doc doc : DOCS.values()) {
            for (long seed = start; seed < start + seeds; seed++) {
                runs++;
                String bad = firstMismatch(doc, seed, doc.steps());
                if (bad == null) continue;
                System.out.println((bad.contains("鍵の漏れ[") ? "KEYLEAK " : "MISMATCH ") + bad);
                if (gha) System.out.println("::error title=mutationFuzz::" + bad + " → mutation-seeds.txt に「" + seedLine(doc, seed, "夜間ファズ " + LocalDate.now()) + "」を足して直す");
                found.add(seedLine(doc, seed, "夜間ファズ " + LocalDate.now() + "（" + bad + "）"));
            }
        }
        Path out = Path.of("core/build/fuzz/new-seeds.txt");
        Files.createDirectories(out.getParent());
        Files.write(out, found, StandardCharsets.UTF_8);
        if (record && !found.isEmpty()) {
            java.util.Set<String> known = recordedSeeds();
            List<String> fresh = found.stream().filter(l -> {
                String[] f = l.split("\\s*\\|\\s*", 3);
                return !known.contains(f[0] + "/" + f[1]);
            }).toList();
            Files.write(SEEDS_FILE, fresh, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            System.out.println("recorded " + fresh.size() + " new seed(s) into " + SEEDS_FILE + "（表に既にある " + (found.size() - fresh.size()) + " 件は足さない）");
        }
        System.out.println((found.isEmpty() ? "FUZZ OK" : "FUZZ FOUND " + found.size()) + " (" + runs + " runs, seeds " + start + ".." + (start + seeds - 1) + ")");
        System.exit(found.isEmpty() ? 0 : 1);
    }
}
