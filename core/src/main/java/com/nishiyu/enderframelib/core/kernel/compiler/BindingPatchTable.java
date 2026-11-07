package com.nishiyu.enderframelib.core.kernel.compiler;

import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.kernel.memory.DocumentArena;
import com.nishiyu.enderframelib.core.kernel.memory.OffHeapColumns;
import com.nishiyu.enderframelib.core.kernel.style.PackedCssValue;
import com.nishiyu.enderframelib.core.kernel.style.StyleStructs;
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
public final class BindingPatchTable {

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
     * キーに結ばれたすべての欄へ値を直接書く。値が変わった欄の節だけ汚す。
     *
     * @return 値が変わって汚れた節の数（結ばれていないキーは 0）
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
            if (arena.isLive(node)) {
                arena.setDirty(node, DocumentArena.DIRTY_STYLE | (isLayoutField(field) ? DocumentArena.DIRTY_LAYOUT
                        : DocumentArena.DIRTY_PAINT), true);
            }
            changed++;
        }
        return changed;
    }

    private static boolean isLayoutField(int field) { return field != F_OPACITY; }

    /** 欄の現在値。まだ書かれていなければ NaN。 */
    public float get(int field, int nodeId) {
        return nodeId < columnLength ? columns[field].get(nodeId) : Float.NaN;
    }

    public static String fieldName(int field) { return FIELD_NAMES[field]; }

    /**
     * 構造体に、直接書かれたバインディング値を重ねる。パッチが無ければ {@code base} をそのまま返す。
     * 幅・高さ（%/px）・opacity・flex-grow・font-size。重ねた結果は {@link StyleStructs#intern} で畳む。
     */
    public StyleStructs overlay(StyleStructs base, int nodeId) {
        if (nodeId < 0 || nodeId >= columnLength) return base;
        var box = base.box;
        var paint = base.paint;
        var font = base.font;
        float v;
        if (!Float.isNaN(v = columns[F_WIDTH_PCT].get(nodeId))) box = withWidth(box, PackedCssValue.length(v, PackedCssValue.U_PERCENT));
        if (!Float.isNaN(v = columns[F_WIDTH_PX].get(nodeId))) box = withWidth(box, PackedCssValue.length(v, PackedCssValue.U_PX));
        if (!Float.isNaN(v = columns[F_HEIGHT_PCT].get(nodeId))) box = withHeight(box, PackedCssValue.length(v, PackedCssValue.U_PERCENT));
        if (!Float.isNaN(v = columns[F_HEIGHT_PX].get(nodeId))) box = withHeight(box, PackedCssValue.length(v, PackedCssValue.U_PX));
        if (!Float.isNaN(v = columns[F_FLEX_GROW].get(nodeId))) {
            box = new StyleStructs.BoxGeometryStruct(box.width(), box.height(), box.minWidth(), box.maxWidth(),
                    box.minHeight(), box.maxHeight(), box.aspectRatio(), v, box.flexShrink(), box.flexBasis(),
                    box.displayAtom(), box.positionAtom(), box.boxSizing(), box.overflowX(), box.overflowY());
        }
        if (!Float.isNaN(v = columns[F_OPACITY].get(nodeId))) {
            paint = new StyleStructs.PaintEffectStruct(v, paint.bgColorArgb(), paint.zIndex(), paint.transformOpId(),
                    paint.filterOpId(), paint.transitionId(), paint.animationId());
        }
        if (!Float.isNaN(v = columns[F_FONT_SIZE_PX].get(nodeId))) {
            font = new StyleStructs.InheritedFontStruct(v, font.lineHeightPx(), font.fontWeight(), font.fontStyleAtom(),
                    font.fontFamilyAtom());
        }
        if (box == base.box && paint == base.paint && font == base.font) return base;
        return StyleStructs.of(StyleStructs.intern(font), base.text, StyleStructs.intern(box), base.surround,
                StyleStructs.intern(paint));
    }

    private static StyleStructs.BoxGeometryStruct withWidth(StyleStructs.BoxGeometryStruct b, long w) {
        return new StyleStructs.BoxGeometryStruct(w, b.height(), b.minWidth(), b.maxWidth(), b.minHeight(), b.maxHeight(),
                b.aspectRatio(), b.flexGrow(), b.flexShrink(), b.flexBasis(), b.displayAtom(), b.positionAtom(),
                b.boxSizing(), b.overflowX(), b.overflowY());
    }

    private static StyleStructs.BoxGeometryStruct withHeight(StyleStructs.BoxGeometryStruct b, long h) {
        return new StyleStructs.BoxGeometryStruct(b.width(), h, b.minWidth(), b.maxWidth(), b.minHeight(), b.maxHeight(),
                b.aspectRatio(), b.flexGrow(), b.flexShrink(), b.flexBasis(), b.displayAtom(), b.positionAtom(),
                b.boxSizing(), b.overflowX(), b.overflowY());
    }

    /** 登録されたキーの一覧（診断用）。 */
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
