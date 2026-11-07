package com.nishiyu.enderframelib.core.kernel.memory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * 文字列を 32-bit の整数 ID（atom）に双方向で対応づける、ロックを取らない辞書。
 *
 * <p>{@code 0} は「空」。{@code 1..255} は標準の HTML タグ・属性名の予約席で、ここに載る名前は
 * プロセスが何回起動しても同じ ID になる。それ以降は初出の順に振る。
 * 各 atom は 64-bit の Bloom ビット（2 ビット立て）を持ち、要素の tag / id / class の集合は
 * ビット OR 1 回で作れる。</p>
 *
 * <p>引き当て（{@link #intern}）は {@link ConcurrentHashMap}、逆引き（{@link #nameOf}）は
 * 追記専用の段付き配列で、どちらも読み手がロックを待たない。</p>
 */
public final class AtomTable {

    /** 空文字列・未指定。 */
    public static final int NONE = 0;
    /** 予約席の最大 ID（含む）。 */
    public static final int LAST_RESERVED = 255;

    private static final int CHUNK_BITS = 10;
    private static final int CHUNK_SIZE = 1 << CHUNK_BITS;
    private static final int MAX_CHUNKS = 1 << 12; // 約 419 万 atom まで

    /** 標準の HTML タグと属性名。並びを変えると予約 ID がずれるので、追記だけにする。 */
    private static final String[] RESERVED = {
            // タグ
            "html", "head", "body", "div", "span", "p", "a", "img", "ul", "ol", "li", "table", "thead", "tbody",
            "tfoot", "tr", "td", "th", "caption", "colgroup", "col", "form", "input", "button", "select", "option",
            "optgroup", "textarea", "label", "fieldset", "legend", "h1", "h2", "h3", "h4", "h5", "h6", "header",
            "footer", "nav", "main", "section", "article", "aside", "details", "summary", "dialog", "pre", "code",
            "em", "strong", "b", "i", "u", "s", "small", "sub", "sup", "br", "hr", "svg", "path", "circle", "rect",
            "line", "polyline", "polygon", "ellipse", "g", "defs", "use", "canvas", "video", "audio", "source",
            "track", "iframe", "template", "slot", "style", "script", "link", "meta", "title", "base", "noscript",
            "progress", "meter", "output", "dl", "dt", "dd", "blockquote", "figure", "figcaption", "mark", "abbr",
            "cite", "kbd", "samp", "var", "time", "ruby", "rt", "rp", "bdi", "bdo", "wbr", "picture", "map", "area",
            "object", "embed", "param", "datalist", "menu", "address", "hgroup", "search", "data", "del", "ins", "q",
            "dfn", "tt", "big", "center", "font",
            // 属性
            "id", "class", "style", "href", "src", "alt", "title", "type", "value", "name", "placeholder",
            "disabled", "checked", "selected", "readonly", "required", "hidden", "tabindex", "role", "for", "action",
            "method", "target", "rel", "width", "height", "min", "max", "step", "rows", "cols", "colspan", "rowspan",
            "open", "multiple", "autofocus", "draggable", "lang", "dir", "content", "charset", "media", "loading",
            "srcset", "sizes", "controls", "autoplay", "loop", "muted", "poster", "preload", "data-bind", "slot-name",
            "contenteditable", "spellcheck", "maxlength", "minlength", "pattern", "accept", "autocomplete", "form",
            "viewbox", "fill", "stroke", "d", "points", "cx", "cy", "r", "x", "y", "transform"
    };

    private static final AtomTable SHARED = new AtomTable();

    private final ConcurrentHashMap<String, Integer> byName = new ConcurrentHashMap<>(1024);
    private final AtomicReferenceArray<String[]> chunks = new AtomicReferenceArray<>(MAX_CHUNKS);
    private final AtomicInteger next = new AtomicInteger(LAST_RESERVED + 1);

    /** プロセス全体で共有する辞書。DOM の節と算出スタイルは、これを通して名前を整数にする。 */
    public static AtomTable shared() {
        return SHARED;
    }

    public AtomTable() {
        int id = 1;
        for (String name : RESERVED) {
            if (byName.putIfAbsent(name, id) == null) {
                store(id, name);
                id++;
            }
        }
        if (id > LAST_RESERVED + 1) throw new IllegalStateException("予約席が足りない: " + id);
        // 余った予約席は欠番のまま（nameOf は null）。次の動的 ID は 256 から
    }

    /** 予約席に載っている名前の数（テスト用）。 */
    public static int reservedCount() {
        return (int) java.util.Arrays.stream(RESERVED).distinct().count();
    }

    /**
     * 名前の atom を返す。無ければ新しく振る。{@code null} と空文字列は {@link #NONE}。
     * 大文字小文字は区別する（呼び出し側が正規化してから渡す）。
     */
    public int intern(String name) {
        if (name == null || name.isEmpty()) return NONE;
        Integer found = byName.get(name);
        if (found != null) return found;
        Integer created = byName.computeIfAbsent(name, key -> {
            int id = next.getAndIncrement();
            if (id >= MAX_CHUNKS * CHUNK_SIZE) throw new IllegalStateException("atom の上限を超えた");
            store(id, key);
            return id;
        });
        return created;
    }

    /** 既にある atom だけを引く。無ければ {@link #NONE}（新しくは振らない）。 */
    public int lookup(String name) {
        if (name == null || name.isEmpty()) return NONE;
        Integer found = byName.get(name);
        return found != null ? found : NONE;
    }

    /** atom の名前。{@link #NONE} と未割り当ては空文字列。 */
    public String nameOf(int atom) {
        if (atom <= 0) return "";
        int ci = atom >>> CHUNK_BITS;
        if (ci >= MAX_CHUNKS) return "";
        String[] chunk = chunks.get(ci);
        if (chunk == null) return "";
        String name = chunk[atom & (CHUNK_SIZE - 1)];
        return name != null ? name : "";
    }

    /** 振られた atom の数（予約席を含み、{@link #NONE} は含まない）。 */
    public int size() {
        return byName.size();
    }

    private void store(int id, String name) {
        int ci = id >>> CHUNK_BITS;
        String[] chunk = chunks.get(ci);
        if (chunk == null) {
            chunks.compareAndSet(ci, null, new String[CHUNK_SIZE]);
            chunk = chunks.get(ci);
        }
        // 書き込み先の添字は atom ごとに一意なので、配列要素の書き込みどうしは競合しない。
        // 公開は byName の登録（happens-before）を通して読み手に届く。
        chunk[id & (CHUNK_SIZE - 1)] = name;
    }

    // ------------------------------------------------------------------
    // Bloom ビット
    // ------------------------------------------------------------------

    /** atom の 64-bit Bloom マスク。2 ビットだけ立つ（{@link #NONE} は 0）。ID だけで決まるので表を持たない。 */
    public static long bloomBits(int atom) {
        if (atom <= 0) return 0L;
        long h = mix64(atom * 0x9E3779B97F4A7C15L);
        int h1 = (int) (h & 63);
        int h2 = (int) ((h >>> 17) & 63);
        return (1L << h1) | (1L << h2);
    }

    /** {@code haystack} が {@code needle} の全ビットを含むか。含まなければ、その集合に atom 群は確実に無い。 */
    public static boolean mayContain(long haystack, long needle) {
        return (haystack & needle) == needle;
    }

    private static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
