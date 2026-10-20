package com.nishiyu.enderframelib.wpt;

import com.nishiyu.enderframelib.wpt.WptHarness.DivergenceOutcome;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 既知差分の台帳（known-divergences.txt）の判定ロジックのテスト。
 *
 * <p>台帳そのものは空でもよいが、判定が「無い失敗は回帰」「通ったのに載っているのは古い台帳」に
 * なっていることを固定しておく。ここが壊れると、台帳が回帰の番人として機能しなくなる。
 */
public class WptHarnessTest {

    @Test
    public void testClassify() {
        assertEquals(DivergenceOutcome.PASSED, WptHarness.classify(false, false));
        assertEquals(DivergenceOutcome.UNEXPECTED_FAILURE, WptHarness.classify(true, false));
        assertEquals(DivergenceOutcome.EXPECTED_FAILURE, WptHarness.classify(true, true));
        assertEquals(DivergenceOutcome.STALE_LEDGER, WptHarness.classify(false, true));
    }

    @Test
    public void testDivergenceKey() {
        assertEquals("dom/nodes/Node-nodeType.html#Element nodeType must be 1",
                WptHarness.divergenceKey("dom/nodes/Node-nodeType.html", "Element nodeType must be 1"));
    }

    @Test
    public void testTrackedFailureIsExpected() {
        Map<String, String> ledger = Map.of("dom/nodes/x.html#sub", "R2: 未対応");
        assertDoesNotThrow(() -> WptHarness.expectDivergence(
                "dom/nodes/x.html#sub", ledger, () -> {
                    throw new AssertionError("browser passes, engine fails");
                }));
    }

    @Test
    public void testTrackedPassIsStaleLedger() {
        Map<String, String> ledger = Map.of("dom/nodes/x.html#sub", "R2: 未対応");
        AssertionError error = assertThrows(AssertionError.class, () -> WptHarness.expectDivergence(
                "dom/nodes/x.html#sub", ledger, () -> {
                    // 通ってしまった = 台帳が古い
                }));
        assertTrue(error.getMessage().contains("台帳が古い"), error.getMessage());
    }

    @Test
    public void testUntrackedFailureIsRegression() {
        AssertionError error = assertThrows(AssertionError.class, () -> WptHarness.expectDivergence(
                "dom/nodes/x.html#sub", Map.of(), () -> {
                    throw new AssertionError("unexpected");
                }));
        assertTrue(error.getMessage().contains("台帳に無い差異"), error.getMessage());
    }

    @Test
    public void testUntrackedPassIsFine() {
        assertDoesNotThrow(() -> WptHarness.expectDivergence(
                "dom/nodes/x.html#sub", Map.of(), () -> {
                    // ブラウザと一致
                }));
    }

    @Test
    public void testLedgerParser() {
        Map<String, String> rows = WptHarness.parseLedger("""
                # comment
                dom/nodes/x.html#first R2: 未対応
                dom/nodes/x.html#second   R5: 遅延
                """);
        assertEquals(2, rows.size());
        assertEquals("R2: 未対応", rows.get("dom/nodes/x.html#first"));
        assertEquals("R5: 遅延", rows.get("dom/nodes/x.html#second"));
    }

    @Test
    public void testLedgerParserRejectsMissingReason() {
        assertThrows(IllegalStateException.class,
                () -> WptHarness.parseLedger("dom/nodes/x.html#sub\n"));
        assertThrows(IllegalStateException.class,
                () -> WptHarness.parseLedger("dom/nodes/x.html#sub   \n"));
    }

    @Test
    public void testLedgerParserRejectsWrongKeyAndDuplicates() {
        assertThrows(IllegalStateException.class,
                () -> WptHarness.parseLedger("no-hash R2: reason\n"));
        assertThrows(IllegalStateException.class,
                () -> WptHarness.parseLedger("a#b R2: one\na#b R3: two\n"));
    }
}
