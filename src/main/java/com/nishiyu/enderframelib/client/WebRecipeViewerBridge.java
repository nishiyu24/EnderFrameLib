package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.core.paint.model.*;

import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.client.gui.AbstractEnderContainerScreen;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * JEI / REI / EMI などのレシピビューアMODと、Web標準DOM要素をシームレスに統合するブリッジヘルパー。
 * <ul>
 *   <li><b>Exclusion Zones (除外領域)</b>: {@code role="complementary"}, {@code role="dialog"}, {@code <aside>}, {@code <dialog open>}, または {@code data-jei-exclude="true"} を持つDOM要素のスクリーン矩形を自動収集。</li>
 *   <li><b>Ghost Slot / Drag &amp; Drop Target</b>: {@code data-dropzone="item"} または {@code data-ghost-slot="true"} を持つDOM要素へのアイテム/液体ドラッグ＆ドロップを受け入れ。</li>
 *   <li><b>Ingredient Under Mouse</b>: {@code data-item}, {@code data-fluid}, または {@code item:...} / {@code fluid:...} 画像要素から、ホバー中の {@link ItemStack} / {@link FluidStack} を解決。</li>
 * </ul>
 */
public class WebRecipeViewerBridge {

    public record GhostDropTarget(
            String elementId,
            Rect2i screenBounds,
            BiConsumer<String, Object> onDropHandler
    ) {
        public void accept(Object ingredient) {
            if (onDropHandler != null) {
                onDropHandler.accept(elementId, ingredient);
            }
        }
    }

    /**
     * コンテナ画面内の全除外領域（メインビューポート矩形 + DOM内の {@code <aside>}, {@code <dialog open>},
     * {@code role="complementary"}, {@code data-jei-exclude="true"} 要素）をスクリーン座標系の {@link Rect2i} リストとして返す。
     */
    public static List<Rect2i> getExclusionZones(AbstractEnderContainerScreen<?> screen) {
        List<Rect2i> zones = new ArrayList<>();
        if (screen == null) return zones;

        String sessionId = screen.getWebSessionId();
        SessionContext session = ClientWebManager.getSession(sessionId);
        HtmlNode vRoot = session != null ? session.rootNode() : null;
        if (vRoot == null) {
            zones.add(screen.getWebViewportBounds());
            return zones;
        }

        List<String> explicitExcludeIds = new ArrayList<>();
        collectExclusionNodeIds(vRoot, explicitExcludeIds);

        if (explicitExcludeIds.isEmpty()) {
            zones.add(screen.getWebViewportBounds());
            return zones;
        }

        for (DisplayItem el : session.elements()) {
            if (el.type() == ElementType.NODE && explicitExcludeIds.contains(el.id()) && el.width() > 0 && el.height() > 0) {
                zones.add(screen.toScreenRect(el.x(), el.y(), el.width(), el.height()));
            }
        }

        if (zones.isEmpty()) {
            zones.add(screen.getWebViewportBounds());
        }
        return zones;
    }

    private static void collectExclusionNodeIds(HtmlNode node, List<String> out) {
        if (node == null) return;
        String role = node.attrs.getOrDefault("role", "");
        boolean isExclude = "true".equalsIgnoreCase(node.attrs.get("data-jei-exclude"))
                || "aside".equalsIgnoreCase(node.tag)
                || ("dialog".equalsIgnoreCase(node.tag) && node.attrs.containsKey("open"))
                || "complementary".equalsIgnoreCase(role)
                || "dialog".equalsIgnoreCase(role);
        if (isExclude && !node.getId().isEmpty()) {
            out.add(node.getId());
        }
        for (HtmlNode child : node.children) {
            collectExclusionNodeIds(child, out);
        }
    }

    /**
     * レシピビューア（JEI/EMI/REI）からアイテムや液体をドラッグ＆ドロップ可能なゴーストスロットターゲット一覧を返す。
     * Web標準の {@code data-dropzone="item"} または {@code data-ghost-slot="true"} 属性を持つ要素を自動検出する。
     */
    public static List<GhostDropTarget> getGhostTargets(AbstractEnderContainerScreen<?> screen) {
        List<GhostDropTarget> targets = new ArrayList<>();
        if (screen == null) return targets;

        String sessionId = screen.getWebSessionId();
        SessionContext session = ClientWebManager.getSession(sessionId);
        HtmlNode vRoot = session != null ? session.rootNode() : null;
        if (vRoot == null) return targets;

        for (DisplayItem el : session.elements()) {
            if (el.type() != ElementType.NODE || el.width() <= 0 || el.height() <= 0) continue;
            HtmlNode domNode = vRoot.getNodeById(el.id());
            if (domNode == null) continue;

            boolean isGhostTarget = "true".equalsIgnoreCase(domNode.attrs.get("data-ghost-slot"))
                    || domNode.attrs.containsKey("data-dropzone")
                    || domNode.attrs.containsKey("data-ghost-item");

            if (isGhostTarget) {
                Rect2i bounds = screen.toScreenRect(el.x(), el.y(), el.width(), el.height());
                targets.add(new GhostDropTarget(el.id(), bounds, screen::acceptGhostIngredientDrop));
            }
        }
        return targets;
    }

    /**
     * 指定したスクリーン座標にあるWeb要素から、レシピ検索対象の {@link ItemStack} または {@link FluidStack} を解決する。
     * {@code src="item:..."} だけでなく、任意のHTML要素の {@code data-item="minecraft:diamond"} や {@code data-fluid="minecraft:water"} にも対応。
     */
    public static Object resolveIngredientAt(String sessionId, double webX, double webY) {
        SessionContext session = ClientWebManager.getSession(sessionId);
        if (session == null) return null;
        SessionContext.UIHitResult hit = session.hitTest(webX, webY);
        if (hit == null || hit.element() == null) return null;

        DisplayItem el = hit.element();
        String text = el.text();
        if (text != null) {
            if (text.startsWith("item:")) {
                ItemStack stack = parseItemSpec(text.substring(5));
                if (!stack.isEmpty()) return stack;
            } else if (text.startsWith("fluid:")) {
                FluidStack fluid = parseFluidSpec(text.substring(6));
                if (!fluid.isEmpty()) return fluid;
            }
        }

        HtmlNode vRoot = session.rootNode();
        if (vRoot != null) {
            String baseId = ClientWebManager.getBaseNodeId(el.id());
            HtmlNode domNode = vRoot.getNodeById(baseId);
            while (domNode != null) {
                if (domNode.attrs.containsKey("data-item")) {
                    ItemStack stack = parseItemSpec(domNode.attrs.get("data-item"));
                    if (!stack.isEmpty()) return stack;
                }
                if (domNode.attrs.containsKey("data-ghost-item")) {
                    ItemStack stack = parseItemSpec(domNode.attrs.get("data-ghost-item"));
                    if (!stack.isEmpty()) return stack;
                }
                if (domNode.attrs.containsKey("data-fluid")) {
                    FluidStack fluid = parseFluidSpec(domNode.attrs.get("data-fluid"));
                    if (!fluid.isEmpty()) return fluid;
                }
                domNode = domNode.parent;
            }
        }
        return null;
    }

    public static ItemStack parseItemSpec(String rawSpec) {
        if (rawSpec == null || rawSpec.isBlank()) return ItemStack.EMPTY;
        try {
            String clean = rawSpec.split("\\*")[0].trim();
            Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(clean));
            if (item != Items.AIR) {
                return new ItemStack(item);
            }
        } catch (Exception ignored) {
        }
        return ItemStack.EMPTY;
    }

    public static FluidStack parseFluidSpec(String rawSpec) {
        if (rawSpec == null || rawSpec.isBlank()) return FluidStack.EMPTY;
        try {
            Fluid fluid = BuiltInRegistries.FLUID.get(ResourceLocation.parse(rawSpec.trim()));
            if (fluid != Fluids.EMPTY) {
                return new FluidStack(fluid, 1000);
            }
        } catch (Exception ignored) {
        }
        return FluidStack.EMPTY;
    }
}