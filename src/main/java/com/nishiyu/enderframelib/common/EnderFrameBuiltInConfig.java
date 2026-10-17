package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.EnderFrameLib;
import com.nishiyu.enderframelib.api.config.ConfigEntry;
import com.nishiyu.enderframelib.api.config.EnderConfig;

/**
 * EnderFrameLib 自体のビルトイン設定クラス。
 * Dedicated Server でも安全にロードできるよう、クライアント専用クラスへの直接参照を持たない設計としている。
 */
@EnderConfig(
        fileName = EnderFrameLib.MODID,
        title = "EnderFrameLib Engine Settings",
        theme = "enderframelib:dark.css"
)
public class EnderFrameBuiltInConfig {

    // 設定値はリフレクションで書き込まれ、レンダースレッドなど別のスレッドから読まれる。
    // volatile でないと読み側に happens-before の保証が無い（EnderConfigManager の Javadoc 参照）

    @ConfigEntry(
            category = "Rendering",
            label = "Use SDF Shader",
            description = "Enable hardware-accelerated Signed Distance Field (SDF) rendering for anti-aliased rounded corners and borders."
    )
    public static volatile boolean useSdfShader = true;

    @ConfigEntry(
            category = "Rendering",
            label = "Default UI Accent Color",
            description = "Primary accent color used across default Web UI components.",
            colorPicker = true
    )
    public static volatile int accentColor = 0x89B4FA;

    @ConfigEntry(
            category = "Interaction",
            label = "Enable Default UI Sounds",
            description = "Play standard click sounds when interacting with buttons, selects, and toggles."
    )
    public static volatile boolean enableDefaultUiSounds = true;

    @ConfigEntry(
            category = "Interaction",
            label = "Scroll Speed Multiplier",
            description = "Sensitivity multiplier for mouse wheel scrolling inside overflow:auto/scroll containers.",
            min = 10,
            max = 100,
            step = 5,
            slider = true
    )
    public static volatile int scrollSensitivity = 30;

    @ConfigEntry(
            category = "Developer Tools",
            label = "Default Theme Preset",
            description = "Built-in CSS stylesheet applied when no theme is specified.",
            options = {"enderframelib:dark.css", "enderframelib:vanilla.css", "enderframelib:brass.css"}
    )
    public static volatile String defaultTheme = "enderframelib:dark.css";

    /**
     * クライアント起動時に EnderFrameClientInit からバインドされるランタイム反映フック。
     * Dedicated Server 環境では null のままとなり、クライアントクラスのロードを発生させない。
     */
    public static Runnable runtimeApplier = null;

    public static void applyToRuntime() {
        if (runtimeApplier != null) {
            runtimeApplier.run();
        }
    }
}