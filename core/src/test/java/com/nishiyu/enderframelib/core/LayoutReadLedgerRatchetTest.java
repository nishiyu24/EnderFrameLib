package com.nishiyu.enderframelib.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * 読み取りの台帳（K1: {@code LayoutReads}・{@code ReadKind}）の外での直接読みを 0 に固定するラチェット
 * （計画書 §3 K1・設計書 {@code DESIGN_INCREMENTAL_KERNEL.md} §2 K2・§5 K1）。
 *
 * <p>入口（{@code LayoutReads}・{@code MeasureReads}）を通さずに外の状態（スクロール・包含ブロック・アンカー・
 * 親の外寸・親の幾何）を読む箇所が再び増えると赤になる。「読んだのに記録し忘れた」を構造上起こさないための番。</p>
 */
class LayoutReadLedgerRatchetTest {

    private static final Set<String> LEDGER_OR_MODEL = Set.of(
            "LayoutReads.java",
            "ReadKind.java",
            "MeasureReads.java",
            "LayoutContext.java"
    );

    private static final Pattern DIRECT_SCROLL = Pattern.compile(
            "\\.scrollState\\(\\)|\\bScrollState\\.version\\(|\\.getScroll[XY]\\(");
    private static final Pattern DIRECT_CB = Pattern.compile(
            "(?<!LayoutReads\\.)\\b(?:findContainingBlock|resolveContainingBlock)\\(");
    private static final Pattern DIRECT_ANCHOR = Pattern.compile(
            "(?<!LayoutReads\\.)\\bfindAnchor\\(|\\b(?:a|anchor)\\.(?:x|y|w|h)\\b");
    private static final Pattern DIRECT_PARENT_GEOM = Pattern.compile(
            "\\bparent\\.(?:w|h)\\b|\\bbox\\.parent\\.(?:x|y|w|h|b[LRTB]|p[LRTB])\\b");
    private static final Pattern DIRECT_PARENT_OUTER_PARAM = Pattern.compile(
            "(?<!LayoutReads\\.)\\bparentOuter[WH]\\b");

    private static Path layoutDir() {
        Path p = Path.of("core/src/main/java/com/nishiyu/enderframelib/core/layout");
        return Files.isDirectory(p) ? p : Path.of("src/main/java/com/nishiyu/enderframelib/core/layout");
    }

    private static List<Path> layoutSources() throws IOException {
        return RatchetSupport.javaFiles(layoutDir()).stream()
                .filter(p -> p.getParent().equals(layoutDir()))
                .toList();
    }

    private static String strippedBody(Path p) throws IOException {
        return RatchetSupport.stripCommentsAndStrings(Files.readString(p, StandardCharsets.UTF_8), false);
    }

    private static List<String> findMatches(Pattern pattern, Set<String> skipFiles) throws IOException {
        List<String> hits = new ArrayList<>();
        for (Path p : layoutSources()) {
            String name = p.getFileName().toString();
            if (skipFiles.contains(name)) continue;
            String body = strippedBody(p);
            String[] lines = body.split("\\R", -1);
            for (int i = 0; i < lines.length; i++) {
                Matcher m = pattern.matcher(lines[i]);
                if (m.find()) {
                    hits.add(name + ":" + (i + 1) + ": " + lines[i].trim());
                }
            }
        }
        return hits;
    }

    @Test
    void noDirectScrollReadsOutsideLedger() throws IOException {
        List<String> hits = findMatches(DIRECT_SCROLL, LEDGER_OR_MODEL);
        assertEquals(List.of(), hits,
                "core/layout で LayoutReads を通さず ScrollState を直接読んでいる（K1: 14 → 0）");
    }

    @Test
    void noDirectContainingBlockReadsOutsideLedger() throws IOException {
        List<String> hits = findMatches(DIRECT_CB, LEDGER_OR_MODEL);
        // LayoutBox の公開ラッパー 2 つの宣言行（findContainingBlock / resolveContainingBlock）だけは互換のため残す
        List<String> nonDecl = hits.stream()
                .filter(s -> !s.startsWith("LayoutBox.java:") || (!s.contains("public LayoutBox findContainingBlock()") && !s.contains("public ContainingBlock resolveContainingBlock(")))
                .toList();
        assertEquals(List.of(), nonDecl,
                "core/layout で LayoutReads を通さず包含ブロックを直接求めている（K1: 6 → 0）");
        assertTrue(hits.size() <= 2, "LayoutBox の公開委譲以外の包含ブロック呼び出しが増えた: " + hits);
    }

    @Test
    void noDirectAnchorReadsOutsideLedger() throws IOException {
        List<String> hits = findMatches(DIRECT_ANCHOR, LEDGER_OR_MODEL);
        assertEquals(List.of(), hits,
                "core/layout で LayoutReads を通さずアンカーの検索・幾何を直接読んでいる（K1: 13 → 0）");
    }

    @Test
    void noDirectParentGeometryOrOuterReadsOutsideEntryAndMemoKey() throws IOException {
        List<String> geomHits = findMatches(DIRECT_PARENT_GEOM, LEDGER_OR_MODEL);
        assertEquals(List.of(), geomHits,
                "core/layout で LayoutReads を通さず parent.w/h や box.parent.* の幾何を直接読んでいる");

        // parentOuterW/H は public layout(...) の入口・tryReuseOrShift 以外に持たせない（21 → 入口のみ）
        Set<String> allowedEntry = Set.of("LayoutReads.java", "ReadKind.java", "MeasureReads.java", "LayoutContext.java",
                "LayoutBox.java", "PlacementMemo.java");
        List<String> outerOutsideEntry = findMatches(DIRECT_PARENT_OUTER_PARAM, allowedEntry);
        assertEquals(List.of(), outerOutsideEntry,
                "ScrollExtentCalc 等が LayoutReads を通さず parentOuterW/H を直接受け取っている");

        List<String> allOuter = findMatches(DIRECT_PARENT_OUTER_PARAM, LEDGER_OR_MODEL);
        assertTrue(allOuter.size() <= 10,
                "parentOuterW/H の出現箇所が増えた（K1 で 21 → 10 以下へ縮退済み）: " + allOuter);
    }
}
