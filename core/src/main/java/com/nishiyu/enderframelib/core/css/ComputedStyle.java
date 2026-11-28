package com.nishiyu.enderframelib.core.css;

import com.nishiyu.enderframelib.core.css.model.Overflow;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 算出スタイル（M1c: 本体 {@link Style} の薄い不変ラッパー）。描画・フォント等の要素ごとのメモを持つ。
 */
public final class ComputedStyle {

    /** 既定のルートフォントサイズ（px）。 */
    public static final float DEFAULT_FONT_SIZE = 16.0f;
    /** {@code line-height} の種類（{@link StyleFacts#lineHeightKind}）。 */
    public static final byte LH_NORMAL = 0, LH_PX = 1, LH_PERCENT = 2, LH_EM = 3, LH_NUMBER = 4;
    /** {@code contain} の値のビット（{@link #containment()}）。 */
    public static final int CONTAIN_SIZE = 1, CONTAIN_INLINE_SIZE = 2, CONTAIN_LAYOUT = 4, CONTAIN_PAINT = 8, CONTAIN_STYLE = 16;

    private final Style style;
    private volatile Object derivedFacts;
    private volatile Object paint;
    private volatile CachedFace cachedFace;
    private volatile Map<String, String> customProperties;

    private record CachedFace(com.nishiyu.enderframelib.core.text.model.FontResolver resolver, long generation,
                              com.nishiyu.enderframelib.core.text.model.FontFace face) {}

    private static CachedFace LAST_FACE;
    private static final VarHandle PAINT;
    private static final int OBJ_MEMO_LIMIT = 8;

    static {
        try {
            PAINT = MethodHandles.lookup().findVarHandle(ComputedStyle.class, "paint", Object.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static final class ObjMemo {
        final Object[] keys, vals;
        ObjMemo(Object[] keys, Object[] vals) { this.keys = keys; this.vals = vals; }
        Object get(Object key) {
            for (int i = 0; i < keys.length; i++) if (keys[i] == key || keys[i].equals(key)) return vals[i];
            return null;
        }
        ObjMemo with(Object key, Object val) {
            int n = keys.length;
            Object[] k = new Object[n + 1], v = new Object[n + 1];
            System.arraycopy(keys, 0, k, 0, n);
            System.arraycopy(vals, 0, v, 0, n);
            k[n] = key; v[n] = val;
            return new ObjMemo(k, v);
        }
        ConcurrentHashMap<Object, Object> toMap() {
            ConcurrentHashMap<Object, Object> m = new ConcurrentHashMap<>(keys.length * 4);
            for (int i = 0; i < keys.length; i++) m.put(keys[i], vals[i]);
            return m;
        }
    }

    public ComputedStyle(Style style) { this.style = Objects.requireNonNull(style); }
    public ComputedStyle(Map<String, String> properties) { this(properties, DEFAULT_FONT_SIZE, DEFAULT_FONT_SIZE); }
    public ComputedStyle(Map<String, String> source, float elementFontSize, float rootFontSize) {
        this.style = styleFromMap(source, elementFontSize, rootFontSize, null);
    }

    public static ComputedStyle of(Map<String, String> properties, float parentFontSize, float rootFontSize) {
        float own = parentFontSize;
        String fontSize = properties != null ? properties.get(CssProperty.FONT_SIZE) : null;
        if (fontSize != null) {
            own = ValueComputer.resolveFontSize(fontSize, new ValueComputer.Context(Map.of(), parentFontSize, rootFontSize, 0, 0));
        }
        return new ComputedStyle(properties, own, rootFontSize);
    }

    static ComputedStyle ofOwned(StyleTable owned, float parentFontSize, float rootFontSize, Style parent, boolean readViewport) {
        float own = parentFontSize;
        String fontSize = owned.textById(CssPropertyIndex.FONT_SIZE);
        if (fontSize != null) {
            own = ValueComputer.resolveFontSize(fontSize, new ValueComputer.Context(Style.DEFAULT, parentFontSize, rootFontSize, 0, 0));
        }
        final float elFont = own;
        StyleReader.Source src = new StyleReader.Source() {
            @Override public String get(int id) { return owned.textById(id); }
            @Override public float fontSizePx() { return elFont; }
            @Override public float rootFontSizePx() { return rootFontSize; }
        };
        return new ComputedStyle(Style.fromSource(src, owned.extras, readViewport || owned.anyValueDependsOnViewport(), parent, owned.groupMask));
    }

    private static Style styleFromMap(Map<String, String> source, float elFont, float rootFont, Style parent) {
        if ((source == null || source.isEmpty()) && Float.compare(elFont, DEFAULT_FONT_SIZE) == 0
                && Float.compare(rootFont, DEFAULT_FONT_SIZE) == 0 && parent == null) {
            return Style.DEFAULT;
        }
        Map<String, String> srcMap = source != null ? source : Map.of();
        Map<String, String> extras = null;
        boolean vp = false;
        int mask = 0;
        for (Map.Entry<String, String> e : srcMap.entrySet()) {
            String k = e.getKey(), v = e.getValue();
            int id = CssPropertyId.of(k);
            if (id == CssPropertyId.NONE) {
                if (extras == null) extras = new LinkedHashMap<>();
                extras.put(k, v);
            } else if (v != null) {
                mask |= StyleProperties.groupBit(id);
            }
            if (!vp && v != null && StyleResolver.valueDependsOnViewport(v)) vp = true;
        }
        StyleReader.Source src = new StyleReader.Source() {
            @Override public String get(int id) { return srcMap.get(CssPropertyId.name(id)); }
            @Override public float fontSizePx() { return elFont; }
            @Override public float rootFontSizePx() { return rootFont; }
        };
        return Style.fromSource(src, extras, vp, parent, mask);
    }

    public Style typed() { return style; }
    public StyleFacts facts() { return style.facts; }
    public float fontSizePx() { return style.inherited.fontSize; }
    public float rootFontSizePx() { return style.inherited.rootFontSizePx; }

    public float lineHeightPx() {
        InheritedStyle in = style.inherited;
        return switch (in.lineHeightKind) {
            case LH_PX -> in.lineHeightValue;
            case LH_PERCENT, LH_EM, LH_NUMBER -> in.lineHeightValue * in.fontSize;
            default -> in.fontSize * LengthResolver.NORMAL_LINE_HEIGHT_EM;
        };
    }

    public String get(String propertyName) { return style.cssProperty(propertyName); }
    public String getOrDefault(String propertyName, String defaultValue) {
        String v = get(propertyName);
        return v != null ? v : defaultValue;
    }
    public boolean containsKey(String propertyName) { return get(propertyName) != null; }
    String get(int id) { return style.cssProperty(CssPropertyId.name(id)); }
    public static int idOf(String propertyName) { return CssPropertyId.of(propertyName); }
    static String initialOf(int id) { return CssPropertyId.INITIALS[id]; }
    static boolean isInheritedId(int id) { return CssPropertyId.INHERITED[id]; }

    public Overflow overflowX() { return style.facts.overflowX; }
    public Overflow overflowY() { return style.facts.overflowY; }
    public int containment() { return style.facts.containment; }
    public boolean skipsContents() { return style.facts.contentVisibilityHidden; }
    public boolean isContentVisibilityAuto() { return style.facts.contentVisibilityAuto; }
    public boolean usesViewportUnits() { return style.facts.usesViewportUnits; }
    /** {@code box-sizing: border-box} か。 */
    public boolean isBorderBox() { return style.facts.borderBox; }

    public float containIntrinsicWidthPx(float rootW, float rootH, float rememberedPx) {
        return resolveContainIntrinsic(style.rare.containIntrinsicWidth, rootW, rootH, rememberedPx);
    }

    public float containIntrinsicHeightPx(float rootW, float rootH, float rememberedPx) {
        return resolveContainIntrinsic(style.rare.containIntrinsicHeight, rootW, rootH, rememberedPx);
    }

    private float resolveContainIntrinsic(String raw, float rootW, float rootH, float rememberedPx) {
        if (raw == null || raw.isBlank()) return Float.NaN;
        String s = raw.trim();
        if (s.length() > 4 && s.regionMatches(true, 0, "auto", 0, 4) && Character.isWhitespace(s.charAt(4))) {
            if (!Float.isNaN(rememberedPx)) return Math.max(0f, rememberedPx);
            s = s.substring(4).trim();
        }
        if (s.isEmpty() || "none".equalsIgnoreCase(s) || s.indexOf('%') >= 0) return Float.NaN;
        float px = LengthResolver.parseDim(s, 0f, rootW, rootH, fontSizePx(), rootFontSizePx());
        return px >= 0f ? px : Float.NaN;
    }

    public Map<String, String> customProperties() {
        Map<String, String> c = customProperties;
        if (c == null) {
            CustomProperties cp = style.custom;
            if (cp == null) c = Map.of();
            else if (cp.hasOnlyCustom()) c = cp.asMap();
            else {
                CustomProperties filtered = cp.onlyCustom();
                c = filtered != null ? filtered.asMap() : Map.of();
            }
            customProperties = c;
        }
        return c;
    }

    public Map<String, String> asMap() { return Collections.unmodifiableMap(style.toCssMap()); }

    public Object derivedFacts() { return derivedFacts; }
    public void derivedFacts(Object facts) { this.derivedFacts = facts; }

    public void carryDerivedFrom(ComputedStyle previous) {
        if (previous == null || previous == this || derivedFacts != null) return;
        Object carried = previous.derivedFacts;
        if (carried != null && style.sameNonInherited(previous.style)) derivedFacts = carried;
    }

    public com.nishiyu.enderframelib.core.text.model.FontFace peekFace(
            com.nishiyu.enderframelib.core.text.model.FontResolver resolver, long generation) {
        CachedFace c = cachedFace;
        return c != null && c.resolver == resolver && c.generation == generation ? c.face : null;
    }

    public void cacheFace(com.nishiyu.enderframelib.core.text.model.FontResolver resolver, long generation,
                          com.nishiyu.enderframelib.core.text.model.FontFace face) {
        CachedFace last = LAST_FACE;
        if (last == null || last.resolver != resolver || last.generation != generation || last.face != face) {
            LAST_FACE = last = new CachedFace(resolver, generation, face);
        }
        this.cachedFace = last;
    }

    public <T> T paintMemo(Object key, Function<Object, T> compute) { return paintMemoInternal(key, compute, null); }
    public <T> T paintMemoSelf(Object key, Function<ComputedStyle, T> compute) { return paintMemoInternal(key, null, compute); }

    @SuppressWarnings("unchecked")
    private <T> T paintMemoInternal(Object key, Function<Object, T> byKey, Function<ComputedStyle, T> bySelf) {
        Object cur = paint;
        if (cur instanceof ConcurrentHashMap<?, ?> big) {
            Object hit = ((ConcurrentHashMap<Object, Object>) big).get(key);
            if (hit != null) return (T) hit;
        } else if (cur != null) {
            Object hit = ((ObjMemo) cur).get(key);
            if (hit != null) return (T) hit;
        }
        T value = bySelf != null ? bySelf.apply(this) : byKey.apply(key);
        if (value == null) return null;
        for (;;) {
            cur = paint;
            if (cur instanceof ConcurrentHashMap<?, ?> big) {
                Object prev = ((ConcurrentHashMap<Object, Object>) big).putIfAbsent(key, value);
                return prev != null ? (T) prev : value;
            }
            ObjMemo m = (ObjMemo) cur;
            if (m != null) {
                Object hit = m.get(key);
                if (hit != null) return (T) hit;
            }
            Object next = (m != null && m.keys.length >= OBJ_MEMO_LIMIT) ? withToMap(m, key, value)
                    : (m == null ? new ObjMemo(new Object[]{key}, new Object[]{value}) : m.with(key, value));
            if (PAINT.compareAndSet(this, cur, next)) return value;
        }
    }

    private static ConcurrentHashMap<Object, Object> withToMap(ObjMemo m, Object key, Object value) {
        ConcurrentHashMap<Object, Object> map = m.toMap();
        map.putIfAbsent(key, value);
        return map;
    }

    // ---- 差分・比較・再スタイル伝播 ----

    public static boolean sameDeclarations(ComputedStyle a, ComputedStyle b) {
        return a == b || (a != null && b != null && a.style.equals(b.style));
    }

    public boolean hasLayoutAffectingDiffFrom(ComputedStyle other) {
        return other == null || StyleDiff.hasLayoutDiff(style, other.style);
    }

    /** 0 = NONE, 1 = NON_INHERITED, 2 = INHERITED。 */
    public int classifyPropagationFrom(ComputedStyle previous) {
        if (previous == null) return 2;
        if (this == previous) return 0;
        Style o = previous.style;
        if (!style.inherited.equals(o.inherited) || !style.inheritedPaint.equals(o.inheritedPaint)
                || !Objects.equals(style.custom, o.custom)) return 2;
        if (!style.box.equals(o.box) || !style.visual.equals(o.visual) || !style.flexGrid.equals(o.flexGrid)
                || !style.table.equals(o.table) || !style.rare.equals(o.rare)) return 1;
        return 0;
    }

    public boolean hasStructureAffectingDiff(ComputedStyle other) {
        if (other == null) return true;
        if (this == other) return false;
        Style o = other.style;
        BoxGeomStyle ba = style.box, bb = o.box;
        InheritedStyle ia = style.inherited, ib = o.inherited;
        RareStyle ra = style.rare, rb = o.rare;
        return !Objects.equals(ba.display, bb.display) || ba.position != bb.position || ba.floatValue != bb.floatValue
                || !Objects.equals(ia.whiteSpace, ib.whiteSpace) || !Objects.equals(ra.content, rb.content)
                || ra.contentVisibility != rb.contentVisibility || !Objects.equals(ia.writingMode, ib.writingMode)
                || !Objects.equals(ia.listStyleType, ib.listStyleType) || ia.listStylePosition != ib.listStylePosition
                || !Objects.equals(ia.listStyleImage, ib.listStyleImage) || !Objects.equals(ra.counterReset, rb.counterReset)
                || !Objects.equals(ra.counterIncrement, rb.counterIncrement) || !Objects.equals(ra.counterSet, rb.counterSet);
    }

    public int classifyPositionOnlyDiff(ComputedStyle other) {
        return other == null ? -1 : StyleDiff.positionOnlyAxes(style, other.style);
    }

    @FunctionalInterface
    public interface DiffVisitor extends Style.DiffVisitor {
        @Override void changed(String name, boolean paintOnly, boolean inherited);
    }

    public static void diff(ComputedStyle a, ComputedStyle b, DiffVisitor visitor) {
        if (a == b) return;
        StyleDiff.diff(a.style, b.style, visitor);
    }

    // ---- 派生スタイル（匿名箱・テキスト・::marker・縦書き軸・::first-line・position-try） ----

    private static final Object KEY_ANON_BLOCK = new Object(), KEY_ANON_INLINE = new Object(),
            KEY_ANON_INLINE_BLOCK = new Object(), KEY_TEXT_CHILD = new Object();
    private record DerivedAnonKey(String display) {}
    private static final class AnonHolder {
        static final BoxGeomStyle BLOCK = boxWithDisplay("block"), INLINE = boxWithDisplay("inline"),
                INLINE_BLOCK = boxWithDisplay("inline-block");
        static BoxGeomStyle boxWithDisplay(String d) {
            return BoxGeomStyle.of(new StyleReader.Source() {
                @Override public String get(int id) { return id == CssPropertyIndex.DISPLAY ? d : null; }
                @Override public float fontSizePx() { return DEFAULT_FONT_SIZE; }
                @Override public float rootFontSizePx() { return DEFAULT_FONT_SIZE; }
            });
        }
    }

    public ComputedStyle derivedAnonymous(String display) {
        Object key = switch (display) {
            case "block" -> KEY_ANON_BLOCK;
            case "inline" -> KEY_ANON_INLINE;
            case "inline-block" -> KEY_ANON_INLINE_BLOCK;
            default -> new DerivedAnonKey(display);
        };
        return paintMemo(key, k -> {
            BoxGeomStyle box = switch (display) {
                case "block" -> AnonHolder.BLOCK;
                case "inline" -> AnonHolder.INLINE;
                case "inline-block" -> AnonHolder.INLINE_BLOCK;
                default -> AnonHolder.boxWithDisplay(display);
            };
            return new ComputedStyle(Style.forAnonymous(style, box));
        });
    }

    public record TextChild(ComputedStyle style, boolean viewportDependent) {}

    public TextChild derivedTextChild() {
        return paintMemoSelf(KEY_TEXT_CHILD, p -> {
            Style ts = Style.forTextChild(p.style);
            return new TextChild(new ComputedStyle(ts), ts.facts.usesViewportUnits);
        });
    }

    public ComputedStyle derivedMarker(ComputedStyle declaredMarker) {
        return declaredMarker == null
                ? paintMemoSelf(DerivedStyles.KEY_MARKER_OWNER, DerivedStyles::markerFromOwner)
                : declaredMarker.paintMemoSelf(DerivedStyles.KEY_MARKER_DECLARED, DerivedStyles::markerFromDeclared);
    }

    public ComputedStyle derivedOrthogonalWrapper(int transformKind, String displayValue, boolean copyBoxSizes) {
        int dk = switch (displayValue) {
            case "inline-block" -> 1;
            case "flex" -> 2;
            case "grid" -> 3;
            case "table" -> 4;
            default -> 0;
        };
        return paintMemo(DerivedStyles.ORTHO_KEYS[transformKind][dk][copyBoxSizes ? 1 : 0],
                k -> DerivedStyles.orthogonalWrapper(this, transformKind, displayValue, copyBoxSizes));
    }

    private record FirstLineChildKey(ComputedStyle owner, ComputedStyle firstLine) {}

    public ComputedStyle derivedFirstLineChild(ComputedStyle ownerStyle, ComputedStyle firstLineStyle) {
        if (firstLineStyle == null || ownerStyle == null) return this;
        if (this == ownerStyle) return firstLineStyle;
        return paintMemo(new FirstLineChildKey(ownerStyle, firstLineStyle),
                k -> DerivedStyles.firstLineChild(this, ownerStyle, firstLineStyle));
    }

    public ComputedStyle derivedVerticalAxisStyle(int flow) {
        int norm = flow == WritingModes.SIDEWAYS_RL ? WritingModes.VERTICAL_RL : flow;
        if (norm < 1 || norm >= DerivedStyles.VERTICAL_AXIS_KEYS.length || DerivedStyles.VERTICAL_AXIS_KEYS[norm] == null) return this;
        return paintMemo(DerivedStyles.VERTICAL_AXIS_KEYS[norm], k -> DerivedStyles.verticalAxisStyle(this, norm));
    }

    public ComputedStyle withSizingOverrides(String w, String h, String minW, String maxW, String minH, String maxH) {
        return DerivedStyles.withSizingOverrides(this, w, h, minW, maxW, minH, maxH);
    }

    /** 登録済み CSS プロパティ名 → 固定添字（{@link StyleTable}・{@link CompiledDeclarations} 用）。 */
    static final class CssPropertyId {
        static final int NONE = -1;
        private static final String[] NAMES, KEYS, INITIALS;
        private static final int[] HASHES, VALS;
        private static final int MASK;
        static final boolean[] PAINT_ONLY, INHERITED, INHERITS_AS_LONGHAND;
        static {
            var all = PropertyRegistry.all();
            int n = all.size();
            String[] names = new String[n];
            int cap = Integer.highestOneBit(Math.max(8, n * 4 - 1)) << 1;
            String[] keys = new String[cap];
            int[] hashes = new int[cap], vals = new int[cap];
            int id = 0;
            for (PropertyRegistry.Property p : all) {
                String name = p.name();
                int h = name.hashCode(), at = (h ^ (h >>> 16)) & (cap - 1);
                while (keys[at] != null) at = (at + 1) & (cap - 1);
                keys[at] = name; hashes[at] = h; vals[at] = id; names[id++] = name;
            }
            NAMES = names; KEYS = keys; HASHES = hashes; VALS = vals; MASK = cap - 1;
            PAINT_ONLY = new boolean[n]; INHERITED = new boolean[n];
            INHERITS_AS_LONGHAND = new boolean[n]; INITIALS = new String[n];
            for (int k = 0; k < n; k++) {
                PropertyRegistry.Property p = PropertyRegistry.get(names[k]);
                PAINT_ONLY[k] = PropertyRegistry.isPaintOnly(names[k]);
                INHERITED[k] = PropertyRegistry.isInherited(names[k]);
                INHERITS_AS_LONGHAND[k] = p != null && p.inherited() && !p.isShorthand();
                INITIALS[k] = p != null ? p.initial() : null;
            }
        }
        static int count() { return NAMES.length; }
        static int of(String name) {
            if (name == null) return NONE;
            int h = name.hashCode(), at = (h ^ (h >>> 16)) & MASK;
            while (true) {
                String k = KEYS[at];
                if (k == null) return NONE;
                if (HASHES[at] == h && k.equals(name)) return VALS[at];
                at = (at + 1) & MASK;
            }
        }
        static String name(int id) { return NAMES[id]; }
    }
}
