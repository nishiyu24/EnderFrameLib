package com.nishiyu.enderframelib.server;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * クライアントが指定した NBT パスの許可リスト（I-1）。
 *
 * <p>AR HUD / トラッカーは「どのキーを配信するか」をクライアントが決める作りなので、
 * 購読要求は <b>サーバー NBT の読み出し要求</b>として扱う必要がある。ここが唯一の判定箇所。
 * Minecraft に依存しないので単体テストできる。</p>
 */
@ApiStatus.Internal
public final class NbtPathFilter {

    /** パス 1 セグメントの文字種。NBT のキーに使える範囲だけを許す。 */
    private static final Pattern VALID_SEGMENT = Pattern.compile("[A-Za-z0-9_+.\\-]{1,128}");

    public static final int MAX_PATH_DEPTH = 6;
    public static final int MAX_PATHS = 16;

    /**
     * 一般のエンティティ / BlockEntity で読み出しを許すルートキー。
     * ここに無いキー（Inventory・Items・EnderItems・abilities など）は読めない。
     */
    private static final Set<String> ALLOWED_ROOTS = ConcurrentHashMap.newKeySet();
    /** プレイヤーを対象にした場合の、より狭い許可リスト。 */
    private static final Set<String> PLAYER_ALLOWED_ROOTS = ConcurrentHashMap.newKeySet();

    static {
        ALLOWED_ROOTS.addAll(Set.of(
                "id", "UUID", "CustomName", "CustomNameVisible", "Pos", "Motion", "Rotation",
                "Health", "AbsorptionAmount", "Air", "Fire", "FallDistance", "Invulnerable",
                "Glowing", "NoAI", "NoGravity", "Silent", "Age", "Variant", "Color", "Size",
                "DeathTime", "HurtTime", "Tame", "Sitting", "Saddle", "Owner", "Anger", "AngerTime",
                "Invisible", "Team", "Leash", "PortalCooldown",
                // BlockEntity 側（進捗バーや燃料の表示に使う範囲）
                "Progress", "progress", "fuel", "BurnTime", "CookTime", "CookTimeTotal",
                "RecipesUsed", "energy", "Energy", "Lock", "LootTable", "Level", "Levels"
        ));
        PLAYER_ALLOWED_ROOTS.addAll(Set.of(
                "id", "UUID", "CustomName", "Pos", "Motion", "Rotation",
                "Health", "AbsorptionAmount", "Air", "Fire", "FallDistance",
                "Glowing", "Team", "XpLevel", "Score", "playerGameType", "SelectedItemSlot"
        ));
    }

    private NbtPathFilter() {}

    /**
     * 読み出しを許可するルートキーを追加する。
     * 下流 Mod が自分の BlockEntity のキーを HUD に出したい場合の入口。
     *
     * @return 追加できたら true（文字種が不正なら false）
     */
    public static boolean allowRoot(String rootKey) {
        if (rootKey == null || !VALID_SEGMENT.matcher(rootKey).matches()) return false;
        return ALLOWED_ROOTS.add(rootKey);
    }

    /** 購読時に一度だけ検証し、通ったパスだけを保持する。tick ごとの検証を避けるため。 */
    public static List<String> sanitize(@Nullable List<String> raw) {
        if (raw == null || raw.isEmpty()) return List.of();
        Set<String> accepted = new LinkedHashSet<>();
        for (String path : raw) {
            String clean = normalize(path);
            if (clean != null) accepted.add(clean);
            if (accepted.size() >= MAX_PATHS) break;
        }
        return List.copyOf(accepted);
    }

    /**
     * {@code "nbt.Health"} → {@code "Health"} に正規化し、形として妥当でなければ null を返す。
     * 許可リストの判定は対象（プレイヤーかどうか）が分かる抽出時に行う。
     */
    @Nullable
    public static String normalize(@Nullable String path) {
        if (path == null || path.isEmpty()) return null;
        String clean = path.trim();
        if (clean.startsWith("nbt.")) clean = clean.substring(4);
        if (clean.isEmpty() || clean.startsWith(".") || clean.endsWith(".")) return null;

        String[] parts = clean.split("\\.");
        if (parts.length == 0 || parts.length > MAX_PATH_DEPTH) return null;
        for (String part : parts) {
            if (!VALID_SEGMENT.matcher(part).matches()) return null;
        }
        return clean;
    }

    /** 正規化済みパスのルートキーが許可されているか。 */
    public static boolean isRootAllowed(String normalizedPath, boolean playerTarget) {
        if (normalizedPath == null || normalizedPath.isEmpty()) return false;
        int dot = normalizedPath.indexOf('.');
        String root = dot >= 0 ? normalizedPath.substring(0, dot) : normalizedPath;
        return playerTarget ? PLAYER_ALLOWED_ROOTS.contains(root) : ALLOWED_ROOTS.contains(root);
    }
}
