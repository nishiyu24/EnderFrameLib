package com.nishiyu.enderframelib.client.renderer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Set;

/**
 * YouTube 再生用 yt-dlp 実行ファイルの自動配置。
 *
 * <p>利用者が設定で明示的に有効化した場合だけ、公式 GitHub リリースから
 * プラットフォーム別のバイナリを取得する。取得先は GitHub の配布ホストに限定し、
 * 同じリリースの {@code SHA2-256SUMS} と照合してから、検証済みのファイルだけを
 * 最終パスへ原子的に移動する。自己更新（{@code -U}）は行わない。</p>
 *
 * <p>既に {@code <game dir>/enderframelib/} に実行ファイルがある場合は、それを
 * そのまま使う（利用者による手動配置も引き続き有効）。</p>
 */
final class YtDlpProvisioner {

    private static final Logger LOGGER = LoggerFactory.getLogger(YtDlpProvisioner.class);

    static final String RELEASE_BASE = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/";
    static final String CHECKSUMS_URL = RELEASE_BASE + "SHA2-256SUMS";

    /**
     * 公式リリースの取得にだけ使う完全一致のホスト集合。設定ファイルからは受け取らない。
     * GitHub はリリース資産を署名付き URL へリダイレクトするため、その配信ホストも含める。
     */
    static final Set<String> DOWNLOAD_HOSTS = Set.of(
            "github.com",
            "objects.githubusercontent.com",
            "release-assets.githubusercontent.com",
            "github-releases.githubusercontent.com"
    );

    private static final long MAX_CHECKSUM_BYTES = 1024L * 1024L;
    private static final long MAX_BINARY_BYTES = 128L * 1024L * 1024L;
    private static final long RETRY_COOLDOWN_MS = 60_000L;

    private static final Object LOCK = new Object();
    private static long lastFailureAt;
    private static Path lastFailureTarget;

    private YtDlpProvisioner() {}

    record Asset(String assetName, String fileName) {}

    @FunctionalInterface
    interface Installer {
        void install(Path target, Asset asset) throws IOException;
    }

    /** 実行中の OS / CPU に合う公式リリース資産と、ローカルでのファイル名を返す。 */
    static Asset assetForCurrentPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean arm64 = arch.contains("aarch64") || arch.contains("arm64");
        boolean x86_32 = (arch.contains("x86") && !arch.contains("64"))
                || arch.contains("i386") || arch.contains("i686");

        if (os.contains("win")) {
            if (arm64) return new Asset("yt-dlp_arm64.exe", "yt-dlp.exe");
            if (x86_32) return new Asset("yt-dlp_x86.exe", "yt-dlp.exe");
            return new Asset("yt-dlp.exe", "yt-dlp.exe");
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return new Asset("yt-dlp_macos", "yt-dlp_macos");
        }
        if (os.contains("linux")) {
            if (arm64) return new Asset("yt-dlp_linux_aarch64", "yt-dlp");
            // 32bit ARM 向けの単体バイナリは配布されていないため、Python 製の zipapp を使う。
            if (x86_32 || arch.contains("arm")) return new Asset("yt-dlp", "yt-dlp");
            return new Asset("yt-dlp_linux", "yt-dlp");
        }
        return new Asset("yt-dlp", "yt-dlp");
    }

    static Path binaryPath(File gameDirectory) {
        return gameDirectory.toPath().toAbsolutePath().normalize()
                .resolve("enderframelib")
                .resolve(assetForCurrentPlatform().fileName());
    }

    /**
     * 設定で許可されていれば yt-dlp を用意し、実行可能なファイルを返す。
     * 取得に失敗した場合は {@code null}（60 秒間は同じパスへの再試行を抑止する）。
     */
    static File ensureAvailable(File gameDirectory) {
        return ensureAvailable(gameDirectory, YtDlpProvisioner::installOfficial);
    }

    static File ensureAvailable(File gameDirectory, Installer installer) {
        if (gameDirectory == null || installer == null) return null;
        if (!WebMediaManager.allowExternalMedia || !WebMediaManager.allowYtDlp) return null;

        Asset asset = assetForCurrentPlatform();
        Path target = binaryPath(gameDirectory);
        if (isUsable(target)) {
            makeExecutable(target);
            return target.toFile();
        }

        synchronized (LOCK) {
            if (!WebMediaManager.allowExternalMedia || !WebMediaManager.allowYtDlp) return null;
            if (isUsable(target)) {
                makeExecutable(target);
                return target.toFile();
            }

            long now = System.currentTimeMillis();
            if (target.equals(lastFailureTarget) && now - lastFailureAt < RETRY_COOLDOWN_MS) {
                return null;
            }

            try {
                Path parent = target.getParent();
                if (parent == null) throw new IOException("yt-dlp target has no parent directory");
                Files.createDirectories(parent);
                installer.install(target, asset);
                if (!isUsable(target)) throw new IOException("yt-dlp installer produced no file");
                makeExecutable(target);
                lastFailureTarget = null;
                LOGGER.info("yt-dlp is ready at {}", target);
                return target.toFile();
            } catch (Exception e) {
                lastFailureTarget = target;
                lastFailureAt = now;
                LOGGER.warn("Could not prepare yt-dlp at {}: {}", target, e.getMessage());
                return null;
            }
        }
    }

    private static void installOfficial(Path target, Asset asset) throws IOException {
        install(target, asset.assetName(), RELEASE_BASE + asset.assetName(),
                CHECKSUMS_URL, DOWNLOAD_HOSTS);
    }

    /**
     * 公式リリースの資産とチェックサムを取得し、検証してから {@code target} へ置く。
     * テストから差し替えられるよう、URL と信頼ホストを引数にしている。
     */
    static void install(Path target, String assetName, String assetUrl, String checksumsUrl,
                        Set<String> trustedHosts) throws IOException {
        Path absoluteTarget = target.toAbsolutePath().normalize();
        Path parent = absoluteTarget.getParent();
        if (parent == null) throw new IOException("yt-dlp target has no parent directory");
        Files.createDirectories(parent);

        byte[] checksumBytes = SafeHttpClient.fetchBytesFromTrustedHosts(
                checksumsUrl, MAX_CHECKSUM_BYTES, trustedHosts);
        String expected = parseChecksum(new String(checksumBytes, StandardCharsets.UTF_8), assetName);
        if (expected == null) {
            throw new IOException("SHA2-256SUMS has no entry for " + assetName);
        }

        Path temp = Files.createTempFile(parent, "yt-dlp-", ".part");
        try {
            SafeHttpClient.downloadFromTrustedHosts(assetUrl, MAX_BINARY_BYTES, trustedHosts, temp);
            String actual = sha256(temp);
            if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                    actual.getBytes(StandardCharsets.US_ASCII))) {
                throw new IOException("yt-dlp checksum mismatch");
            }
            move(temp, absoluteTarget);
            makeExecutable(absoluteTarget);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** {@code SHA2-256SUMS} から、指定した資産名の SHA-256 を完全一致で探す。 */
    static String parseChecksum(String sums, String assetName) {
        if (sums == null || assetName == null) return null;
        String text = sums.startsWith("\uFEFF") ? sums.substring(1) : sums;
        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            String[] parts = trimmed.split("\\s+", 2);
            if (parts.length != 2) continue;
            String hash = parts[0];
            String name = parts[1].trim();
            if (name.startsWith("*")) name = name.substring(1);
            if (name.equals(assetName) && isSha256Hex(hash)) {
                return hash.toLowerCase(Locale.ROOT);
            }
        }
        return null;
    }

    private static boolean isUsable(Path path) {
        try {
            return Files.isRegularFile(path) && Files.size(path) > 0;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean isSha256Hex(String value) {
        if (value.length() != 64) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
                return false;
            }
        }
        return true;
    }

    private static String sha256(Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is unavailable", e);
        }
        try (InputStream in = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder result = new StringBuilder(64);
        for (byte b : digest.digest()) {
            result.append(String.format("%02x", b & 0xFF));
        }
        return result.toString();
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void makeExecutable(Path path) {
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) return;
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
            if (permissions.add(PosixFilePermission.OWNER_EXECUTE)) {
                Files.setPosixFilePermissions(path, permissions);
            }
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows / FAT など POSIX 権限を持たないファイルシステム
        }
    }
}
