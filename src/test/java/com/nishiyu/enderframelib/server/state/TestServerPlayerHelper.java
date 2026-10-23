package com.nishiyu.enderframelib.server.state;

import com.mojang.authlib.GameProfile;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerPlayer;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ユニットテスト用: MinecraftServer を起動せずに ServerPlayer インスタンスを安全に生成するヘルパー。
 */
public final class TestServerPlayerHelper {

    private static final Unsafe UNSAFE;
    private static final AtomicInteger NEXT_ENTITY_ID = new AtomicInteger(1000);

    static {
        try {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
        } catch (Throwable ignored) {
        }

        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            UNSAFE = (Unsafe) f.get(null);
        } catch (Exception e) {
            throw new RuntimeException("Failed to obtain Unsafe", e);
        }
    }

    private TestServerPlayerHelper() {}

    public static ServerPlayer createMockPlayer(String name, UUID uuid) {
        try {
            ServerPlayer player = (ServerPlayer) UNSAFE.allocateInstance(ServerPlayer.class);

            int entityId = NEXT_ENTITY_ID.incrementAndGet();
            if (!setFieldRecursively(player, "id", entityId)) {
                // メソッド setId(int) があれば試す
                try {
                    Method setId = player.getClass().getMethod("setId", int.class);
                    setId.invoke(player, entityId);
                } catch (Exception ignored) {}
            }

            // GameProfile の設定（Entity / Player のスーパークラスフィールド）
            UUID resolvedUuid = uuid != null ? uuid : UUID.randomUUID();
            GameProfile profile = new GameProfile(resolvedUuid, name);
            setFieldRecursively(player, "gameProfile", profile);
            // Entity#getUUID() は Entity.uuid を返すため、こちらも揃える
            setFieldRecursively(player, "uuid", resolvedUuid);

            return player;
        } catch (Exception e) {
            throw new RuntimeException("Failed to allocate ServerPlayer mock", e);
        }
    }

    public static ServerPlayer createMockPlayer(String name) {
        return createMockPlayer(name, UUID.randomUUID());
    }

    private static boolean setFieldRecursively(Object target, String fieldName, Object value) {
        Class<?> clazz = target.getClass();
        while (clazz != null && clazz != Object.class) {
            try {
                Field f = clazz.getDeclaredField(fieldName);
                f.setAccessible(true);
                f.set(target, value);
                return true;
            } catch (NoSuchFieldException ignored) {
                clazz = clazz.getSuperclass();
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }
        return false;
    }
}
