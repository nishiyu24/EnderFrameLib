package com.nishiyu.enderframelib.client.gui;

import com.nishiyu.enderframelib.api.config.EnderConfigManager;
import com.nishiyu.enderframelib.common.UIParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

import java.util.Objects;

/**
 * {@link EnderConfigManager} に登録された設定モデルを、Web標準HTML5フォームとして描画・編集するConfig画面。
 * NeoForgeの {@code IConfigScreenFactory} に1行で登録でき、カスタムHTMLテンプレートやリアルタイム検索にも対応する。
 */
public class EnderConfigScreen extends AbstractWebScreen {

    private final Screen parentScreen;
    private final String modId;
    private final EnderConfigManager.RegisteredConfig config;
    private String currentSearchFilter = "";

    public EnderConfigScreen(Screen parentScreen, String modId) {
        super(
                Component.literal(resolveTitle(modId)),
                "ender_config_" + modId,
                0, 0
        );
        this.parentScreen = parentScreen;
        this.modId = modId;
        this.config = EnderConfigManager.getConfig(modId);
    }

    private static String resolveTitle(String modId) {
        EnderConfigManager.RegisteredConfig reg = EnderConfigManager.getConfig(modId);
        if (reg != null && reg.configMeta != null && !reg.configMeta.title().isEmpty()) {
            return reg.configMeta.title();
        }
        return modId + " Settings";
    }

    public static void registerModConfigScreen(ModContainer container) {
        container.registerExtensionPoint(
                IConfigScreenFactory.class,
                (mc, parent) -> new EnderConfigScreen(parent, container.getModId())
        );
    }

    public static void registerForMod(String modId) {
        ModList.get().getModContainerById(modId).ifPresent(EnderConfigScreen::registerModConfigScreen);
    }

    @Override
    protected String buildInitialHtml() {
        if (this.config == null) {
            return "<html><body style=\"padding:24px;color:#f38ba8;\">No @EnderConfig registered for mod: " + modId + "</body></html>";
        }
        for (EnderConfigManager.FieldSpec spec : this.config.fields) {
            this.state.set(spec.key(), spec.getCurrentValueAsString());
        }
        if (this.config.configMeta != null && !this.config.configMeta.customTemplate().isEmpty()) {
            String customHtml = UIParser.resourceTextResolver != null
                    ? UIParser.resourceTextResolver.apply(this.config.configMeta.customTemplate())
                    : null;
            if (customHtml != null && !customHtml.isEmpty()) {
                return customHtml;
            }
        }
        return EnderConfigManager.generateStandardHtml5Config(this.config, this.currentSearchFilter);
    }

    @Override
    protected void registerWebActions() {
        onAction("save_config", this::saveAndClose);
        onAction("cancel_config", this::cancelAndClose);
        onAction("reset_all", this::resetAllFields);
        onActionPrefix("reset_field:", this::resetSingleField);
    }

    @Override
    protected void onWebInputChanged(String elementId, String newText) {
        if ("cfg_search".equals(elementId)) {
            String clean = newText != null ? newText : "";
            if (!Objects.equals(this.currentSearchFilter, clean)) {
                this.currentSearchFilter = clean;
                reloadWebUI(true);
                focusWebInput("cfg_search", this.currentSearchFilter);
            }
        }
    }

    private static String formatDefaultValue(EnderConfigManager.FieldSpec spec) {
        Object defVal = spec.defaultValue();
        if (spec.annotation().colorPicker() && defVal instanceof Integer intCol) {
            return String.format("#%06X", (intCol & 0xFFFFFF));
        }
        return defVal != null ? String.valueOf(defVal) : "";
    }

    private void resetSingleField(String fieldKey) {
        if (this.config == null || fieldKey == null) return;
        for (EnderConfigManager.FieldSpec spec : this.config.fields) {
            if (spec.key().equals(fieldKey)) {
                String defStr = formatDefaultValue(spec);
                this.state.set(spec.key(), defStr);
                setInputValue("field_" + spec.key(), defStr);
                break;
            }
        }
    }

    private void resetAllFields() {
        if (this.config == null) return;
        for (EnderConfigManager.FieldSpec spec : this.config.fields) {
            this.state.set(spec.key(), formatDefaultValue(spec));
        }
        reloadWebUI(true);
    }

    private void saveAndClose() {
        if (this.config != null) {
            for (EnderConfigManager.FieldSpec spec : this.config.fields) {
                String val = this.state.get(spec.key());
                if (val != null && !val.isEmpty()) {
                    spec.setFromString(val);
                }
            }
            EnderConfigManager.save(this.modId);
        }
        Minecraft.getInstance().setScreen(this.parentScreen);
    }

    private void cancelAndClose() {
        if (this.config != null) {
            EnderConfigManager.load(this.modId);
        }
        Minecraft.getInstance().setScreen(this.parentScreen);
    }

    @Override
    public void onClose() {
        super.onClose();
        if (Minecraft.getInstance().screen == null && this.parentScreen != null) {
            Minecraft.getInstance().setScreen(this.parentScreen);
        }
    }
}