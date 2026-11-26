package com.nishiyu.enderframelib.server.state;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import sun.misc.Unsafe;

import java.lang.reflect.Field;

/**
 * ユニットテスト用: ワールドやブロックエンティティタイプを初期化せずに BlockEntity を生成するヘルパー。
 */
public final class TestBlockEntityHelper {

    private static final Unsafe UNSAFE;

    static {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            UNSAFE = (Unsafe) f.get(null);
        } catch (Exception e) {
            throw new RuntimeException("Failed to obtain Unsafe", e);
        }
    }

    private TestBlockEntityHelper() {}

    /**
     * allocateInstance 可能な BlockEntity 具象サブクラス。
     */
    private static class DummyBlockEntity extends BlockEntity {
        public DummyBlockEntity() {
            super(null, BlockPos.ZERO, null);
        }
    }

    public static BlockEntity createMockBlockEntity(BlockPos pos) {
        try {
            DummyBlockEntity be = (DummyBlockEntity) UNSAFE.allocateInstance(DummyBlockEntity.class);
            Class<?> clazz = BlockEntity.class;
            while (clazz != null && clazz != Object.class) {
                for (Field f : clazz.getDeclaredFields()) {
                    if (f.getType() == BlockPos.class) {
                        f.setAccessible(true);
                        f.set(be, pos);
                        return be;
                    }
                }
                clazz = clazz.getSuperclass();
            }
            return be;
        } catch (Exception e) {
            throw new RuntimeException("Failed to allocate BlockEntity mock", e);
        }
    }
}
