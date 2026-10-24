package com.nishiyu.enderframelib.client.gui;

import com.nishiyu.enderframelib.core.paint.model.*;

import com.nishiyu.enderframelib.EnderFrameLib;
import com.mojang.blaze3d.vertex.PoseStack;
import com.nishiyu.enderframelib.client.ClientScreenInteractionManager;
import com.nishiyu.enderframelib.client.ClientWebManager;
import com.nishiyu.enderframelib.client.ReactiveStateStore;
import com.nishiyu.enderframelib.client.SessionContext;
import com.nishiyu.enderframelib.client.renderer.SurfaceRenderer;
import com.nishiyu.enderframelib.client.renderer.SurfaceStyle;
import com.nishiyu.enderframelib.client.renderer.DisplayItemDrawer;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.glfw.GLFW;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

public class AbstractEnderScreen extends Screen implements EnderScreenHost {
    public static final float DEFAULT_VIRTUAL_WIDTH = 854.0f;
    public static final float DEFAULT_VIRTUAL_HEIGHT = 480.0f;

    protected final String sessionId;
    public final ReactiveStateStore state;
    protected final EnderScreenDelegate uiSupport;

    protected int virtualWidth;
    protected int virtualHeight;
    protected boolean fullscreenResponsive = false;
    protected float viewportMarginRatio = 0.94f;
    protected float guiScaleFactor = 1.0f;
    protected float offsetX = 0.0f;
    protected float offsetY = 0.0f;
    protected EditBox hiddenEditBox;
    protected String hoveredId = "";
    private boolean isLocalHtmlSession = false;

    protected boolean devToolsEnabled = false;
    protected ResourceLocation htmlResourceLocation = null;

    public AbstractEnderScreen(Component title) {
        this(title, "local_screen_" + UUID.randomUUID(), 0, 0);
    }

    public AbstractEnderScreen(Component title, ResourceLocation htmlResource) {
        this(title, "local_screen_" + UUID.randomUUID(), 0, 0);
        this.htmlResourceLocation = htmlResource;
    }

    public AbstractEnderScreen(Component title, String sessionId) {
        this(title, sessionId, (int) DEFAULT_VIRTUAL_WIDTH, (int) DEFAULT_VIRTUAL_HEIGHT);
    }

    public AbstractEnderScreen(Component title, String sessionId, int virtualWidth, int virtualHeight) {
        super(title);
        this.sessionId = sessionId;
        this.state = new ReactiveStateStore(sessionId);
        this.uiSupport = new EnderScreenDelegate(sessionId, state);
        if (virtualWidth <= 0 || virtualHeight <= 0) {
            this.fullscreenResponsive = true;
            this.virtualWidth = (int) DEFAULT_VIRTUAL_WIDTH;
            this.virtualHeight = (int) DEFAULT_VIRTUAL_HEIGHT;
            this.viewportMarginRatio = 1.0f;
        } else {
            this.virtualWidth = virtualWidth;
            this.virtualHeight = virtualHeight;
        }
    }

    public void setViewportMarginRatio(float ratio) {
        this.viewportMarginRatio = Math.max(0.1f, Math.min(1.0f, ratio));
    }

    public void setHtmlResource(ResourceLocation location) {
        this.htmlResourceLocation = location;
    }

    /**
     * HTML 繝ｪ繧ｽ繝ｼ繧ｹ繧定ｪｭ縺ｿ霎ｼ繧縲りｦ九▽縺九ｉ縺ｪ縺代ｌ縺ｰ遨ｺ縺ｮ {@link Optional} 繧定ｿ斐☆縲・
     *
     * <p>莉･蜑阪・繧ｨ繝ｩ繝ｼ繧・HTML 譁・ｭ怜・縺ｧ霑斐＠縺ｦ縺翫ｊ縲∝他縺ｳ蜃ｺ縺怜・縺・
     * {@code contains("Failed to load HTML resource:")} 縺ｧ驛ｨ蛻・枚蟄怜・辣ｧ蜷医＠縺ｦ
     * 螟ｱ謨励ｒ蛻､螳壹＠縺ｦ縺・◆縲よｭ｣隕上・ HTML 縺後◎縺ｮ譁・ｒ蜷ｫ繧縺�縺代〒螟ｱ謨玲桶縺・↓縺ｪ繧九◆繧√・
     * 謌仙凄縺ｯ蝙九〒陦ｨ縺吶り｡ｨ遉ｺ逕ｨ縺ｮ繧ｨ繝ｩ繝ｼ HTML 縺悟ｿ・ｦ√↑蝣ｴ蜷医・
     * {@link #errorHtml(ResourceLocation)} 繧剃ｽｿ縺・・
     */
    public static Optional<String> findHtmlResource(ResourceLocation loc) {
        try {
            Optional<Resource> res = Minecraft.getInstance().getResourceManager().getResource(loc);
            if (res.isPresent()) {
                try (InputStream is = res.get().open()) {
                    return Optional.of(new String(is.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        } catch (Exception e) {
            EnderFrameLib.LOGGER.debug("Resource manager could not read HTML resource {}: {}", loc, e.getMessage());
        }
        // 繝ｪ繧ｽ繝ｼ繧ｹ繝代ャ繧ｯ縺ｫ辟｡縺・�ｴ蜷医・ jar 蜀・・ assets 縺九ｉ逶ｴ謗･隱ｭ繧縲・
        try (InputStream is = AbstractEnderScreen.class.getResourceAsStream("/assets/" + loc.getNamespace() + "/" + loc.getPath())) {
            if (is != null) {
                return Optional.of(new String(is.readAllBytes(), StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            EnderFrameLib.LOGGER.debug("Classpath fallback could not read HTML resource {}: {}", loc, e.getMessage());
        }
        EnderFrameLib.LOGGER.warn("HTML resource not found: {}", loc);
        return Optional.empty();
    }

    /** 隱ｭ縺ｿ霎ｼ縺ｿ縺ｫ螟ｱ謨励＠縺溘％縺ｨ繧堤判髱｢荳翫↓遉ｺ縺吶◆繧√・ HTML縲・*/
    public static String errorHtml(ResourceLocation loc) {
        return "<html><body><div style='padding:20px;color:#f38ba8;'>Failed to load HTML resource: " + loc + "</div></body></html>";
    }

    /**
     * 謖・ｮ壹＆繧後◆繝ｪ繧ｽ繝ｼ繧ｹ菴咲ｽｮ・・@link ResourceLocation}・峨°繧・HTML 繝輔ぃ繧､繝ｫ縺ｮ蜀・ｮｹ繧呈枚蟄怜・縺ｨ縺励※隱ｭ縺ｿ霎ｼ繧縲・
     * <p>
     * 繝ｪ繧ｽ繝ｼ繧ｹ縺悟ｭ伜惠縺励↑縺・√∪縺溘・隱ｭ縺ｿ霎ｼ縺ｿ縺ｫ螟ｱ謨励＠縺溷�ｴ蜷医・ {@link #errorHtml(ResourceLocation)}
     * 縺ｫ繧医ｋ繧ｨ繝ｩ繝ｼ陦ｨ遉ｺ逕ｨ HTML 繧定ｿ斐☆縲ら判髱｢縺ｮ蛻晄悄蛹悶ｄ繝ｪ繝ｭ繝ｼ繝画凾縺ｫ繧｢繧ｻ繝・ヨ繧偵Ο繝ｼ繝峨☆繧九◆繧√・蟾ｮ縺苓ｾｼ縺ｿ蜿｣縲・
     *
     * @param loc 隱ｭ縺ｿ霎ｼ繧 HTML 繝ｪ繧ｽ繝ｼ繧ｹ縺ｮ菴咲ｽｮ
     * @return 隱ｭ縺ｿ霎ｼ繧薙□ HTML 譁・ｭ怜・縲√∪縺溘・繝輔か繝ｼ繝ｫ繝舌ャ繧ｯ逕ｨ縺ｮ繧ｨ繝ｩ繝ｼ HTML
     */
    public static String loadHtmlResource(ResourceLocation loc) {
        return findHtmlResource(loc).orElseGet(() -> errorHtml(loc));
    }

    @Override
    protected void init() {
        super.init();
        this.hiddenEditBox = this.uiSupport.createHiddenEditBox(this.font, this::notifyInputChanged);
        this.addWidget(this.hiddenEditBox);
        if (resizing && resizeWebUI()) return;
        reloadWebUI(false);
    }

    /** {@link #resize} から呼ばれた {@link #init()} の間だけ true。 */
    private boolean resizing = false;

    /**
     * ウィンドウの大きさが変わると Minecraft は {@link #init()} を呼び直す。そのたびに文書を読み直すと、
     * つかんで広げている間は毎フレーム HTML の解析からやり直すうえ、スクロール・入力中の値・アニメーションも消える。
     * ここでは文書を保ったまま、ビューポートの大きさだけを変える。
     */
    @Override
    public void resize(@NotNull Minecraft minecraft, int width, int height) {
        resizing = true;
        try {
            super.resize(minecraft, width, height);
        } finally {
            resizing = false;
        }
    }

    /** 読み込み済みの文書があれば大きさだけを変えて true。無ければ false（通常の読み込みに任せる）。 */
    private boolean resizeWebUI() {
        SessionContext session = ClientWebManager.getSession(this.sessionId);
        if (session == null || session.document() == null) return false;
        updateViewportTransform();
        session.requestResize(this.virtualWidth, this.virtualHeight);
        return true;
    }

    private void notifyInputChanged(String baseId, String newText) {
        onInputChanged(baseId, newText);
        onWebInputChanged(baseId, newText);
    }

    public void reloadWebUI() {
        reloadWebUI(true);
    }

    protected void reloadWebUI(boolean forceReload) {
        this.uiSupport.clearHandlers();
        this.state.clearBindings();
        registerWebActions();
        updateViewportTransform();

        this.isLocalHtmlSession = ClientWebManager.initOrBindSession(
                this.sessionId,
                buildInitialHtml(),
                this.htmlResourceLocation,
                this.virtualWidth,
                this.virtualHeight,
                forceReload
        );
        this.state.forceSync();
    }

    protected String buildInitialHtml() {
        return null;
    }

    protected void registerWebActions() {}

    protected void onAction(String actionName, Runnable handler) {
        this.uiSupport.onAction(actionName, handler);
    }

    protected void onActionPrefix(String prefix, Consumer<String> payloadHandler) {
        this.uiSupport.onActionPrefix(prefix, payloadHandler);
    }

    @Override
    public String getWebSessionId() {
        return this.sessionId;
    }

    @Override
    public EnderScreenDelegate getUiDelegate() {
        return this.uiSupport;
    }

    @Override
    public Screen getWebScreen() {
        return this;
    }

    protected void onInputChanged(String elementId, String newText) {}
    protected void onWebAction(String action, DisplayItem clickedElement, int mouseButton) {}
    protected void onWebInputChanged(String elementId, String newText) {}
    protected void onWebInputSubmitted(String elementId, String text) {}
    protected void onWebDrop(String draggedId, String dropTargetId, String dropAction) {}

    protected void updateViewportTransform() {
        this.uiSupport.updateViewport(this.width, this.height, this.virtualWidth, this.virtualHeight, this.fullscreenResponsive, this.viewportMarginRatio);
        this.virtualWidth = this.uiSupport.getVirtualWidth();
        this.virtualHeight = this.uiSupport.getVirtualHeight();
        this.guiScaleFactor = this.uiSupport.getGuiScaleFactor();
        this.offsetX = this.uiSupport.getOffsetX();
        this.offsetY = this.uiSupport.getOffsetY();
    }

    protected double toWebX(double mouseX) {
        return this.uiSupport.toWebX(mouseX);
    }

    protected double toWebY(double mouseY) {
        return this.uiSupport.toWebY(mouseY);
    }

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        if (!fullscreenResponsive || viewportMarginRatio < 1.0f) {
            this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        }
        this.state.syncToDom();
        ClientWebManager.processRenderQueue();
        if (!isLocalHtmlSession) {
            ClientScreenInteractionManager.ensureSynced(sessionId);
        }
        updateViewportTransform();

        double webX = toWebX(mouseX);
        double webY = toWebY(mouseY);
        if (this.uiSupport.isSelectDropdownHovered(webX, webY)) {
            hoveredId = this.uiSupport.selectWidget().openSelectBaseId();
        } else {
            hoveredId = ClientScreenInteractionManager.handleHover(sessionId, webX, webY);
        }

        List<DisplayItem> elements = ClientWebManager.getSession(sessionId) != null
                ? ClientWebManager.getSession(sessionId).elements()
                : java.util.Collections.emptyList();
        if (elements != null && !elements.isEmpty()) {
            PoseStack poseStack = guiGraphics.pose();
            poseStack.pushPose();
            poseStack.translate(offsetX, offsetY, 0);
            poseStack.scale(guiScaleFactor, guiScaleFactor, 1.0f);

            var bufferSource = Minecraft.getInstance().renderBuffers().bufferSource();
            int logicW = (int) Math.ceil(virtualWidth / DisplayItemDrawer.RESOLUTION);
            int logicH = (int) Math.ceil(virtualHeight / DisplayItemDrawer.RESOLUTION);

            SurfaceRenderer.draw(sessionId, elements, logicW, logicH,
                    SurfaceStyle.gui(true).withHovered(hoveredId), poseStack, bufferSource);
            bufferSource.endBatch();

            this.uiSupport.renderOverlays(guiGraphics, this.font, elements, (int) webX, (int) webY, virtualWidth, virtualHeight, devToolsEnabled);

            poseStack.popPose();
        }
    }

    protected void renderDevToolsInspector(GuiGraphics guiGraphics, List<DisplayItem> elements, int webX, int webY) {
        this.uiSupport.renderDevToolsInspector(guiGraphics, this.font, elements, webX, webY, virtualWidth, virtualHeight);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (this.uiSupport.handleMouseClicked(this, this.font, toWebX(mouseX), toWebY(mouseY), button, this::notifyInputChanged, this::onWebAction)) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    protected boolean dispatchRoutedAction(String action) {
        return this.uiSupport.dispatchRoutedAction(action);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (this.uiSupport.handleMouseDragged(toWebX(mouseX), toWebY(mouseY), dragX, dragY, guiScaleFactor, this::notifyInputChanged)) {
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        this.uiSupport.handleMouseReleased(toWebX(mouseX), toWebY(mouseY), button, this::onWebDrop);
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (this.uiSupport.handleMouseScrolled(toWebX(mouseX), toWebY(mouseY), scrollX, scrollY)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_F12) {
            this.devToolsEnabled = !this.devToolsEnabled;
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_F5) {
            reloadWebUI(true);
            return true;
        }
        if (this.uiSupport.handleKeyPressed(this, this.font, keyCode, scanCode, modifiers, true, this::notifyInputChanged, this::onWebInputSubmitted, this::onWebAction)) {
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        if (this.uiSupport.handleKeyReleased(keyCode, modifiers)) {
            return true;
        }
        return super.keyReleased(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (this.uiSupport.handleCharTyped(codePoint, modifiers, this::notifyInputChanged)) {
            return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public void onClose() {
        this.uiSupport.onClose();
        SessionContext session = ClientWebManager.getSession(this.sessionId);
        if (isLocalHtmlSession && (session == null || !session.isServerManaged())) {
            ClientWebManager.clearSession(this.sessionId);
        }
        super.onClose();
    }

    @Override
    public void removed() {
        this.uiSupport.onScreenRemoved();
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}