package com.nishiyu.enderframelib.core.kernel.compiler;

import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.kernel.memory.DocumentArena;
import com.nishiyu.enderframelib.core.kernel.memory.OffHeapColumns;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code style="width: {{hp_pct}}%"} のようなバインディングを、ロード時に
 * {@code キー → (nodeId, 欄)} の直接書き込み命令へコンパイルした表。
 *
 * <p>実行時に {@link #setFloat} を呼ぶと、DOM の属性も CSS のパースも通らず、アリーナと同じ添字（nodeId）の
 * オフヒープ float 列に値が書かれ、その節に再スタイル／レイアウトの汚れが立つ。ホットパスは割り当てなし。</p>
 */
public final class BindingPatchTable implements com.nishiyu.enderframelib.core.dom.node.InlineStyleOverlay {

    /** 書き込み先の欄。 */
    public static final int F_WIDTH_PCT = 0, F_WIDTH_PX = 1, F_HEIGHT_PCT = 2, F_HEIGHT_PX = 3,
            F_OPACITY = 4, F_FLEX_GROW = 5, F_FONT_SIZE_PX = 6;
    public static final int FIELD_COUNT = 7;

    private static final String[] FIELD_NAMES = {"width%", "width", "height%", "height", "opacity", "flex-grow",
            "font-size"};

    private final DocumentArena arena;
    private OffHeapColumns.Floats[] columns = new OffHeapColumns.Floats[FIELD_COUNT];
    private int columnLength;
    private final Map<String, int[]> patchesByKey = new HashMap<>();
    private int patchCount;
    private final java.util.Set<Long> registered = new java.util.HashSet<>();
    private int compiledLive = -1;

    public BindingPatchTable(DocumentArena arena) {
        this.arena = arena;
        this.columnLength = Math.max(1, arena.capacity());
        for (int f = 0; f < FIELD_COUNT; f++) {
            columns[f] = new OffHeapColumns.Floats(columnLength);
            columns[f].fill(Float.NaN);
        }
    }

    /** この表が {@code a} の添字で作られたものか。 */
    public boolean isFor(DocumentArena a) { return arena == a; }

    /** 登録済みのパッチ数。 */
    public int patchCount() { return patchCount; }

    public boolean hasKey(String key) { return patchesByKey.containsKey(key); }

    /** 1 つのキーを 1 つの節の 1 つの欄へ結ぶ。 */
    public void register(String key, int nodeId, int field) {
        if (nodeId < 0 || field < 0 || field >= FIELD_COUNT) throw new IllegalArgumentException();
        if (!registered.add(((long) nodeId << 3) | field)) return; // 同じ節の同じ欄は 1 つのキーにしか結ばない
        int[] old = patchesByKey.get(key);
        int n = old == null ? 0 : old.length;
        int[] next = new int[n + 2]; // [nodeId, field] の組
        if (old != null) System.arraycopy(old, 0, next, 0, n);
        next[n] = nodeId;
        next[n + 1] = field;
        patchesByKey.put(key, next);
        patchCount++;
        ensureCapacity(nodeId);
    }

    /**
     * 木の中の {@code style} 属性から {@code prop: {{key}}unit} を探して登録する。
     * 対応しない宣言（複数の置換が混ざるものなど）は従来の経路に任せ、登録しない。
     *
     * @return 登録したパッチ数
     */
    public int compile(HtmlNode root) {
        return compile(root, n -> n.attrs == null ? null : n.attrs.get("style"));
    }

    /** {@code styleSource} は節の「置換前の」style 属性を返す（同期で解決済みになる前のテンプレート文字列）。 */
    public int compile(HtmlNode root, java.util.function.Function<HtmlNode, String> styleSource) {
        int before = patchCount;
        scan(root, styleSource);
        compiledLive = arena.liveCount();
        return patchCount - before;
    }

    /**
     * 前回のコンパイルから節の数が変わっていたら（リスト行の追加など）、木を走査し直して新しい節だけを登録する。
     *
     * @return 新しく登録したパッチ数
     */
    public int compileNewNodes(HtmlNode root, java.util.function.Function<HtmlNode, String> styleSource) {
        if (arena.liveCount() == compiledLive) return 0;
        return compile(root, styleSource);
    }

    private void scan(HtmlNode node, java.util.function.Function<HtmlNode, String> styleSource) {
        if (node == null) return;
        int id = node.nodeId();
        String style = styleSource.apply(node);
        if (id >= 0 && style != null && style.contains("{{")) {
            for (String decl : style.split(";")) {
                int colon = decl.indexOf(':');
                if (colon < 0) continue;
                String prop = decl.substring(0, colon).trim().toLowerCase(java.util.Locale.ROOT);
                String val = decl.substring(colon + 1).trim();
                if (!val.startsWith("{{")) continue;
                int close = val.indexOf("}}");
                if (close < 0 || val.indexOf("{{", close) >= 0) continue;
                String key = val.substring(2, close).trim();
                String unit = val.substring(close + 2).trim();
                int field = fieldFor(prop, unit);
                if (field >= 0 && !key.isEmpty() && !key.startsWith("@") && !key.contains(":")) register(key, id, field);
            }
        }
        for (HtmlNode child : node.children) scan(child, styleSource);
    }

    /** style 属性の 1 宣言から読み取った、直接書き込みの対象。 */
    public record Patch(String prop, String key, int field) {}

    /**
     * {@code prop: {{key}}unit} の形の 1 宣言を読む。直接書き込みに向かない宣言（式の中の置換・{@code !important}・
     * 未対応の単位や属性）は null。
     */
    public static Patch parsePatch(String decl) {
        int colon = decl.indexOf(':');
        if (colon < 0) return null;
        String prop = decl.substring(0, colon).trim().toLowerCase(java.util.Locale.ROOT);
        String val = decl.substring(colon + 1).trim();
        if (!val.startsWith("{{")) return null;
        int close = val.indexOf("}}");
        if (close < 0 || val.indexOf("{{", close) >= 0) return null;
        String key = val.substring(2, close).trim();
        int field = fieldFor(prop, val.substring(close + 2).trim());
        if (field < 0 || key.isEmpty() || key.startsWith("@") || key.contains(":")) return null;
        return new Patch(prop, key, field);
    }

    /**
     * 1 つの節の 1 つの欄へ値を直接書く。{@code NaN} は「書かれていない」に戻す。値が変わったときだけ true（再スタイルの要求は呼び出し側が changedNodes で行う）。
     */
    public boolean set(int nodeId, int field, float value) {
        if (nodeId < 0 || field < 0 || field >= FIELD_COUNT) throw new IllegalArgumentException();
        if (nodeId >= columnLength) {
            if (Float.isNaN(value)) return false;
            ensureCapacity(nodeId);
        }
        OffHeapColumns.Floats col = columns[field];
        if (Float.floatToRawIntBits(col.get(nodeId)) == Float.floatToRawIntBits(value)) return false;
        col.set(nodeId, value);
        return true;
    }

    /** すべての欄を「書かれていない」に戻す（バインダーの作り直し用）。 */
    public void clearValues() {
        for (int f = 0; f < FIELD_COUNT; f++) columns[f].fill(Float.NaN);
    }

    @Override
    public void overlayInline(HtmlNode node, Map<String, String> inline) {
        int id = node.hasNodeId() ? node.nodeId() : -1;
        if (id < 0 || id >= columnLength) return;
        float v;
        if (!Float.isNaN(v = columns[F_WIDTH_PCT].get(id))) inline.put("width", css(v) + "%");
        if (!Float.isNaN(v = columns[F_WIDTH_PX].get(id))) inline.put("width", css(v) + "px");
        if (!Float.isNaN(v = columns[F_HEIGHT_PCT].get(id))) inline.put("height", css(v) + "%");
        if (!Float.isNaN(v = columns[F_HEIGHT_PX].get(id))) inline.put("height", css(v) + "px");
        if (!Float.isNaN(v = columns[F_OPACITY].get(id))) inline.put("opacity", css(v));
        if (!Float.isNaN(v = columns[F_FLEX_GROW].get(id))) inline.put("flex-grow", css(v));
        if (!Float.isNaN(v = columns[F_FONT_SIZE_PX].get(id))) inline.put("font-size", css(v) + "px");
    }

    /** CSS に書ける数値の文字列（指数表記にしない）。 */
    static String css(float v) {
        if (v == (long) v) return Long.toString((long) v);
        String s = Float.toString(v);
        return s.indexOf('E') < 0 ? s : new java.math.BigDecimal(s).toPlainString();
    }

    private static int fieldFor(String prop, String unit) {
        return switch (prop) {
            case "width" -> unit.equals("%") ? F_WIDTH_PCT : unit.equals("px") || unit.isEmpty() ? F_WIDTH_PX : -1;
            case "height" -> unit.equals("%") ? F_HEIGHT_PCT : unit.equals("px") || unit.isEmpty() ? F_HEIGHT_PX : -1;
            case "opacity" -> unit.isEmpty() ? F_OPACITY : -1;
            case "flex-grow" -> unit.isEmpty() ? F_FLEX_GROW : -1;
            case "font-size" -> unit.equals("px") ? F_FONT_SIZE_PX : -1;
            default -> -1;
        };
    }

    /**
     * キーに結ばれたすべての欄へ値を直接書く。値が変わった欄だけ数える。
     *
     * @return 値が変わった欄の数（結ばれていないキーは 0）
     */
    public int setFloat(String key, float value) {
        int[] patches = patchesByKey.get(key);
        if (patches == null) return 0;
        int changed = 0;
        for (int i = 0; i < patches.length; i += 2) {
            int node = patches[i], field = patches[i + 1];
            if (node >= columnLength) ensureCapacity(node);
            OffHeapColumns.Floats col = columns[field];
            if (Float.floatToRawIntBits(col.get(node)) == Float.floatToRawIntBits(value)) continue;
            col.set(node, value);
            changed++;
        }
        return changed;
    }

    /** 欄の現在値。まだ書かれていなければ NaN。 */
    public float get(int field, int nodeId) {
        return nodeId < columnLength ? columns[field].get(nodeId) : Float.NaN;
    }

    public static String fieldName(int field) { return FIELD_NAMES[field]; }

    public List<String> keys() { return new ArrayList<>(patchesByKey.keySet()); }

    private void ensureCapacity(int nodeId) {
        if (nodeId < columnLength) return;
        int len = Math.max(nodeId + 1, columnLength * 2);
        for (int f = 0; f < FIELD_COUNT; f++) {
            OffHeapColumns.Floats next = new OffHeapColumns.Floats(len);
            next.fill(Float.NaN);
            for (int i = 0; i < columnLength; i++) next.set(i, columns[f].get(i));
            columns[f] = next;
        }
        columnLength = len;
    }
}
