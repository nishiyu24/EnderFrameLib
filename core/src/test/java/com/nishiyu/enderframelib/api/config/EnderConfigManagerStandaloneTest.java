package com.nishiyu.enderframelib.api.config;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** Minecraft（FMLPaths）が無くても、差し込んだ保存先で設定を読み書きできること。 */
class EnderConfigManagerStandaloneTest {

    @EnderConfig(fileName = "standalone_cfg")
    public static class Cfg {
        @ConfigEntry(label = "Count") public static volatile int count = 3;
        @ConfigEntry public static volatile String name = "x";
    }

    private Supplier<Path> saved;

    @BeforeEach void save() { saved = EnderConfigManager.configDirProvider; }
    @AfterEach void restore() { EnderConfigManager.configDirProvider = saved; Cfg.count = 3; Cfg.name = "x"; }

    @Test
    @DisplayName("注入した保存先へ JSON を書き、書き換えて再読み込みすると値が戻る")
    void writesToInjectedDirAndReloads(@TempDir Path dir) throws Exception {
        EnderConfigManager.configDirProvider = () -> dir;
        EnderConfigManager.register("standalone_test", Cfg.class);
        Path file = dir.resolve("standalone_cfg.json");
        assertTrue(Files.exists(file), "登録で保存される");
        assertTrue(Files.readString(file).contains("\"count\": 3"));

        Files.writeString(file, "{\"count\": 42, \"name\": \"y\"}");
        EnderConfigManager.load("standalone_test");
        assertEquals(42, Cfg.count);
        assertEquals("y", Cfg.name);
    }

    @Test
    @DisplayName("既定の保存先は作業ディレクトリの config")
    void defaultDirIsRelativeConfig() {
        assertEquals(Path.of("config"), saved.get());
    }
}
