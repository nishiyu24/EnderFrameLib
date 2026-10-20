package com.nishiyu.enderframelib.wpt;

import org.junit.jupiter.api.function.Executable;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import com.nishiyu.enderframelib.common.Document;
import com.nishiyu.enderframelib.common.RenderPipeline;
import com.nishiyu.enderframelib.core.paint.*;
import com.nishiyu.enderframelib.core.paint.model.*;
import com.nishiyu.enderframelib.core.text.*;
import com.nishiyu.enderframelib.core.text.model.*;
import com.nishiyu.enderframelib.core.layout.LayoutContext;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.layout.model.Viewport;
import com.nishiyu.enderframelib.core.layout.model.FormattingContext;
import com.nishiyu.enderframelib.core.layout.model.ConstraintSpace;
import com.nishiyu.enderframelib.core.layout.model.LayoutMetrics;
import com.nishiyu.enderframelib.core.css.model.Overflow;
import com.nishiyu.enderframelib.core.css.model.TextAlign;
import com.nishiyu.enderframelib.core.css.model.BorderStyle;

/**
 * WPT (Web Platform Tests) の testharness.js テストケースを JUnit に移植するための基盤。
 *
 * <p>testharness.js の主要なアサーション（assert_equals, assert_true, assert_throws_dom 等）を提供し、
 * 各テストメソッドには {@link WptCase} アノテーションで WPT アップストリームのパスと固定 SHA を記録する。
 *
 * <p>ブラウザ側の期待値と一致しないことが分かっているサブテストは、
 * {@link #expectDivergence(String, String, Executable)} で包み、台帳
 * {@code src/test/resources/wpt/known-divergences.txt} にフェーズ付きで載せる。
 * 台帳に無い失敗は回帰、載っているのに通ったものは「台帳が古い」として落ちる。
 */
public final class WptHarness {

    /** ApricityUI と同一のピン留めされた WPT コミット SHA */
    public static final String PINNED_SHA = "a6f29b0bedaf3f1edba7b6739127fe8e713bfcb3";

    private static final String DIVERGENCE_LEDGER = "/wpt/known-divergences.txt";

    /** 実行中のテストメソッドに付いた {@link WptCase#path()}。台帳の引き当てに使う。 */
    private static final ThreadLocal<String> CURRENT_CASE_PATH = new ThreadLocal<>();

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    public @interface WptCase {
        /** WPT のリポジトリルートからの相対パス (例: "dom/nodes/Node-nodeType.html") */
        String path();

        /** 固定された WPT のコミット SHA */
        String sha() default PINNED_SHA;

        /** テスト対象の仕様・目的の説明 */
        String description() default "";
    }

    private WptHarness() {}

    /** testharness.js の test(fn, name) 相当。実行中のケースが分かれば台帳も引く。 */
    public static void test(String name, Executable body) {
        String path = CURRENT_CASE_PATH.get();
        if (path != null) {
            expectDivergence(path, name, body);
            return;
        }
        try {
            body.execute();
        } catch (Throwable t) {
            fail("WPT test failed: [" + name + "] " + t.getMessage(), t);
        }
    }

    /** テストメソッドの {@link WptCase#path()} を現在のスレッドに結ぶ（{@code WptBehaviorTest} が呼ぶ）。 */
    static void bindCase(String path) {
        CURRENT_CASE_PATH.set(path);
    }

    static void clearCase() {
        CURRENT_CASE_PATH.remove();
    }

    /** testharness.js の assert_equals 相当 */
    public static void assertEqualsWpt(Object expected, Object actual, String description) {
        assertEquals(expected, actual, description);
    }

    /** testharness.js の assert_true 相当 */
    public static void assertTrueWpt(boolean actual, String description) {
        assertTrue(actual, description);
    }

    /** testharness.js の assert_false 相当 */
    public static void assertFalseWpt(boolean actual, String description) {
        assertFalse(actual, description);
    }

    /** testharness.js の assert_not_equals 相当 */
    public static void assertNotEqualsWpt(Object expected, Object actual, String description) {
        if (Objects.equals(expected, actual)) {
            fail(description + ": expected different value, but both were: " + actual);
        }
    }

    /** testharness.js の assert_throws_dom 相当 */
    public static void assertThrowsDom(String errorName, Executable executable, String description) {
        Throwable caught = null;
        try {
            executable.execute();
        } catch (Throwable t) {
            caught = t;
        }
        if (caught == null) {
            fail(description + ": expected DOMException [" + errorName + "], but nothing was thrown");
        }
        String className = caught.getClass().getSimpleName();
        String msg = caught.getMessage() != null ? caught.getMessage() : "";
        boolean matched = className.contains(errorName) || msg.contains(errorName);
        if (caught instanceof com.nishiyu.enderframelib.api.dom.DOMException domEx) {
            matched = matched || errorName.equalsIgnoreCase(domEx.getName());
        }
        assertTrue(matched, description + ": expected DOMException [" + errorName + "], but got: " + className
                + (caught instanceof com.nishiyu.enderframelib.api.dom.DOMException domEx ? " (" + domEx.getName() + ": " + msg + ")" : " (" + msg + ")"));
    }

    /**
     * ブラウザでは pass するが本エンジンでは未対応、と台帳に記録済みのサブテストを実行する。
     *
     * <p>台帳（{@code wpt/known-divergences.txt}）のキーは {@code path#subtest}。
     * 台帳に無い失敗はそのまま回帰として落とし、台帳にあるのに通ったら台帳が古いとして落とす。
     * 既知差分を持つケースを移植するときは {@link #test(String, Executable)} の代わりにこれを使う。
     */
    public static void expectDivergence(String wptPath, String subtest, Executable body) {
        expectDivergence(divergenceKey(wptPath, subtest), knownDivergences(), body);
    }

    /** 台帳を差し替えて判定する版（単体テスト用）。 */
    static void expectDivergence(String key, Map<String, String> ledger, Executable body) {
        Throwable failure = null;
        try {
            body.execute();
        } catch (Throwable t) {
            failure = t;
        }
        switch (classify(failure != null, ledger.containsKey(key))) {
            case PASSED -> {
                // 期待どおり（ブラウザと一致）
            }
            case EXPECTED_FAILURE ->
                    System.out.println("[WPT] 既知差分（台帳どおり失敗）: " + key + " — " + ledger.get(key));
            case UNEXPECTED_FAILURE ->
                    fail("WPT test failed (台帳に無い差異): [" + key + "] "
                            + (failure.getMessage() != null ? failure.getMessage() : ""), failure);
            case STALE_LEDGER ->
                    fail("既知差分の台帳が古い: [" + key + "] は通ったので "
                            + "src/test/resources/wpt/known-divergences.txt から行を削除する");
        }
    }

    /** {@code wptPath#subtest} 形式の台帳キー。 */
    public static String divergenceKey(String wptPath, String subtest) {
        Objects.requireNonNull(wptPath, "wptPath");
        Objects.requireNonNull(subtest, "subtest");
        return wptPath + "#" + subtest;
    }

    /** 実行結果と台帳の有無から、どう扱うかを決める（純関数。単体テスト用に公開）。 */
    static DivergenceOutcome classify(boolean failed, boolean tracked) {
        if (failed) return tracked ? DivergenceOutcome.EXPECTED_FAILURE : DivergenceOutcome.UNEXPECTED_FAILURE;
        return tracked ? DivergenceOutcome.STALE_LEDGER : DivergenceOutcome.PASSED;
    }

    enum DivergenceOutcome {
        /** 失敗せず、台帳にも無い（正常）。 */
        PASSED,
        /** 失敗したが台帳にある（既知差分）。 */
        EXPECTED_FAILURE,
        /** 失敗し、台帳にも無い（回帰）。 */
        UNEXPECTED_FAILURE,
        /** 通ったが台帳にある（台帳が古い）。 */
        STALE_LEDGER
    }

    /** 台帳を読む。キーは {@code path#subtest}、値は理由（フェーズを含む）。 */
    public static Map<String, String> knownDivergences() {
        return DivergenceLedger.ROWS;
    }

    private static final class DivergenceLedger {
        static final Map<String, String> ROWS = load();

        private static Map<String, String> load() {
            String text;
            try (InputStream is = WptHarness.class.getResourceAsStream(DIVERGENCE_LEDGER)) {
                if (is == null) {
                    throw new IllegalStateException("WPT の既知差分台帳が無い: " + DIVERGENCE_LEDGER);
                }
                text = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return parseLedger(text);
        }
    }

    /** 台帳のテキストを読む（単体テスト用に公開）。 */
    static Map<String, String> parseLedger(String text) {
        Map<String, String> rows = new LinkedHashMap<>();
        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            int sp = trimmed.indexOf(' ');
            if (sp < 0) {
                throw new IllegalStateException("台帳の行に理由が無い: " + trimmed);
            }
            String key = trimmed.substring(0, sp);
            String reason = trimmed.substring(sp + 1).trim();
            if (!key.contains("#")) {
                throw new IllegalStateException("台帳のキーは path#subtest 形式であること: " + key);
            }
            if (reason.isEmpty()) {
                throw new IllegalStateException("台帳の行に理由が無い: " + trimmed);
            }
            if (rows.put(key, reason) != null) {
                throw new IllegalStateException("台帳に同じキーが 2 回ある: " + key);
            }
        }
        return Collections.unmodifiableMap(rows);
    }
}
