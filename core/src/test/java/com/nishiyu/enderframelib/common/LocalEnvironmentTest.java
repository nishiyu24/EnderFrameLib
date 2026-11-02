package com.nishiyu.enderframelib.common;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LocalEnvironmentTest {

    @BeforeEach
    @AfterEach
    void reset() {
        LocalEnvironment.setBaseSource(null);
        LocalEnvironment.clearCustomVariables();
    }

    @Test
    void defaultsWhenNoBaseSource() {
        Map<String, String> env = LocalEnvironment.capture();
        assertEquals("en_us", env.get("@lang"));
        assertEquals("60", env.get("@fps"));
    }

    @Test
    void baseSourceReplacesDefaults() {
        LocalEnvironment.setBaseSource(() -> Map.of("@lang", "ja_jp", "@hp", "20"));
        Map<String, String> env = LocalEnvironment.capture();
        assertEquals("ja_jp", env.get("@lang"));
        assertEquals("20", env.get("@hp"));
        assertNull(env.get("@fps"));
    }

    @Test
    void nullFromBaseSourceFallsBackToDefaults() {
        LocalEnvironment.setBaseSource(() -> null);
        assertEquals("en_us", LocalEnvironment.capture().get("@lang"));
    }

    @Test
    void throwingBaseSourceFallsBackToDefaults() {
        LocalEnvironment.setBaseSource(() -> { throw new IllegalStateException("boom"); });
        assertEquals("60", LocalEnvironment.capture().get("@fps"));
    }

    @Test
    void customVariablesAcceptWithAndWithoutAtSign() {
        LocalEnvironment.registerCustomVariable("mana", () -> "7");
        LocalEnvironment.registerCustomVariable("@stamina", () -> "9");
        LocalEnvironment.registerCustomVariable("nothing", () -> null);
        LocalEnvironment.registerCustomVariable("ignored", null);
        Map<String, String> env = LocalEnvironment.capture();
        assertEquals("7", env.get("@mana"));
        assertEquals("9", env.get("@stamina"));
        assertEquals("", env.get("@nothing"));
        assertFalse(env.containsKey("@ignored"));
    }

    @Test
    void failingCustomSupplierIsSkipped() {
        LocalEnvironment.registerCustomVariable("bad", () -> { throw new RuntimeException("x"); });
        LocalEnvironment.registerCustomVariable("ok", () -> "1");
        Map<String, String> env = LocalEnvironment.capture();
        assertFalse(env.containsKey("@bad"));
        assertEquals("1", env.get("@ok"));
    }

    @Test
    void snapshotIsCachedBrieflyAndReadOnly() {
        int[] calls = {0};
        LocalEnvironment.registerCustomVariable("n", () -> String.valueOf(++calls[0]));
        Map<String, String> first = LocalEnvironment.capture();
        Map<String, String> second = LocalEnvironment.capture();
        assertSame(first, second);
        assertEquals(1, calls[0]);
        assertThrows(UnsupportedOperationException.class, () -> first.put("@x", "y"));
        LocalEnvironment.invalidate();
        assertNotSame(first, LocalEnvironment.capture());
    }
}
