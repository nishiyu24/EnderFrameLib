package com.nishiyu.enderframelib.core.paint.scene;

import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.model.AnimationSpec;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.model.ImageFitMode;
import com.nishiyu.enderframelib.core.paint.model.RenderMeta;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

/**
 * {@link DrawOps} を詰める組み立て器（M3b-4c-1b。生成器は {@code OpList} を通して書く）。使い回し、{@link #clear} で空にする。{@link #freeze} が不変の列を作る。
 */
public final class DrawOpsBuilder {
    int[] data = new int[256];
    int len;
    Object[] refs = new Object[32];
    int nrefs;
    int count;
    int runs;
    /** 層の中で別のメタ（同一性で 1 つずつ。命令はこの番号を持ち、{@link #freeze} で語の表にする）。 */
    RenderMeta[] metas = new RenderMeta[8];
    int nmetas;
    /** 層の持ち主: 最初に持ち主の ID を持った命令の ID の参照の番号（無ければ -1）と、最初に節の番号の鍵を持った命令の節の番号（無ければ 0）。 */
    int owner = -1, ownerHandle;
    private int[] starts = new int[32];
    // 参照の表の重複を除く開番地の表（文字列は値、ほかは同一性）。使った番地だけを消す
    private Object[] slotKey = new Object[64];
    private int[] slotVal = new int[64];
    private int[] used = new int[32];
    private int nused;
    private int nullRef = -1;

    public void clear() {
        for (int i = 0; i < nused; i++) {
            slotKey[used[i]] = null;
            slotVal[used[i]] = 0;
        }
        nused = 0;
        for (int i = 0; i < nrefs; i++) refs[i] = null;
        nrefs = 0;
        for (int i = 0; i < nmetas; i++) metas[i] = null;
        nmetas = 0;
        owner = -1;
        ownerHandle = 0;
        nullRef = -1;
        len = 0;
        count = 0;
        runs = 0;
    }

    /** 空にして、層の断片 {@code src} を写す（層の命令を作るとき。文字の命令は断片を番号で指せる）。 */
    public void beginRuns(DrawOps src) {
        clear();
        int n = src.runs;
        if (n == 0) return;
        ensure(n * DrawOps.RUN_WORDS);
        for (int i = 0; i < n; i++) {
            ComputedStyle fl = src.runFirstLine(i);
            String t = src.runText(i);
            int flRef = fl != null ? ref(fl) : -1;
            int tRef = t != null ? ref(t) : -1;
            DrawOps.writeRun(data, i * DrawOps.RUN_WORDS, src.runX(i), src.runY(i), src.runW(i), src.runH(i), src.runBaseline(i),
                    src.runStartEdge(i), src.runEndEdge(i), flRef, tRef);
        }
        len = n * DrawOps.RUN_WORDS;
        runs = n;
    }

    public int size() {
        return count;
    }

    public boolean isEmpty() {
        return count == 0;
    }

    /**
     * 命令を末尾に足す。{@code line} なら {@code meta} のベースライン・行の高さを {@code baseline}・{@code lineHeight} で上書きする。
     * {@code run} は命令を作った断片の番号（無ければ -1）で、座標・中身・行の値が断片と同じなら番号だけを持つ。
     */
    public void add(ElementType type, int flags, ImageFitMode fit, String baseNodeId, String id, long key,
                    float x, float y, float w, float h, String text, int color, int bgColor, Map<String, String> events,
                    int radius, float opacity, float tx, float ty, float sx, float sy, float rot, int trans,
                    AnimationSpec animDef, RenderMeta meta, boolean line, float baseline, float lineHeight, int run) {
        ensure(40);
        if (count == starts.length) starts = Arrays.copyOf(starts, count * 2);
        starts[count++] = len;
        int[] a = data;
        int start = len;
        int p = start + 2;
        int m = 0;
        a[start] = type.ordinal() | ((flags & 0xFF) << 8) | ((fit != null ? fit.ordinal() : 0) << 16);
        boolean fromRun = line && meta != null && sameAsRun(run, x, y, w, h, text, baseline, lineHeight);
        if (fromRun) {
            m |= DrawOps.HAS_RUN;
            a[p++] = run;
        } else {
            a[p++] = Float.floatToRawIntBits(x);
            a[p++] = Float.floatToRawIntBits(y);
            a[p++] = Float.floatToRawIntBits(w);
            a[p++] = Float.floatToRawIntBits(h);
        }
        if (key != 0L) {
            m |= DrawOps.HAS_KEY;
            long handle = key >>> 16;
            // 節の番号の鍵（文字列の鍵の印が無く、番号が int に収まる）で、番号が層の持ち主と同じなら部品の番号だけを持つ
            if (ownerHandle == 0 && (key & DisplayItem.STRING_KEY_BIT) == 0 && handle > 0 && handle <= Integer.MAX_VALUE) {
                ownerHandle = (int) handle;
            }
            if (ownerHandle != 0 && handle == ownerHandle) {
                m |= DrawOps.KEY_SHORT;
                a[p++] = (int) (key & 0xFFFFL);
            } else {
                a[p++] = (int) (key >>> 32);
                a[p++] = (int) key;
            }
        }
        if (!fromRun && !"".equals(text)) { m |= DrawOps.HAS_TEXT; a[p++] = ref(text); }
        if (color != 0) { m |= DrawOps.HAS_COLOR; a[p++] = color; }
        if (bgColor != 0) { m |= DrawOps.HAS_BG; a[p++] = bgColor; }
        if (events == null || !events.isEmpty()) { m |= DrawOps.HAS_EVENTS; a[p++] = ref(events); }
        if (radius != 0) { m |= DrawOps.HAS_RADIUS; a[p++] = radius; }
        int bits;
        if ((bits = Float.floatToRawIntBits(opacity)) != DrawOps.ONE) { m |= DrawOps.HAS_OPACITY; a[p++] = bits; }
        if ((bits = Float.floatToRawIntBits(tx)) != 0) { m |= DrawOps.HAS_TX; a[p++] = bits; }
        if ((bits = Float.floatToRawIntBits(ty)) != 0) { m |= DrawOps.HAS_TY; a[p++] = bits; }
        if ((bits = Float.floatToRawIntBits(sx)) != DrawOps.ONE) { m |= DrawOps.HAS_SX; a[p++] = bits; }
        if ((bits = Float.floatToRawIntBits(sy)) != DrawOps.ONE) { m |= DrawOps.HAS_SY; a[p++] = bits; }
        if ((bits = Float.floatToRawIntBits(rot)) != 0) { m |= DrawOps.HAS_ROT; a[p++] = bits; }
        if (trans != 0) { m |= DrawOps.HAS_TRANS; a[p++] = trans; }
        if (animDef != AnimationSpec.NONE) { m |= DrawOps.HAS_ANIM; a[p++] = ref(animDef); }
        if (meta != null) { m |= DrawOps.HAS_META; a[p++] = metaIndex(meta); }
        if (line && meta != null && !fromRun) {
            m |= DrawOps.HAS_LINE;
            a[p++] = Float.floatToRawIntBits(baseline);
            a[p++] = Float.floatToRawIntBits(lineHeight);
        }
        if (baseNodeId != null) {
            int b = ref(baseNodeId);
            if (owner < 0) owner = b;
            if (b == owner) {
                m |= DrawOps.BASE_OWNER;
            } else {
                m |= DrawOps.HAS_BASE;
                a[p++] = b;
            }
        }
        if (id != null) {
            if (baseNodeId != null && id.startsWith(baseNodeId)) {
                int bl = baseNodeId.length(), j = id.length();
                if (j == bl) {
                    m |= DrawOps.ID_SAME;
                } else {
                    while (j > bl && j - 1 >= 0 && isDigit(id.charAt(j - 1))) j--;
                    int digits = id.length() - j;
                    // 通し番号として戻せる（先頭の 0 が無く、int に収まる）ときだけ分ける
                    if (digits > 0 && digits <= 9 && (digits == 1 || id.charAt(j) != '0')) {
                        m |= DrawOps.ID_SUFFIX | DrawOps.ID_NUMBER;
                        a[p++] = ref(id.substring(bl, j));
                        a[p++] = Integer.parseInt(id, j, id.length(), 10);
                    } else {
                        m |= DrawOps.ID_SUFFIX;
                        a[p++] = ref(id.substring(bl));
                    }
                }
            } else {
                m |= DrawOps.ID_FULL;
                a[p++] = ref(id);
            }
        }
        a[start + 1] = m;
        len = p;
    }

    /** 断片 {@code run} と同じ座標・文字・行の値か（行の高さは断片の高さ）。 */
    private boolean sameAsRun(int run, float x, float y, float w, float h, String text, float baseline, float lineHeight) {
        if (run < 0 || run >= runs || text == null) return false;
        int q = run * DrawOps.RUN_WORDS;
        int[] d = data;
        if (d[q] != Float.floatToRawIntBits(x) || d[q + 1] != Float.floatToRawIntBits(y)
                || d[q + 2] != Float.floatToRawIntBits(w) || d[q + 3] != Float.floatToRawIntBits(h)
                || d[q + 4] != Float.floatToRawIntBits(baseline) || d[q + 3] != Float.floatToRawIntBits(lineHeight)) {
            return false;
        }
        int t = d[q + 6];
        return t >= 0 && (refs[t] == text || refs[t].equals(text));
    }

    /** 命令 {@code i} に印を足す。 */
    public void addFlags(int i, int add) {
        data[starts[i]] |= (add & 0xFF) << 8;
    }

    /** 命令 {@code i} のメタ（無ければ null）。 */
    public RenderMeta meta(int i) {
        int p = metaWord(i);
        return p >= 0 ? metas[data[p]] : null;
    }

    /** 命令 {@code i} のメタを替える（無かったら語を差し込む）。 */
    public void setMeta(int i, RenderMeta meta) {
        Objects.requireNonNull(meta, "meta");
        int p = metaWord(i);
        if (p >= 0) {
            data[p] = metaIndex(meta);
            return;
        }
        // メタの語を差し込む（錨に層の指定を載せるときだけの稀な道）
        int s = starts[i];
        int m = data[s + 1];
        int at = s + 2 + coordWords(m) + wordsBefore(m, DrawOps.HAS_META);
        ensure(1);
        System.arraycopy(data, at, data, at + 1, len - at);
        len++;
        data[at] = metaIndex(meta);
        data[s + 1] = m | DrawOps.HAS_META;
        for (int k = i + 1; k < count; k++) starts[k]++;
    }

    /** 不変の列にする（空なら {@link DrawOps#EMPTY}）。 */
    public DrawOps freeze() {
        if (count == 0 && runs == 0) return DrawOps.EMPTY;
        int metaStart = len;
        if (nmetas > 0) {
            // メタの表: 個数・各メタの位置・各メタの語（MetaWords）
            ensure(1 + nmetas + nmetas * MetaWords.MAX_WORDS);
            int p = metaStart + 1 + nmetas;
            data[metaStart] = nmetas;
            for (int i = 0; i < nmetas; i++) {
                data[metaStart + 1 + i] = p;
                p += MetaWords.write(metas[i], data, p, this::ref);
            }
            return new DrawOps(Arrays.copyOf(data, p), Arrays.copyOf(refs, nrefs), count, runs, metaStart, owner, ownerHandle);
        }
        return new DrawOps(Arrays.copyOf(data, len), Arrays.copyOf(refs, nrefs), count, runs, len, owner, ownerHandle);
    }

    /** メタの番号（同じ参照は同じ番号。箱の命令は続けて同じメタを使うので後ろから探す）。 */
    private int metaIndex(RenderMeta meta) {
        for (int i = nmetas - 1; i >= 0; i--) {
            if (metas[i] == meta) return i;
        }
        if (nmetas == metas.length) metas = Arrays.copyOf(metas, nmetas * 2);
        metas[nmetas] = meta;
        return nmetas++;
    }

    private int metaWord(int i) {
        int s = starts[i];
        int m = data[s + 1];
        return (m & DrawOps.HAS_META) != 0 ? s + 2 + coordWords(m) + wordsBefore(m, DrawOps.HAS_META) : -1;
    }

    /** 座標の語の数（断片を指す命令は番号の 1 語）。 */
    private static int coordWords(int m) {
        return (m & DrawOps.HAS_RUN) != 0 ? 1 : 4;
    }

    /** 欄 {@code bit} より前にある欄の語の数。 */
    private static int wordsBefore(int m, int bit) {
        int below = m & (bit - 1);
        int n = Integer.bitCount(below);
        if ((below & DrawOps.HAS_KEY) != 0 && (m & DrawOps.KEY_SHORT) == 0) n++;   // 鍵は 2 語（部品の番号だけなら 1 語）
        if ((below & DrawOps.HAS_LINE) != 0) n++;  // 行の値は 2 語
        return n;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private void ensure(int words) {
        if (len + words > data.length) data = Arrays.copyOf(data, Math.max(data.length * 2, len + words));
    }

    /** 参照の表の番号（同じ参照〔文字列は同じ値〕は同じ番号）。 */
    private int ref(Object o) {
        if (o == null) {
            if (nullRef < 0) nullRef = push(null);
            return nullRef;
        }
        if (nused * 2 >= slotKey.length) rehash();
        boolean str = o instanceof String;
        int mask = slotKey.length - 1;
        int i = mix(str ? o.hashCode() : System.identityHashCode(o)) & mask;
        while (true) {
            Object k = slotKey[i];
            if (k == null) break;
            if (k == o || (str && k instanceof String && k.equals(o))) return slotVal[i];
            i = (i + 1) & mask;
        }
        int idx = push(o);
        slotKey[i] = o;
        slotVal[i] = idx;
        if (nused == used.length) used = Arrays.copyOf(used, nused * 2);
        used[nused++] = i;
        return idx;
    }

    private int push(Object o) {
        if (nrefs == refs.length) refs = Arrays.copyOf(refs, nrefs * 2);
        refs[nrefs] = o;
        return nrefs++;
    }

    private void rehash() {
        Object[] ok = slotKey;
        int[] ov = slotVal;
        int cap = ok.length * 2;
        slotKey = new Object[cap];
        slotVal = new int[cap];
        int[] nu = new int[Math.max(used.length, cap / 2)];
        int n = 0;
        for (int j = 0; j < nused; j++) {
            int s = used[j];
            Object k = ok[s];
            int i = mix(k instanceof String ? k.hashCode() : System.identityHashCode(k)) & (cap - 1);
            while (slotKey[i] != null) i = (i + 1) & (cap - 1);
            slotKey[i] = k;
            slotVal[i] = ov[s];
            nu[n++] = i;
        }
        used = nu;
        nused = n;
    }

    private static int mix(int h) {
        return h ^ (h >>> 16);
    }
}
