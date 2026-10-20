package com.nishiyu.enderframelib.api.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Manages configuration classes annotated with {@link EnderConfig} and {@link ConfigEntry}:
 * JSON load/save, remembering and restoring defaults, and binding to standard HTML5 forms.
 *
 * <h2>Client or server?</h2>
 * This system has <b>no client/server distinction</b>. Every registered config is stored in
 * the game instance's {@code config/} directory and is read where it is declared. On a client
 * connected to a dedicated server, values come from that client's own file: registering a
 * gameplay setting here silently makes it client-local. Use NeoForge's {@code ModConfigSpec}
 * with {@code ModConfig.Type.SERVER} for anything that must be authoritative.
 *
 * <h2>Threading</h2>
 * Values are written by reflection from the thread that applies a config change (the render
 * thread for the in-game config screen) and read from wherever the owning mod reads them.
 * Declare annotated fields {@code volatile} — the write path here is synchronized, but a
 * non-volatile field gives the reader no happens-before guarantee.
 */
public class EnderConfigManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("enderframelib-config");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Map<String, RegisteredConfig> CONFIGS = new ConcurrentHashMap<>();
    /** 登録・ロード・保存の read-modify-write を直列化する。 */
    private static final Object WRITE_LOCK = new Object();

    public record FieldSpec(
            String key,
            Field field,
            Object targetInstance,
            ConfigEntry annotation,
            Object defaultValue,
            String category,
            String label,
            String description
    ) {
        // setAccessible は register() で 1 度だけ行う（以前は 3 箇所で毎回呼んでいた）
        public Object getCurrentValue() {
            try {
                return field.get(targetInstance);
            } catch (Exception e) {
                return defaultValue;
            }
        }

        public String getCurrentValueAsString() {
            Object val = getCurrentValue();
            if (val == null) return "";
            if (annotation.colorPicker() && val instanceof Integer intCol) {
                return String.format("#%06X", (intCol & 0xFFFFFF));
            }
            return String.valueOf(val);
        }

        /**
         * 文字列から値を設定する。型が合わない・パターンに合わない場合は false を返す。
         * 失敗理由は呼び出し側（{@link #load} / HTML フォームの適用）が記録する。
         */
        public boolean setFromString(String rawValue) {
            if (rawValue == null) return false;
            synchronized (WRITE_LOCK) {
                return setFromStringLocked(rawValue);
            }
        }

        private boolean setFromStringLocked(String rawValue) {
            try {
                Class<?> type = field.getType();
                String trimmed = rawValue.trim();

                if (!annotation.pattern().isEmpty() && !trimmed.matches(annotation.pattern())) {
                    return false;
                }

                if (type == boolean.class || type == Boolean.class) {
                    boolean b = "true".equalsIgnoreCase(trimmed) || "1".equals(trimmed) || "on".equalsIgnoreCase(trimmed);
                    field.set(targetInstance, b);
                    return true;
                } else if (type == int.class || type == Integer.class) {
                    if (annotation.colorPicker() && trimmed.startsWith("#")) {
                        int rgb = Integer.parseInt(trimmed.substring(1), 16);
                        field.set(targetInstance, rgb);
                        return true;
                    }
                    int v = (int) Math.round(Double.parseDouble(trimmed));
                    if (annotation.min() != -Double.MAX_VALUE) v = Math.max((int) annotation.min(), v);
                    if (annotation.max() != Double.MAX_VALUE) v = Math.min((int) annotation.max(), v);
                    field.set(targetInstance, v);
                    return true;
                } else if (type == long.class || type == Long.class) {
                    long v = Math.round(Double.parseDouble(trimmed));
                    if (annotation.min() != -Double.MAX_VALUE) v = Math.max((long) annotation.min(), v);
                    if (annotation.max() != Double.MAX_VALUE) v = Math.min((long) annotation.max(), v);
                    field.set(targetInstance, v);
                    return true;
                } else if (type == float.class || type == Float.class) {
                    float v = Float.parseFloat(trimmed);
                    if (annotation.min() != -Double.MAX_VALUE) v = (float) Math.max(annotation.min(), v);
                    if (annotation.max() != Double.MAX_VALUE) v = (float) Math.min(annotation.max(), v);
                    field.set(targetInstance, v);
                    return true;
                } else if (type == double.class || type == Double.class) {
                    double v = Double.parseDouble(trimmed);
                    if (annotation.min() != -Double.MAX_VALUE) v = Math.max(annotation.min(), v);
                    if (annotation.max() != Double.MAX_VALUE) v = Math.min(annotation.max(), v);
                    field.set(targetInstance, v);
                    return true;
                } else if (type == String.class) {
                    field.set(targetInstance, rawValue);
                    return true;
                } else if (type.isEnum()) {
                    for (Object constant : type.getEnumConstants()) {
                        if (constant.toString().equalsIgnoreCase(trimmed) || ((Enum<?>) constant).name().equalsIgnoreCase(trimmed)) {
                            field.set(targetInstance, constant);
                            return true;
                        }
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("設定値 {} に \"{}\" を設定できませんでした: {}",
                        key, rawValue, e.toString());
            }
            return false;
        }

        public void resetToDefault() {
            synchronized (WRITE_LOCK) {
                try {
                    field.set(targetInstance, defaultValue);
                } catch (Exception e) {
                    LOGGER.warn("設定値 {} を既定値に戻せませんでした: {}", key, e.toString());
                }
            }
        }
    }

    public static class RegisteredConfig {
        public final String modId;
        public final Class<?> configClass;
        public final Object instance;
        public final EnderConfig configMeta;
        public final Path filePath;
        public final List<FieldSpec> fields = new ArrayList<>();
        public final List<Consumer<RegisteredConfig>> changeListeners = new CopyOnWriteArrayList<>();

        public RegisteredConfig(String modId, Class<?> configClass, Object instance, EnderConfig configMeta, Path filePath) {
            this.modId = modId;
            this.configClass = configClass;
            this.instance = instance;
            this.configMeta = configMeta;
            this.filePath = filePath;
        }

        public void addChangeListener(Consumer<RegisteredConfig> listener) {
            if (listener != null && !changeListeners.contains(listener)) {
                changeListeners.add(listener);
            }
        }

        public void notifyListeners() {
            for (Consumer<RegisteredConfig> listener : changeListeners) {
                try {
                    listener.accept(this);
                } catch (Exception ignored) {
                }
            }
        }

        public Map<String, List<FieldSpec>> getFieldsByCategory() {
            Map<String, List<FieldSpec>> map = new LinkedHashMap<>();
            for (FieldSpec spec : fields) {
                map.computeIfAbsent(spec.category(), k -> new ArrayList<>()).add(spec);
            }
            return map;
        }
    }

    /**
     * static フィールドを持つ設定クラスを登録し、configディレクトリから即時ロードする。
     */
    public static RegisteredConfig register(String modId, Class<?> configClass) {
        return register(modId, configClass, null);
    }

    /**
     * インスタンス（または static クラス）を登録し、configディレクトリから即時ロードする。
     */
    public static RegisteredConfig register(String modId, Class<?> configClass, Object instance) {
        // 登録 → ロード → 保存 は 1 つの read-modify-write。途中で別スレッドに割り込まれると
        // 既定値のまま保存して設定を失う
        synchronized (WRITE_LOCK) {
            RegisteredConfig reg = buildRegistration(modId, configClass, instance);
            CONFIGS.put(modId, reg);
            // 以前は load() と save() がそれぞれ通知していたため、登録だけで 2 回発火していた
            loadInternal(modId, false);
            saveInternal(modId, false);
            reg.notifyListeners();
            return reg;
        }
    }

    private static RegisteredConfig buildRegistration(String modId, Class<?> configClass, Object instance) {
        EnderConfig meta = configClass.getAnnotation(EnderConfig.class);
        String fileName = (meta != null && !meta.fileName().isEmpty()) ? meta.fileName() : modId;
        if (!fileName.endsWith(".json")) fileName += ".json";

        Path configDir;
        try {
            configDir = FMLPaths.CONFIGDIR.get();
        } catch (Throwable t) {
            configDir = Path.of("config");
        }
        Path filePath = configDir.resolve(fileName);

        RegisteredConfig reg = new RegisteredConfig(modId, configClass, instance, meta, filePath);

        for (Field f : configClass.getDeclaredFields()) {
            ConfigEntry entry = f.getAnnotation(ConfigEntry.class);
            if (entry == null) continue;
            boolean isStatic = Modifier.isStatic(f.getModifiers());
            if (!isStatic && instance == null) continue;

            f.setAccessible(true);
            Object target = isStatic ? null : instance;
            Object defVal = null;
            try {
                defVal = f.get(target);
            } catch (Exception ignored) {
            }

            String label = entry.label().isEmpty() ? humanizeFieldName(f.getName()) : entry.label();
            String category = entry.category().isEmpty() ? "General" : entry.category();

            reg.fields.add(new FieldSpec(
                    f.getName(),
                    f,
                    target,
                    entry,
                    defVal,
                    category,
                    label,
                    entry.description()
            ));
        }

        return reg;
    }

    public static RegisteredConfig getConfig(String modId) {
        return CONFIGS.get(modId);
    }

    public static Collection<RegisteredConfig> getAllConfigs() {
        return CONFIGS.values();
    }

    public static void load(String modId) {
        synchronized (WRITE_LOCK) {
            loadInternal(modId, true);
        }
    }

    private static void loadInternal(String modId, boolean notify) {
        RegisteredConfig reg = CONFIGS.get(modId);
        if (reg == null || !Files.exists(reg.filePath)) return;

        try (Reader reader = Files.newBufferedReader(reg.filePath, StandardCharsets.UTF_8)) {
            JsonElement rootEl = JsonParser.parseReader(reader);
            if (!rootEl.isJsonObject()) return;
            JsonObject root = rootEl.getAsJsonObject();

            for (FieldSpec spec : reg.fields) {
                JsonElement valEl = null;
                if (root.has(spec.category()) && root.get(spec.category()).isJsonObject()) {
                    JsonObject catObj = root.getAsJsonObject(spec.category());
                    if (catObj.has(spec.key())) {
                        valEl = catObj.get(spec.key());
                    }
                } else if (root.has(spec.key())) {
                    valEl = root.get(spec.key());
                }

                if (valEl != null && !valEl.isJsonNull()) {
                    // 型が合わない値を黙って捨てると、設定ファイルを直接編集した利用者が
                    // 「保存したのに反映されない」状態に気づけない
                    if (!spec.setFromString(valEl.getAsString())) {
                        LOGGER.warn(
                                "設定 {} の値 \"{}\" を {} として解釈できなかったため、既定値 {} を使います ({})",
                                spec.key(), valEl.getAsString(), spec.field().getType().getSimpleName(),
                                spec.defaultValue(), reg.filePath.getFileName());
                    }
                }
            }
            if (notify) reg.notifyListeners();
        } catch (Exception e) {
            LOGGER.warn("Failed to load config for {}: {}", modId, e.getMessage());
        }
    }

    public static void save(String modId) {
        synchronized (WRITE_LOCK) {
            saveInternal(modId, true);
        }
    }

    private static void saveInternal(String modId, boolean notify) {
        RegisteredConfig reg = CONFIGS.get(modId);
        if (reg == null) return;

        try {
            if (reg.filePath.getParent() != null) {
                Files.createDirectories(reg.filePath.getParent());
            }
            JsonObject root = new JsonObject();
            for (Map.Entry<String, List<FieldSpec>> entry : reg.getFieldsByCategory().entrySet()) {
                JsonObject catObj = new JsonObject();
                for (FieldSpec spec : entry.getValue()) {
                    Object val = spec.getCurrentValue();
                    if (val instanceof Boolean b) catObj.addProperty(spec.key(), b);
                    else if (val instanceof Number n) {
                        if (spec.annotation().colorPicker() && val instanceof Integer col) {
                            catObj.addProperty(spec.key(), String.format("#%06X", (col & 0xFFFFFF)));
                        } else {
                            catObj.addProperty(spec.key(), n);
                        }
                    } else if (val != null) {
                        catObj.addProperty(spec.key(), String.valueOf(val));
                    }
                }
                root.add(entry.getKey(), catObj);
            }

            try (Writer writer = Files.newBufferedWriter(reg.filePath, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }

            if (notify) reg.notifyListeners();
        } catch (Exception e) {
            LOGGER.error("Failed to save config for {}: {}", modId, e.getMessage());
        }
    }

    /** 既定のテーマ。{@link EnderConfig#theme()} が空のときに使う。 */
    private static final String DEFAULT_THEME = "enderframelib:dark.css";
    /** 設定画面自体のスタイルシート（jar 同梱リソース）。 */
    private static final String CONFIG_FORM_CSS = "enderframelib:css/config-form.css";

    /**
     * Resolves external resources (custom templates, stylesheets) by id.
     * Installed by the library at startup; defaults to resolving nothing so that this
     * package does not depend on the parser implementation.
     */
    public static Function<String, String> resourceResolver = path -> "";

    /**
     * Generates a semantic HTML5 document for a mod's configuration class.
     *
     * @param searchFilter free-text filter; only matching entries are emitted
     */
    public static String generateStandardHtml5Config(RegisteredConfig reg, String searchFilter) {
        String custom = resolveCustomTemplate(reg);
        if (custom != null) return custom;

        StringBuilder html = new StringBuilder();
        appendHead(html, reg);
        appendHeader(html, reg, searchFilter);

        html.append("<form id=\"cfg_form\" class=\"cfg-form\" onsubmit=\"save_config\" onreset=\"reset_all\">");
        String filterLower = searchFilter != null ? searchFilter.trim().toLowerCase(Locale.ROOT) : "";
        for (Map.Entry<String, List<FieldSpec>> catEntry : reg.getFieldsByCategory().entrySet()) {
            appendCategory(html, catEntry.getKey(), catEntry.getValue(), filterLower);
        }
        appendFooter(html);
        html.append("</form></body></html>");
        return html.toString();
    }

    /** {@link EnderConfig#customTemplate()} が解決できればそれを使う。 */
    private static String resolveCustomTemplate(RegisteredConfig reg) {
        if (reg.configMeta == null || reg.configMeta.customTemplate().isEmpty() || resourceResolver == null) {
            return null;
        }
        String customHtml = resourceResolver.apply(reg.configMeta.customTemplate());
        return (customHtml != null && !customHtml.isEmpty()) ? customHtml : null;
    }

    private static void appendHead(StringBuilder html, RegisteredConfig reg) {
        String theme = (reg.configMeta != null && !reg.configMeta.theme().isEmpty())
                ? reg.configMeta.theme()
                : DEFAULT_THEME;

        html.append("<html><head>");
        html.append("<link rel=\"stylesheet\" href=\"").append(escapeHtml(theme)).append("\" />");
        // 設定画面のスタイルはリソース 1 箇所が出所。読めなかった場合でも
        // テーマだけで最低限表示できるので、空のままにする
        String formCss = resourceResolver != null ? resourceResolver.apply(CONFIG_FORM_CSS) : null;
        if (formCss != null && !formCss.isEmpty()) {
            html.append("<style>").append(formCss).append("</style>");
        }
        html.append("</head><body>");
    }

    private static void appendHeader(StringBuilder html, RegisteredConfig reg, String searchFilter) {
        String title = (reg.configMeta != null && !reg.configMeta.title().isEmpty())
                ? reg.configMeta.title()
                : (reg.modId + " Configuration");

        html.append("<header class=\"cfg-header\">");
        html.append("<div class=\"cfg-title\">").append(escapeHtml(title)).append("</div>");
        html.append("<input id=\"cfg_search\" type=\"search\" class=\"cfg-search\" placeholder=\"Search settings...\" value=\"")
                .append(escapeHtml(searchFilter != null ? searchFilter : "")).append("\" />");
        html.append("</header>");
    }

    private static void appendCategory(StringBuilder html, String category, List<FieldSpec> specs, String filterLower) {
        List<FieldSpec> matchedSpecs = new ArrayList<>();
        for (FieldSpec spec : specs) {
            if (matchesFilter(spec, category, filterLower)) matchedSpecs.add(spec);
        }
        if (matchedSpecs.isEmpty()) return;

        String catId = "cat_" + category.replaceAll("[^a-zA-Z0-9_]", "_");
        html.append("<details id=\"").append(catId).append("\" class=\"cfg-category\" open=\"true\">");
        html.append("<summary class=\"cfg-summary\">").append(escapeHtml(category)).append("</summary>");
        for (FieldSpec spec : matchedSpecs) {
            appendRow(html, spec);
        }
        html.append("</details>");
    }

    private static boolean matchesFilter(FieldSpec spec, String category, String filterLower) {
        if (filterLower.isEmpty()) return true;
        return spec.label().toLowerCase(Locale.ROOT).contains(filterLower)
                || spec.key().toLowerCase(Locale.ROOT).contains(filterLower)
                || spec.description().toLowerCase(Locale.ROOT).contains(filterLower)
                || category.toLowerCase(Locale.ROOT).contains(filterLower);
    }

    private static void appendRow(StringBuilder html, FieldSpec spec) {
        String inputId = "field_" + spec.key();
        String tooltipAttr = spec.description().isEmpty() ? "" : " title=\"" + escapeHtml(spec.description()) + "\"";

        html.append("<div class=\"cfg-row\"").append(tooltipAttr).append(">");
        html.append("<div class=\"cfg-label-col\">");
        html.append("<label class=\"cfg-label\" for=\"").append(inputId).append("\">")
                .append(escapeHtml(spec.label()));
        if (spec.annotation().requiresRestart()) {
            html.append("<span class=\"cfg-badge-restart\">[Restart]</span>");
        }
        html.append("</label>");
        if (!spec.description().isEmpty()) {
            html.append("<span class=\"cfg-desc\">").append(escapeHtml(spec.description())).append("</span>");
        }
        html.append("</div>");

        html.append("<div class=\"cfg-control-col\">");
        appendStandardControlHtml(html, spec, inputId);
        html.append("<button type=\"button\" class=\"cfg-reset-btn\" title=\"Reset to default: ")
                .append(escapeHtml(String.valueOf(spec.defaultValue())))
                .append("\" onclick=\"reset_field:").append(spec.key()).append("\">↺</button>");
        html.append("</div>");
        html.append("</div>");
    }

    private static void appendFooter(StringBuilder html) {
        html.append("<footer class=\"cfg-footer\">");
        html.append("<button type=\"reset\" class=\"mc-btn\" onclick=\"reset_all\">Reset All</button>");
        html.append("<button type=\"button\" class=\"mc-btn\" onclick=\"cancel_config\">Cancel</button>");
        html.append("<button type=\"submit\" class=\"mc-btn mc-btn-primary\" onclick=\"save_config\">Save &amp; Apply</button>");
        html.append("</footer>");
    }

    private static void appendStandardControlHtml(StringBuilder html, FieldSpec spec, String inputId) {
        Class<?> type = spec.field().getType();
        ConfigEntry ann = spec.annotation();
        String modelAttr = " name=\"" + escapeHtml(spec.key()) + "\" data-model=\"" + escapeHtml(spec.key()) + "\"";

        if (type == boolean.class || type == Boolean.class) {
            boolean checked = Boolean.TRUE.equals(spec.getCurrentValue());
            html.append("<input id=\"").append(inputId).append("\" type=\"checkbox\"")
                    .append(modelAttr)
                    .append(checked ? " checked=\"true\"" : "")
                    .append(" />");
        } else if (type.isEnum() || ann.options().length > 0) {
            String cur = spec.getCurrentValueAsString();
            html.append("<select id=\"").append(inputId).append("\" class=\"cfg-input\"").append(modelAttr)
                    .append(" value=\"").append(escapeHtml(cur)).append("\">");
            if (type.isEnum()) {
                for (Object constant : type.getEnumConstants()) {
                    String val = constant.toString();
                    boolean selected = Objects.equals(cur, val);
                    html.append("<option value=\"").append(escapeHtml(val)).append("\"")
                            .append(selected ? " selected=\"true\"" : "").append(">")
                            .append(escapeHtml(val)).append("</option>");
                }
            } else {
                for (String opt : ann.options()) {
                    boolean selected = Objects.equals(cur, opt);
                    html.append("<option value=\"").append(escapeHtml(opt)).append("\"")
                            .append(selected ? " selected=\"true\"" : "").append(">")
                            .append(escapeHtml(opt)).append("</option>");
                }
            }
            html.append("</select>");
        } else if (ann.colorPicker()) {
            String cur = spec.getCurrentValueAsString();
            html.append("<input id=\"").append(inputId).append("\" type=\"color\" class=\"cfg-input\"")
                    .append(modelAttr)
                    .append(" value=\"").append(escapeHtml(cur)).append("\" />");
        } else if (isNumericType(type)) {
            double min = ann.min() != -Double.MAX_VALUE ? ann.min() : 0;
            double max = ann.max() != Double.MAX_VALUE ? ann.max() : 100;
            double step = ann.step();
            String cur = spec.getCurrentValueAsString();

            if (ann.slider()) {
                html.append("<div class=\"cfg-range-wrap\">");
                html.append("<input id=\"").append(inputId).append("\" type=\"range\" style=\"width:110px;\"")
                        .append(modelAttr)
                        .append(" min=\"").append(formatNum(min)).append("\"")
                        .append(" max=\"").append(formatNum(max)).append("\"")
                        .append(" step=\"").append(formatNum(step)).append("\"")
                        .append(" value=\"").append(escapeHtml(cur)).append("\" />");
                html.append("<output class=\"cfg-range-val\" for=\"").append(inputId).append("\">{{")
                        .append(escapeHtml(spec.key())).append("}}</output>");
                html.append("</div>");
            } else {
                html.append("<input id=\"").append(inputId).append("\" type=\"number\" class=\"cfg-input\"")
                        .append(modelAttr)
                        .append(" value=\"").append(escapeHtml(cur)).append("\"");
                if (ann.min() != -Double.MAX_VALUE) html.append(" min=\"").append(formatNum(ann.min())).append("\"");
                if (ann.max() != Double.MAX_VALUE) html.append(" max=\"").append(formatNum(ann.max())).append("\"");
                html.append(" step=\"").append(formatNum(step)).append("\" />");
            }
        } else {
            String cur = spec.getCurrentValueAsString();
            html.append("<input id=\"").append(inputId).append("\" type=\"text\" class=\"cfg-input\"")
                    .append(modelAttr)
                    .append(" value=\"").append(escapeHtml(cur)).append("\"");
            if (!ann.placeholder().isEmpty()) {
                html.append(" placeholder=\"").append(escapeHtml(ann.placeholder())).append("\"");
            }
            if (!ann.pattern().isEmpty()) {
                html.append(" pattern=\"").append(escapeHtml(ann.pattern())).append("\"");
            }
            html.append(" />");
        }
    }

    private static boolean isNumericType(Class<?> type) {
        return type == int.class || type == Integer.class
                || type == long.class || type == Long.class
                || type == float.class || type == Float.class
                || type == double.class || type == Double.class;
    }

    private static String formatNum(double d) {
        if (d == Math.floor(d) && !Double.isInfinite(d)) {
            return String.valueOf((long) d);
        }
        return String.valueOf(d);
    }

    public static String humanizeFieldName(String name) {
        if (name == null || name.isEmpty()) return "";
        String spaced = name.replaceAll("([a-z])([A-Z])", "$1 $2").replace('_', ' ');
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}