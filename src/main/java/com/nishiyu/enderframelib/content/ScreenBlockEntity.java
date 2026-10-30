package com.nishiyu.enderframelib.content;

import com.nishiyu.enderframelib.api.template.EnderScreenBlockEntity;
import com.nishiyu.enderframelib.common.UiDocumentCompiler;
import com.nishiyu.enderframelib.server.EnderFrameDataPackManager;
import com.nishiyu.enderframelib.server.WebSession;
import com.nishiyu.enderframelib.server.WebSessionManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * EnderFrameLib の標準スクリーンブロックエンティティ。
 * データパック UI や組み込み UI をワールド内マルチブロック画面に投影できる。
 */
public class ScreenBlockEntity extends EnderScreenBlockEntity {

    @Nullable
    private ResourceLocation uiId;

    public ScreenBlockEntity(BlockPos pos, BlockState state) {
        this(resolveType(), pos, state);
    }

    public ScreenBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type != null ? type : resolveType(), pos, state);
    }

    private static BlockEntityType<?> resolveType() {
        if (EnderFrameContent.SCREEN_BLOCK_ENTITY_RAW != null) {
            return EnderFrameContent.SCREEN_BLOCK_ENTITY_RAW;
        }
        return BlockEntityType.Builder.of(ScreenBlockEntity::new).build(null);
    }

    public @Nullable ResourceLocation getUiId() {
        if (isMaster()) return uiId;
        if (level != null && level.getBlockEntity(getScreenMasterPos()) instanceof ScreenBlockEntity master) {
            return master.uiId;
        }
        return uiId;
    }

    public void setUiId(@Nullable ResourceLocation uiId) {
        this.uiId = uiId;
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    @Override
    public int getLodColor() {
        return 0xFF1E88E5;
    }

    @Override
    protected void onScreenFormed(int width, int height) {
        if (level instanceof ServerLevel) {
            ResourceLocation currentUiId = getUiId();
            if (currentUiId != null) {
                String sessionId = getSessionId();
                int pxW = width * getPixelsPerBlock();
                int pxH = height * getPixelsPerBlock();
                String html = resolveServerHtml(currentUiId);
                if (html != null && !html.isEmpty()) {
                    WebSession session = WebSessionManager.getOrCreateStandaloneSession(sessionId, pxW, pxH);
                    session.loadHtml(html);
                }
            }
        }
    }

    private static String resolveServerHtml(ResourceLocation id) {
        String html = EnderFrameDataPackManager.getHtml(id);
        if ((html == null || html.isEmpty()) && UiDocumentCompiler.resourceTextResolver != null) {
            html = UiDocumentCompiler.resourceTextResolver.apply(id.toString());
        }
        return (html != null && !html.isEmpty()) ? html : null;
    }

    @Override
    public void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (uiId != null) {
            tag.putString("UiId", uiId.toString());
        }
    }

    @Override
    public void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("UiId")) {
            uiId = ResourceLocation.tryParse(tag.getString("UiId"));
        } else {
            uiId = null;
        }
    }
}
