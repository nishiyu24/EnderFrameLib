package com.nishiyu.enderframelib.api.template;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import org.jetbrains.annotations.ApiStatus;

/**
 * 手持ちの画面（タブレットなど）のひな形。セッション ID を {@code CustomData} の {@value #SESSION_KEY} に持ち、
 * クライアントの {@code Surfaces.item} がその面に Web 画面を描く。表示専用で、入力は受け付けない。
 *
 * <p>テンプレートで動かすときは、{@code EnderFrameAPI.showSurface} で持ち主に見せ、セッション ID にそのインスタンス ID を渡す。</p>
 *
 * <p>モデル JSON は {@code "parent": "builtin/entity"} にし、{@code display} で一人称・三人称・GUI の位置を決める。</p>
 */
@ApiStatus.AvailableSince("1.1")
public class EnderScreenItem extends Item {

    /** {@code CustomData} のキー。ライブラリ既定のセッション ID 抽出器と同じ。 */
    public static final String SESSION_KEY = "SessionId";

    public EnderScreenItem(Properties properties) {
        super(properties);
    }

    /** {@code sessionId} の画面を映すスタックを作る。 */
    public ItemStack createStack(String sessionId) {
        ItemStack stack = new ItemStack(this);
        CompoundTag tag = new CompoundTag();
        tag.putString(SESSION_KEY, sessionId);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return stack;
    }
}
