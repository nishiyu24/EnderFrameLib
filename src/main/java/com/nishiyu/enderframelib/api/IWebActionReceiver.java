package com.nishiyu.enderframelib.api;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;

/**
 * AbstractContainerMenu または BlockEntity に実装することで、
 * クライアント画面の <button onclick="server:action_name"> や sendServerAction(...) を
 * カスタムパケット定義なしで直接受信するためのインターフェース
 */
@org.jetbrains.annotations.ApiStatus.AvailableSince("1.0")
public interface IWebActionReceiver {

    /**
     * クライアントからサーバーアクションが送信された際にサーバーメインスレッドで呼び出される
     *
     * @param player  送信元のプレイヤー
     * @param action  アクション名（"server:" プレフィックスを除いた名前）
     * @param value   文字列ペイロード（"server:action:value" や input 値など）
     * @param payload 追加の CompoundTag データ
     */
    void onWebServerAction(ServerPlayer player, String action, String value, CompoundTag payload);
}