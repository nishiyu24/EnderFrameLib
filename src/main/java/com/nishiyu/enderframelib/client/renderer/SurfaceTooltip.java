package com.nishiyu.enderframelib.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import org.joml.Matrix4f;

import java.util.regex.Pattern;

/**
 * ホバー中の要素の {@code title}（無ければアイテム・液体の名前）を、面の上にツールチップとして重ねる。
 * ブラウザの OS のツールチップと同じく、ページの中身ではなくホストの飾りなので、コアの描いた板の上に Minecraft の書体で描く。
 */
final class SurfaceTooltip {

    private static final Pattern LINE_BREAK = Pattern.compile("\\\\n|\\n");

    private SurfaceTooltip() {}

    /** 要素のツールチップの文。出さないなら null。 */
    static String textOf(DisplayItem el) {
        String title = el.hints().title();
        if (title != null) return "false".equalsIgnoreCase(title) || title.isEmpty() ? null : title;
        if (el.type() != ElementType.IMAGE || el.text() == null) return null;
        try {
            if (el.text().startsWith("item:")) {
                Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(el.text().substring(5).split("\\*")[0].trim()));
                return item != Items.AIR ? new ItemStack(item).getHoverName().getString() : null;
            }
            if (el.text().startsWith("fluid:")) {
                Fluid fluid = BuiltInRegistries.FLUID.get(ResourceLocation.parse(el.text().substring(6).trim()));
                return fluid != Fluids.EMPTY ? fluid.getFluidType().getDescription().getString() : null;
            }
        } catch (RuntimeException ignored) {
            // 名前の形が不正なら出さない
        }
        return null;
    }

    /**
     * {@code target}（面の座標）の上、入らなければ下に描く。面（{@code maxW × maxH}）の外へは出さない。
     * {@code poseStack} は面の左上を原点に面の 1 px が 1 単位。
     */
    static void draw(PoseStack poseStack, MultiBufferSource buffer, Font font, DisplayItem target, String text,
                     float maxW, float maxH, float z, int packedLight) {
        String[] lines = LINE_BREAK.split(text);
        int maxLineW = 0;
        for (String line : lines) maxLineW = Math.max(maxLineW, font.width(line));
        float boxW = maxLineW + 10, boxH = lines.length * 11 + 8;
        float tipX = target.x() + (target.width() - boxW) / 2.0f;
        float tipY = target.y() - boxH - 4.0f;
        if (tipY < 2.0f) tipY = target.y() + target.height() + 4.0f;
        // 面よりツールチップが大きいと範囲が逆転する（Math.clamp は例外）。その場合は左上に寄せる
        tipX = Math.max(2.0f, Math.min(tipX, maxW - boxW - 2.0f));
        tipY = Math.max(2.0f, Math.min(tipY, maxH - boxH - 2.0f));
        poseStack.pushPose();
        try {
            Matrix4f mat = poseStack.last().pose();
            VertexConsumer quads = buffer.getBuffer(RenderType.gui());
            quad(quads, mat, tipX - 1, tipY - 1, tipX + boxW + 1, tipY + boxH + 1, 0xB35000FF, z);
            quad(quads, mat, tipX, tipY, tipX + boxW, tipY + boxH, 0xF0100010, z + 0.01f);
            poseStack.translate(tipX + 5.0f, tipY + 4.0f, z + 0.02f);
            for (int i = 0; i < lines.length; i++) {
                font.drawInBatch(lines[i], 0, i * 11, 0xFFFFFFFF, true, poseStack.last().pose(), buffer,
                        Font.DisplayMode.NORMAL, 0, packedLight);
            }
        } finally {
            poseStack.popPose();
        }
    }

    private static void quad(VertexConsumer b, Matrix4f m, float x0, float y0, float x1, float y1, int argb, float z) {
        b.addVertex(m, x0, y0, z).setColor(argb);
        b.addVertex(m, x0, y1, z).setColor(argb);
        b.addVertex(m, x1, y1, z).setColor(argb);
        b.addVertex(m, x1, y0, z).setColor(argb);
    }
}
