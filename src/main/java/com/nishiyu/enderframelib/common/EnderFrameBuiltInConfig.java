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
            category = "Rendering",
            label = "Web UI Zoom (%)",
            description = "Page zoom for full-screen Web UIs, like a browser's zoom. The CSS viewport is the window size divided by (OS display scale x zoom), so larger windows show more content.",
            min = 25,
            max = 300,
            step = 5,
            slider = true
    )
    public static volatile int uiZoomPercent = 100;

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

    // 旧キー名 allowExternalMedia は既定 true だったため、既存の設定ファイルでは
    // 利用者が明示していなくても true が残っている。キー名を変えて既定 false を一度
    // 適用し、外部メディアを使う場合は改めて明示的に有効化してもらう。
    @ConfigEntry(
            category = "Media",
            label = "Allow External Web Media",
            description = "Allow downloading and rendering images/videos from public HTTP/HTTPS URLs. Disabled by default: only mod:/mc: media works until this is enabled. Use Allowed Media Domains to restrict which hosts may be contacted."
    )
    public static volatile boolean enableExternalWebMedia = false;

    @ConfigEntry(
            category = "Media",
            label = "Allowed Media Domains",
            description = "Comma-separated hostnames (subdomains included) allowed for external media. Leave empty to allow any public host while external media is enabled. When using yt-dlp, include youtube.com (or youtu.be) and the resolved media host (for example googlevideo.com). The yt-dlp binary itself is fetched from the official GitHub release separately, so GitHub does not need to be listed here. Loopback/LAN/link-local addresses are blocked while Block Private Network Media is enabled."
    )
    public static volatile String allowedMediaDomains = "";

    @ConfigEntry(
            category = "Media",
            label = "Block Private Network Media",
            description = "Refuse to connect to loopback, LAN, link-local, carrier-grade NAT, and other non-public addresses. Keep enabled to prevent server-side request forgery (SSRF)."
    )
    public static volatile boolean blockPrivateNetworkAccess = true;

    @ConfigEntry(
            category = "Media",
            label = "Max Video Stream (MB)",
            description = "Videos are streamed through a loopback relay and are never saved to disk. This limits the size of each relayed response (range); increase it for long or high-bitrate videos.",
            min = 1,
            max = 4096,
            slider = true
    )
    public static volatile int maxVideoDownloadMb = 512;

    @ConfigEntry(
            category = "Media",
            label = "Enable YouTube Playback (yt-dlp)",
            description = "Allow EnderFrameLib to download the official yt-dlp executable from GitHub (SHA-256 verified) and run it to resolve YouTube video streams. Requires Allow External Web Media. An existing binary in <game dir>/enderframelib/ is used as-is. Only YouTube URLs are passed to the process; self-update is disabled."
    )
    public static volatile boolean allowYtDlp = false;

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