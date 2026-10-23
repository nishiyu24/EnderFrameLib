package com.nishiyu.enderframelib.server.state;

import com.nishiyu.enderframelib.api.WebDocument;
import com.nishiyu.enderframelib.api.WebSessionHandle;
import com.nishiyu.enderframelib.api.state.UIState;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * 後方互換性アダプタ (H4):
 * {@link UIState} を既存の {@link WebSessionHandle} として透過的に扱えるようにするアダプタ。
 *
 * <p>既存の Mod コードやスクリプトが {@link WebSessionHandle} の {@code patchText} 等を呼び出している場合でも、
 * 本アダプタを介することで、内部で自動的に {@link UIState#set(String, Object)} へルーティングされ、
 * 軽量な {@link com.nishiyu.enderframelib.network.StateDeltaS2CPacket} による状態配信が行われます。</p>
 */
@ApiStatus.Internal
public class UIStateSessionAdapter implements WebSessionHandle {

    private final UIState state;
    private int width = 854;
    private int height = 480;

    public UIStateSessionAdapter(@NotNull UIState state) {
        this.state = Objects.requireNonNull(state, "state cannot be null");
    }

    /**
     * ラップしている {@link UIState} を返します。
     */
    @NotNull
    public UIState getUIState() {
        return state;
    }

    @Override
    public String getSessionId() {
        return state.instanceId();
    }

    @Override
    public int getWidth() {
        return width;
    }

    @Override
    public int getHeight() {
        return height;
    }

    @Override
    @Nullable
    public WebDocument getDocument() {
        // Headless State ではサーバー上に DOM ツリーを保持しないため null
        return null;
    }

    @Override
    public void updateSize(int width, int height) {
        this.width = width;
        this.height = height;
    }

    @Override
    public void loadHtml(String html) {
        // Headless State では HTML の再パースは行わない
    }

    @Override
    public void loadWithCss(String html, String css) {
        // Headless State では HTML/CSS の再パースは行わない
    }

    @Override
    public void reload() {
        state.flush();
    }

    @Override
    public void clear() {
        try {
            state.close();
        } catch (Exception ignored) {
        }
    }

    @Override
    public void requestReRender() {
        state.flush();
    }

    @Override
    @Deprecated
    public void patchText(String elementId, String newText) {
        if (elementId != null) {
            state.set(elementId, newText != null ? newText : "");
        }
    }

    @Override
    @Deprecated
    public void patchAttribute(String elementId, String attrName, String attrValue, boolean remove) {
        if (elementId == null || attrName == null) return;
        String key = "value".equalsIgnoreCase(attrName) ? elementId : elementId + "@" + attrName;
        state.set(key, remove ? null : attrValue);
    }

    @Override
    @Deprecated
    public void patchStyle(String elementId, String property, String value) {
        if (elementId != null && property != null) {
            state.set(elementId + ":" + property, value);
        }
    }

    @Override
    @Deprecated
    public void patchClass(String elementId, String className, String mode) {
        if (elementId != null && className != null) {
            state.set(elementId + ":class:" + className, mode != null ? mode : "add");
        }
    }

    @Override
    @Deprecated
    public void patchInnerHtml(String elementId, String htmlFragment) {
        if (elementId != null) {
            state.set(elementId, htmlFragment != null ? htmlFragment : "");
        }
    }

    @Override
    public @NotNull UIState asUIState() {
        return state;
    }
}
