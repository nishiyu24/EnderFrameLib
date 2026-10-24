package com.nishiyu.enderframelib.api.template;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Supplier;

/**
 * インタラクティブなブロック画面のひな形（ブロック側）。
 *
 * <p>置いたときにプレイヤーの方を向き、隣り合うブロックと 1 枚の画面にまとまる。壊したときは残りを組み直す。
 * 向きはブロックエンティティが持つので、blockstate には property が要らない（モデルは普通の 1 枚でよい）。</p>
 *
 * <pre>{@code
 * // 登録（RegisterEvent の中。Block と BlockEntityType は相互に参照する）
 * SCREEN = new EnderScreenBlock(BlockBehaviour.Properties.of().strength(1.0f), () -> SCREEN_ENTITY);
 * SCREEN_ENTITY = BlockEntityType.Builder.of(MyScreenEntity::new, SCREEN).build(null);
 * // クライアント: Surfaces.block(modEventBus, () -> SCREEN_ENTITY);
 * }</pre>
 */
@ApiStatus.AvailableSince("1.1")
public class EnderScreenBlock extends Block implements EntityBlock {

    private final Supplier<? extends BlockEntityType<? extends EnderScreenBlockEntity>> entityType;

    public EnderScreenBlock(Properties properties, Supplier<? extends BlockEntityType<? extends EnderScreenBlockEntity>> entityType) {
        super(properties);
        this.entityType = entityType;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return entityType.get().create(pos, state);
    }

    /** 置いたとき、プレイヤーの方を向く。画面にするには draw の命令が要る。 */
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide || !(level.getBlockEntity(pos) instanceof EnderScreenBlockEntity be)) return;
        be.setFacing(placer != null ? placer.getDirection().getOpposite() : Direction.NORTH);
    }

    /** 壊すとき、その画面のセッションを捨て、残りのブロックを初期化する（組み直さない）。 */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && !level.isClientSide
                && level.getBlockEntity(pos) instanceof EnderScreenBlockEntity be) {
            be.onScreenBlockBreaking();
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
