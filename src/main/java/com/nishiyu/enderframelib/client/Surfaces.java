package com.nishiyu.enderframelib.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nishiyu.enderframelib.api.WebScreenBlockEntity;
import com.nishiyu.enderframelib.client.renderer.HologramRenderer;
import com.nishiyu.enderframelib.client.renderer.WebBlockRenderer;
import com.nishiyu.enderframelib.client.renderer.WebItemRendererHelper;
import com.nishiyu.enderframelib.client.renderer.surface.ItemSurfaceSpec;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;

import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 面（セッションの画面）を世界に出す、クライアント側の唯一の登録口。置き場所はブロック・アイテム・地図・
 * ホログラムの 4 つで、どれも描画は {@code SurfaceRenderer} の 1 本に流れる。クライアントでだけ呼ぶこと。
 *
 * <pre>{@code
 * Surfaces.block(modBus, () -> SCREEN_ENTITY);
 * Surfaces.item(modBus, () -> TABLET, ItemSurfaceSpec.centered(320, 240, 0.8f));
 * Surfaces.item(modBus, () -> PAPER, spec, paperModel);                 // 親モデルの手前に重ねる
 * Surfaces.map(stack -> stack.is(Items.FILLED_MAP), ItemSurfaceSpec.onTexture(320, 240, 128, 128, 8, 16, 112));
 * Surfaces.hologram("lobby", Hologram.at(sessionId, new Vec3(x, y, z), 2f, 1.5f).asBillboard());
 * }</pre>
 *
 * <p>{@code api/} は実装パッケージに依存できないので、描画を結ぶこのクラスは {@code client/} にある。</p>
 */
public final class Surfaces {

    private Surfaces() {}

    /**
     * {@link WebScreenBlockEntity} を実装するブロックエンティティの画面を、世界内に描く。入力（視線のヒット判定）は
     * {@link WebScreenBlockEntity} を見て自動で行う。
     *
     * @param modEventBus 呼び出し元 MOD のイベントバス
     * @param type        ブロックエンティティの種類（登録後に解決される）
     */
    public static <T extends BlockEntity & WebScreenBlockEntity> void block(
            IEventBus modEventBus, Supplier<? extends BlockEntityType<T>> type) {
        modEventBus.addListener(EntityRenderersEvent.RegisterRenderers.class,
                event -> event.registerBlockEntityRenderer(type.get(), WebBlockRenderer::new));
    }

    /**
     * アイテムの面に画面を描く。セッション ID はスタックの {@code CustomData}（{@code SessionId}）から取る。
     * モデルは {@code builtin/entity} にすること。
     */
    public static void item(IEventBus modEventBus, Supplier<? extends Item> item, ItemSurfaceSpec spec) {
        item(modEventBus, item, spec, null);
    }

    /**
     * {@link #item(IEventBus, Supplier, ItemSurfaceSpec)} に、面の下に敷く親モデル（紙や地図など）を足した版。
     * {@code builtin/entity} のモデルは JSON の要素を描かないので、親モデルは BEWLR が自分で描く。
     * 位置は親アイテムのモデル JSON の {@code display} に任せ、画面は親モデルの手前の面に重なる。
     *
     * @param baseModel 親モデルの場所（{@code models/} 以下、拡張子なし）。null なら敷かない
     */
    public static void item(IEventBus modEventBus, Supplier<? extends Item> item, ItemSurfaceSpec spec,
                            ResourceLocation baseModel) {
        if (baseModel != null) {
            modEventBus.addListener(ModelEvent.RegisterAdditional.class,
                    event -> event.register(ModelResourceLocation.standalone(baseModel)));
        }
        modEventBus.addListener(RegisterClientExtensionsEvent.class, event ->
                event.registerItem(new IClientItemExtensions() {
                    private BlockEntityWithoutLevelRenderer renderer;

                    @Override
                    public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                        if (renderer == null) {
                            Minecraft mc = Minecraft.getInstance();
                            renderer = new BlockEntityWithoutLevelRenderer(mc.getBlockEntityRenderDispatcher(), mc.getEntityModels()) {
                                @Override
                                public void renderByItem(ItemStack stack, ItemDisplayContext context, PoseStack poseStack,
                                                         MultiBufferSource buffer, int packedLight, int packedOverlay) {
                                    if (baseModel != null) {
                                        renderBase(baseModel, stack, poseStack, buffer, packedLight, packedOverlay);
                                    }
                                    WebItemRendererHelper.renderSurface(stack, context, poseStack, buffer, packedLight, packedOverlay, spec);
                                }
                            };
                        }
                        return renderer;
                    }
                }, item.get()));
    }

    /**
     * 一人称で開いた地図（{@code filled_map}）に面を重ねる。バニラの地図は専用の描画で BEWLR を通らないので、
     * 地図のときだけこちらを使う。座標は地図の 128x128 のテクスチャ上で、{@link ItemSurfaceSpec#onTexture} に
     * {@code textureWidth = textureHeight = 128} を渡すと地図のピクセルで指定できる。額縁と三人称は対象外。
     *
     * @param predicate 重ねる地図の条件。セッション ID は他のアイテムと同じくスタックから取る
     */
    public static void map(Predicate<ItemStack> predicate, ItemSurfaceSpec spec) {
        WebItemRendererHelper.registerMapOverlay(predicate, spec);
    }

    /** 空中に浮かぶ画面を出す（同じ {@code id} は置き換える）。ワールドを抜けると消える。表示専用。 */
    public static void hologram(String id, Hologram hologram) {
        HologramRenderer.show(id, hologram);
    }

    public static void removeHologram(String id) {
        HologramRenderer.hide(id);
    }

    public static void clearHolograms() {
        HologramRenderer.clear();
    }

    /** 親モデルを描く。この時点で display の変換は掛かっているので、さらに掛け直さず面だけを出す。 */
    private static void renderBase(ResourceLocation baseModel, ItemStack stack, PoseStack poseStack,
                                   MultiBufferSource buffer, int light, int overlay) {
        Minecraft mc = Minecraft.getInstance();
        BakedModel model = mc.getModelManager().getModel(ModelResourceLocation.standalone(baseModel));
        ItemRenderer itemRenderer = mc.getItemRenderer();
        var consumer = ItemRenderer.getFoilBufferDirect(buffer, ItemBlockRenderTypes.getRenderType(stack, true), true, stack.hasFoil());
        itemRenderer.renderModelLists(model, stack, light, overlay, poseStack, consumer);
        // 親モデルの手前の面に画面を重ねる（厚み 1/16 の半分より少し前）
        poseStack.translate(0, 0, 0.032f);
    }
}
