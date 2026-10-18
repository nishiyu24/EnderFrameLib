package com.nishiyu.enderframelib.client.gui;

import com.nishiyu.enderframelib.network.WebActionC2SPacket;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * クライアント側のアクションルーティング（完全一致・プレフィックス・サーバー送信）を管理するクラス。
 */
public class WebActionRouter {

    private final String sessionId;
    private final Map<String, Runnable> exactActionHandlers = new HashMap<>();
    private final Map<String, Consumer<String>> prefixActionHandlers = new LinkedHashMap<>();

    public WebActionRouter(String sessionId) {
        this.sessionId = sessionId;
    }

    public void clear() {
        exactActionHandlers.clear();
        prefixActionHandlers.clear();
    }

    public void onAction(String actionName, Runnable handler) {
        exactActionHandlers.put(actionName, handler);
    }

    public void onActionPrefix(String prefix, Consumer<String> payloadHandler) {
        prefixActionHandlers.put(prefix, payloadHandler);
    }

    public void sendServerAction(String actionName, Object value) {
        CompoundTag tag = new CompoundTag();
        tag.putString("ElementId", "");
        tag.putString("EventName", "rpc");
        tag.putString("ActionName", actionName != null ? actionName : "");
        tag.putString("Value", value != null ? String.valueOf(value) : "");
        PacketDistributor.sendToServer(new WebActionC2SPacket(this.sessionId, "ui_event", tag));
    }

    public boolean dispatch(String action) {
        if (action == null || action.isEmpty()) return false;

        if (action.startsWith("server:")) {
            String rest = action.substring(7);
            if (rest.contains(":")) {
                String[] sp = rest.split(":", 2);
                sendServerAction(sp[0], sp[1]);
            } else {
                sendServerAction(rest, "");
            }
            return true;
        }

        Runnable handler = exactActionHandlers.get(action);
        if (handler != null) {
            handler.run();
            return true;
        }

        for (Map.Entry<String, Consumer<String>> entry : prefixActionHandlers.entrySet()) {
            if (action.startsWith(entry.getKey())) {
                entry.getValue().accept(action.substring(entry.getKey().length()));
                return true;
            }
        }

        return false;
    }
}
