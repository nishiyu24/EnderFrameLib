package com.nishiyu.enderframelib.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 差分ファズ（{@link MutationFuzz}）で見つかった種を、毎回の CI で固定の種として回す（ENGINE_REARCHITECTURE_PLAN T3 ③）。
 * 表は {@code mutation-seeds.txt}。夜間の {@code mutationFuzz} がずれた種を見つけたら、その行を表へ足して同じコミットで直す。
 */
class MutationSeedRegressionTest {

    record Seed(MutationFuzz.Doc doc, long seed, int steps, String origin) {
        boolean known() { return origin.startsWith("既知:"); }
    }

    static List<Seed> load() throws IOException {
        List<Seed> out = new ArrayList<>();
        try (InputStream in = MutationSeedRegressionTest.class.getResourceAsStream("/mutation-seeds.txt")) {
            assertNotNull(in, "mutation-seeds.txt が無い");
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] f = line.split("\\s*\\|\\s*", 4);
                assertEquals(4, f.length, "形式は「文書 | 種 | 手数 | 由来」: " + line);
                MutationFuzz.Doc doc = MutationFuzz.DOCS.get(f[0].trim());
                assertNotNull(doc, "知らない文書: " + line);
                assertFalse(f[3].isBlank(), "由来が空: " + line);
                out.add(new Seed(doc, Long.parseLong(f[1].trim()), Integer.parseInt(f[2].trim()), f[3].trim()));
            }
        }
        return out;
    }

    @Test
    void tableIsWellFormedAndHasNoDuplicates() throws IOException {
        List<Seed> seeds = load();
        assertTrue(seeds.size() >= 5, "表が読めている: " + seeds.size());
        Set<String> seen = new HashSet<>();
        for (Seed s : seeds) assertTrue(seen.add(s.doc().name() + "/" + s.seed()), "重複: " + s);
    }

    @Test
    void everyRecordedSeedMatchesTheFullRelayout() throws IOException {
        List<String> bad = new ArrayList<>();
        for (Seed s : load()) {
            if (s.known()) continue;
            String m = MutationFuzz.firstMismatch(s.doc(), s.seed(), s.steps());
            if (m != null) bad.add(m + " ← " + s.origin());
        }
        assertEquals(List.of(), bad);
    }

    /** 「既知:」の行（すぐ直せずに残した不具合）は、まだずれること。直って一致したら赤（表の「既知:」を外して固定の種にする）。 */
    @Test
    void knownSeedsStillMismatchUntilFixed() throws IOException {
        List<String> fixed = new ArrayList<>();
        for (Seed s : load()) {
            if (s.known() && MutationFuzz.firstMismatch(s.doc(), s.seed(), s.steps()) == null) fixed.add(s.doc().name() + "/" + s.seed() + " は一致した（「既知:」を外す）");
        }
        assertEquals(List.of(), fixed);
    }
}
