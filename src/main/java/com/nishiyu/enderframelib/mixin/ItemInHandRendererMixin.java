package com.nishiyu.enderframelib.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nishiyu.enderframelib.client.renderer.WebItemRendererHelper;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 一人称で開いた地図（{@code renderMap}）の地図の上に、Web セッションを持つ地図なら Web 画面を重ねる。 */
@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererMixin {

    @Inject(method = "renderMap", at = @At("TAIL"))
    private void enderframelib$renderWebOnMap(PoseStack poseStack, MultiBufferSource buffer, int packedLight,
                                              ItemStack stack, CallbackInfo ci) {
        WebItemRendererHelper.renderWebOnMap(stack, poseStack, buffer, packedLight);
    }
}
