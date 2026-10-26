package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.css.CssSyntax;
import com.nishiyu.enderframelib.core.css.CssSyntax.ComponentValue;
import com.nishiyu.enderframelib.core.css.CssSyntax.Token;
import com.nishiyu.enderframelib.core.css.CssSyntax.Type;
import com.nishiyu.enderframelib.core.css.LengthResolver;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code clip-path} の基本図形（CSS Shapes 1 / Masking 1）を、箱の座標（レイアウトの座標。{@code box.x} などと同じ）で解いたもの。
 * レイヤーの合成シェーダーが、箱の座標に戻した画素の位置でこの図形の符号付き距離を計算して切る。
 *
 * <p>対応: {@code inset()}（角丸の {@code round}・楕円の半径を含む）・{@code circle()}・{@code ellipse()}・{@code polygon()}
 * ({@code nonzero} / {@code evenodd})・{@code path()}（SVG パスを線分に平らにする）。基準の箱は {@code border-box}（既定）・
 * {@code padding-box}・{@code content-box}。{@code url()} の参照（SVG の {@code <clipPath>}）と {@code rect()} / {@code xywh()} は
 * 読めない（その値は無効で、切らない）。
 *
 * <p>データの並び（{@link Kind} ごと）:
 * <ul>
 *   <li>{@code INSET}: {@code [x0, y0, x1, y1, rxTL, rxTR, rxBR, rxBL, ryTL, ryTR, ryBR, ryBL]}（12 値）</li>
 *   <li>{@code CIRCLE}: {@code [cx, cy, r]}</li>
 *   <li>{@code ELLIPSE}: {@code [cx, cy, rx, ry]}</li>
 *   <li>{@code POLYGON}: 辺の列 {@code [ax, ay, bx, by, ...]}（閉じた輪の辺を全部持つ。最大 {@link #MAX_EDGES} 本）</li>
 * </ul>
 * シェーダーの {@code sdEllipseBox} と同じ式を {@link #signedDistance} に持ち、テストが形を直接確かめる。
 */
public record ClipShape(Kind kind, float[] data, boolean evenOdd) {

    public enum Kind { INSET, CIRCLE, ELLIPSE, POLYGON }

    /** シェーダーの uniform 配列の大きさ。これを越える辺は、許容誤差を広げて間引く。 */
    public static final int MAX_EDGES = 96;

    /** 値を解くための寸法。{@code x, y, w, h} は基準の箱（border-box など）。 */
    public record Context(float x, float y, float w, float h, float rootW, float rootH, float fontSize, float rootFontSize,
                          float borderX, float borderY, float borderW, float borderH,
                          float paddingX, float paddingY, float paddingW, float paddingH,
                          float contentX, float contentY, float contentW, float contentH) {
        /** 基準の箱を 3 つ（border / padding / content）与える。値の中の geometry-box キーワードで選ぶ。 */
        public static Context of(float[] border, float[] padding, float[] content, float rootW, float rootH, float fontSize, float rootFontSize) {
            return new Context(border[0], border[1], border[2], border[3], rootW, rootH, fontSize, rootFontSize,
                    border[0], border[1], border[2], border[3], padding[0], padding[1], padding[2], padding[3],
                    content[0], content[1], content[2], content[3]);
        }

        Context withBox(String box) {
            return switch (box) {
                case "padding-box" -> new Context(paddingX, paddingY, paddingW, paddingH, rootW, rootH, fontSize, rootFontSize,
                        borderX, borderY, borderW, borderH, paddingX, paddingY, paddingW, paddingH, contentX, contentY, contentW, contentH);
                case "content-box" -> new Context(contentX, contentY, contentW, contentH, rootW, rootH, fontSize, rootFontSize,
                        borderX, borderY, borderW, borderH, paddingX, paddingY, paddingW, paddingH, contentX, contentY, contentW, contentH);
                default -> this;
            };
        }

        /** 長さ・割合・数式を px にする。長さでない値（{@code banana}・単位のない 0 以外の数）は図形全体を無効にする。 */
        float length(ComponentValue v, float percentBase) {
            if (!isLength(v)) throw new IllegalArgumentException("長さでない: " + CssSyntax.serialize(v));
            return LengthResolver.parseDim(CssSyntax.serialize(v).trim(), percentBase, rootW, rootH, fontSize, rootFontSize);
        }

        private static boolean isLength(ComponentValue v) {
            if (v instanceof CssSyntax.Function f) {
                String n = f.name().toLowerCase(Locale.ROOT);
                return n.equals("calc") || n.equals("min") || n.equals("max") || n.equals("clamp");
            }
            Token t = CssSyntax.tokenOf(v);
            if (t == null) return false;
            return switch (t.type()) {
                case PERCENTAGE -> true;
                case NUMBER -> t.number() == 0;
                case DIMENSION -> LengthResolver.isLengthUnit(t.unit());
                default -> false;
            };
        }
    }

    // ==========================================
    // 解析
    // ==========================================

    /** {@code clip-path} の値を図形にする。{@code none}・空・読めない値・対応しない図形は null。 */
    public static ClipShape parse(String value, Context ctx) {
        try {
            return parseUnchecked(value, ctx);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static ClipShape parseUnchecked(String value, Context ctx) {
        if (value == null) return null;
        String v = value.trim();
        if (v.isEmpty() || v.equalsIgnoreCase("none")) return null;
        ClipShape shape = null;
        String box = "border-box";
        for (ComponentValue cv : CssSyntax.parseComponentValues(v)) {
            if (CssSyntax.isWhitespace(cv)) continue;
            if (cv instanceof CssSyntax.Function fn) {
                if (shape != null) return null;
                shape = function(fn, ctx);
                if (shape == null) return null;
            } else {
                Token t = CssSyntax.tokenOf(cv);
                if (t == null || t.type() != Type.IDENT) return null;
                String k = t.value().toLowerCase(Locale.ROOT);
                switch (k) {
                    case "border-box", "padding-box", "content-box" -> box = k;
                    case "margin-box", "fill-box", "stroke-box", "view-box" -> box = "border-box";
                    default -> {
                        return null;
                    }
                }
            }
        }
        if (shape == null) return null;
        // 基準の箱が border-box 以外なら、図形を作り直す（座標はその箱の左上が原点）
        return box.equals("border-box") ? shape : parseWithBox(v, ctx.withBox(box));
    }

    private static ClipShape parseWithBox(String value, Context boxed) {
        for (ComponentValue cv : CssSyntax.parseComponentValues(value)) {
            if (cv instanceof CssSyntax.Function fn) return function(fn, boxed);
        }
        return null;
    }

    private static ClipShape function(CssSyntax.Function fn, Context ctx) {
        String name = fn.name().toLowerCase(Locale.ROOT);
        List<ComponentValue> args = new ArrayList<>();
        for (ComponentValue a : fn.args()) if (!CssSyntax.isWhitespace(a)) args.add(a);
        return switch (name) {
            case "inset" -> inset(args, ctx);
            case "circle" -> circle(args, ctx);
            case "ellipse" -> ellipse(args, ctx);
            case "polygon" -> polygon(fn.args(), ctx);
            case "path" -> path(fn.args(), ctx);
            default -> null;
        };
    }

    private static boolean isIdent(ComponentValue v, String word) {
        Token t = CssSyntax.tokenOf(v);
        return t != null && t.type() == Type.IDENT && t.value().equalsIgnoreCase(word);
    }

    /** {@code inset(<top> [<right> [<bottom> [<left>]]] [round <border-radius>])}。 */
    private static ClipShape inset(List<ComponentValue> args, Context ctx) {
        int round = -1;
        for (int i = 0; i < args.size(); i++) {
            if (isIdent(args.get(i), "round")) {
                round = i;
                break;
            }
        }
        List<ComponentValue> offsets = round < 0 ? args : args.subList(0, round);
        if (offsets.isEmpty() || offsets.size() > 4) return null;
        float[] o = new float[4]; // 上・右・下・左
        float[] base = {ctx.h(), ctx.w(), ctx.h(), ctx.w()};
        int[] pick = switch (offsets.size()) {
            case 1 -> new int[]{0, 0, 0, 0};
            case 2 -> new int[]{0, 1, 0, 1};
            case 3 -> new int[]{0, 1, 2, 1};
            default -> new int[]{0, 1, 2, 3};
        };
        for (int i = 0; i < 4; i++) o[i] = ctx.length(offsets.get(pick[i]), base[i]);
        float x0 = ctx.x() + o[3], y0 = ctx.y() + o[0], x1 = ctx.x() + ctx.w() - o[1], y1 = ctx.y() + ctx.h() - o[2];
        if (x1 < x0) x0 = x1 = (x0 + x1) / 2f;
        if (y1 < y0) y0 = y1 = (y0 + y1) / 2f;
        float[] r = new float[8];
        if (round >= 0) {
            String radius = CssSyntax.serialize(args.subList(round + 1, args.size())).trim();
            r = LengthResolver.cornerRadiiXY(radius, x1 - x0, y1 - y0, ctx.rootW(), ctx.rootH(), ctx.fontSize(), ctx.rootFontSize());
        }
        float[] d = new float[12];
        d[0] = x0;
        d[1] = y0;
        d[2] = x1;
        d[3] = y1;
        System.arraycopy(r, 0, d, 4, 8);
        return new ClipShape(Kind.INSET, d, false);
    }

    /** {@code circle([<radius>] [at <position>])}。半径は長さ・%（基準の箱の対角の √((w²+h²)/2) に対して）・closest-side / farthest-side。 */
    private static ClipShape circle(List<ComponentValue> args, Context ctx) {
        int at = indexOfAt(args);
        List<ComponentValue> head = at < 0 ? args : args.subList(0, at);
        if (head.size() > 1) return null;
        float[] c = position(at < 0 ? List.of() : args.subList(at + 1, args.size()), ctx);
        if (c == null) return null;
        float r;
        if (head.isEmpty() || isIdent(head.get(0), "closest-side")) {
            r = Math.min(Math.min(c[0], ctx.w() - c[0]), Math.min(c[1], ctx.h() - c[1]));
        } else if (isIdent(head.get(0), "farthest-side")) {
            r = Math.max(Math.max(c[0], ctx.w() - c[0]), Math.max(c[1], ctx.h() - c[1]));
        } else {
            float base = (float) Math.sqrt((ctx.w() * ctx.w() + ctx.h() * ctx.h()) / 2.0);
            r = ctx.length(head.get(0), base);
            if (r < 0) return null;
        }
        return new ClipShape(Kind.CIRCLE, new float[]{ctx.x() + c[0], ctx.y() + c[1], Math.max(0f, r)}, false);
    }

    /** {@code ellipse([<rx> <ry>] [at <position>])}。 */
    private static ClipShape ellipse(List<ComponentValue> args, Context ctx) {
        int at = indexOfAt(args);
        List<ComponentValue> head = at < 0 ? args : args.subList(0, at);
        if (head.size() != 0 && head.size() != 2) return null;
        float[] c = position(at < 0 ? List.of() : args.subList(at + 1, args.size()), ctx);
        if (c == null) return null;
        float rx, ry;
        if (head.isEmpty()) {
            rx = Math.min(c[0], ctx.w() - c[0]);
            ry = Math.min(c[1], ctx.h() - c[1]);
        } else {
            rx = radius(head.get(0), c[0], ctx.w(), ctx);
            ry = radius(head.get(1), c[1], ctx.h(), ctx);
            if (rx < 0 || ry < 0) return null;
        }
        return new ClipShape(Kind.ELLIPSE, new float[]{ctx.x() + c[0], ctx.y() + c[1], Math.max(0f, rx), Math.max(0f, ry)}, false);
    }

    private static float radius(ComponentValue v, float center, float extent, Context ctx) {
        if (isIdent(v, "closest-side")) return Math.min(center, extent - center);
        if (isIdent(v, "farthest-side")) return Math.max(center, extent - center);
        return ctx.length(v, extent);
    }

    private static int indexOfAt(List<ComponentValue> args) {
        for (int i = 0; i < args.size(); i++) if (isIdent(args.get(i), "at")) return i;
        return -1;
    }

    /**
     * {@code <position>}（1〜4 値）を、基準の箱の左上からの px にする。省略は中央。
     * 値が 1 つなら横（縦は中央）、2 つなら横・縦（キーワードは順不同）、4 つは {@code <キーワード> <オフセット>} の対。
     */
    private static float[] position(List<ComponentValue> tokens, Context ctx) {
        if (tokens.isEmpty()) return new float[]{ctx.w() / 2f, ctx.h() / 2f};
        if (tokens.size() > 4) return null;
        Float x = null, y = null;
        List<ComponentValue> rest = new ArrayList<>(tokens);
        if (tokens.size() == 4) {
            for (int i = 0; i < 4; i += 2) {
                Token k = CssSyntax.tokenOf(tokens.get(i));
                if (k == null || k.type() != Type.IDENT) return null;
                String word = k.value().toLowerCase(Locale.ROOT);
                float off;
                switch (word) {
                    case "left" -> {
                        off = ctx.length(tokens.get(i + 1), ctx.w());
                        x = off;
                    }
                    case "right" -> {
                        off = ctx.length(tokens.get(i + 1), ctx.w());
                        x = ctx.w() - off;
                    }
                    case "top" -> {
                        off = ctx.length(tokens.get(i + 1), ctx.h());
                        y = off;
                    }
                    case "bottom" -> {
                        off = ctx.length(tokens.get(i + 1), ctx.h());
                        y = ctx.h() - off;
                    }
                    default -> {
                        return null;
                    }
                }
            }
            return x == null || y == null ? null : new float[]{x, y};
        }
        float[] axis = new float[2];
        boolean[] set = new boolean[2];
        List<ComponentValue> unplaced = new ArrayList<>();
        for (ComponentValue t : rest) {
            Token tok = CssSyntax.tokenOf(t);
            if (tok != null && tok.type() == Type.IDENT) {
                switch (tok.value().toLowerCase(Locale.ROOT)) {
                    case "left" -> put(axis, set, 0, 0f);
                    case "right" -> put(axis, set, 0, ctx.w());
                    case "top" -> put(axis, set, 1, 0f);
                    case "bottom" -> put(axis, set, 1, ctx.h());
                    case "center" -> unplaced.add(t);
                    default -> {
                        return null;
                    }
                }
            } else {
                unplaced.add(t);
            }
        }
        // 長さと center は、まだ決まっていない軸へ順に入れる（1 つなら横）
        for (ComponentValue t : unplaced) {
            int a = !set[0] ? 0 : !set[1] ? 1 : -1;
            if (a < 0) return null;
            boolean center = isIdent(t, "center");
            put(axis, set, a, center ? (a == 0 ? ctx.w() : ctx.h()) / 2f : ctx.length(t, a == 0 ? ctx.w() : ctx.h()));
        }
        if (!set[0]) axis[0] = ctx.w() / 2f;
        if (!set[1]) axis[1] = ctx.h() / 2f;
        return axis;
    }

    private static void put(float[] axis, boolean[] set, int i, float v) {
        axis[i] = v;
        set[i] = true;
    }

    /** {@code polygon([nonzero | evenodd,] <x> <y>, ...)}。 */
    private static ClipShape polygon(List<ComponentValue> raw, Context ctx) {
        List<List<ComponentValue>> parts = CssSyntax.split(raw, Type.COMMA);
        boolean evenOdd = false;
        int from = 0;
        if (!parts.isEmpty()) {
            List<ComponentValue> first = CssSyntax.trim(parts.get(0));
            if (first.size() == 1 && (isIdent(first.get(0), "nonzero") || isIdent(first.get(0), "evenodd"))) {
                evenOdd = isIdent(first.get(0), "evenodd");
                from = 1;
            }
        }
        List<float[]> points = new ArrayList<>();
        for (int i = from; i < parts.size(); i++) {
            List<ComponentValue> pair = new ArrayList<>();
            for (ComponentValue v : parts.get(i)) if (!CssSyntax.isWhitespace(v)) pair.add(v);
            if (pair.size() != 2) return null;
            points.add(new float[]{ctx.x() + ctx.length(pair.get(0), ctx.w()), ctx.y() + ctx.length(pair.get(1), ctx.h())});
        }
        if (points.size() < 3) return null;
        List<List<float[]>> loops = List.of(points);
        return new ClipShape(Kind.POLYGON, edges(loops), evenOdd);
    }

    /** {@code path([nonzero | evenodd,] "<SVG path data>")}。パスの座標は基準の箱の左上が原点。 */
    private static ClipShape path(List<ComponentValue> raw, Context ctx) {
        List<List<ComponentValue>> parts = CssSyntax.split(raw, Type.COMMA);
        boolean evenOdd = false;
        int from = 0;
        if (parts.size() == 2) {
            List<ComponentValue> first = CssSyntax.trim(parts.get(0));
            if (first.size() == 1 && (isIdent(first.get(0), "nonzero") || isIdent(first.get(0), "evenodd"))) {
                evenOdd = isIdent(first.get(0), "evenodd");
                from = 1;
            } else {
                return null;
            }
        } else if (parts.size() != 1) {
            return null;
        }
        List<ComponentValue> d = CssSyntax.trim(parts.get(from));
        if (d.size() != 1) return null;
        Token t = CssSyntax.tokenOf(d.get(0));
        if (t == null || t.type() != Type.STRING) return null;
        // 許容誤差 0.25px から始め、辺が多すぎれば倍々にして収める
        for (float tolerance = 0.25f; tolerance < 64f; tolerance *= 2f) {
            List<List<float[]>> loops = SvgPath.flatten(t.value(), tolerance);
            if (loops == null) return null;
            int count = 0;
            for (List<float[]> loop : loops) count += loop.size() >= 2 ? loop.size() : 0;
            if (count == 0) return null;
            if (count <= MAX_EDGES) {
                for (List<float[]> loop : loops) for (float[] p : loop) {
                    p[0] += ctx.x();
                    p[1] += ctx.y();
                }
                return new ClipShape(Kind.POLYGON, edges(loops), evenOdd);
            }
        }
        return null;
    }

    /** 閉じた輪の列を、辺の列にする（点の並びの最後から最初へも辺を張る）。上限を越えたら間引く。 */
    private static float[] edges(List<List<float[]>> loops) {
        int count = 0;
        for (List<float[]> loop : loops) count += loop.size();
        List<List<float[]>> use = loops;
        if (count > MAX_EDGES) {
            use = new ArrayList<>();
            double keep = (double) MAX_EDGES / count;
            for (List<float[]> loop : loops) {
                int n = Math.max(3, (int) Math.floor(loop.size() * keep));
                List<float[]> thinned = new ArrayList<>(n);
                for (int i = 0; i < n; i++) thinned.add(loop.get(Math.min(loop.size() - 1, (int) ((long) i * loop.size() / n))));
                use.add(thinned);
            }
        }
        List<Float> out = new ArrayList<>();
        for (List<float[]> loop : use) {
            for (int i = 0; i < loop.size(); i++) {
                float[] a = loop.get(i), b = loop.get((i + 1) % loop.size());
                out.add(a[0]);
                out.add(a[1]);
                out.add(b[0]);
                out.add(b[1]);
            }
        }
        float[] arr = new float[out.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = out.get(i);
        return arr;
    }

    // ==========================================
    // 符号付き距離（シェーダーと同じ式）
    // ==========================================

    /** 点 {@code (x, y)} から図形までの符号付き距離（図形の内側が負）。 */
    public float signedDistance(float x, float y) {
        return switch (kind) {
            case INSET -> {
                float cx = (data[0] + data[2]) / 2f, cy = (data[1] + data[3]) / 2f;
                float hx = Math.max(0.001f, (data[2] - data[0]) / 2f), hy = Math.max(0.001f, (data[3] - data[1]) / 2f);
                yield sdEllipseBox(x - cx, y - cy, hx, hy,
                        new float[]{data[4], data[5], data[6], data[7]}, new float[]{data[8], data[9], data[10], data[11]});
            }
            case CIRCLE -> (float) Math.hypot(x - data[0], y - data[1]) - data[2];
            case ELLIPSE -> sdEllipse(x - data[0], y - data[1], Math.max(data[2], 0.001f), Math.max(data[3], 0.001f));
            case POLYGON -> {
                float min = Float.MAX_VALUE;
                int winding = 0, crossings = 0;
                for (int i = 0; i + 3 < data.length; i += 4) {
                    float ax = data[i], ay = data[i + 1], bx = data[i + 2], by = data[i + 3];
                    min = Math.min(min, segmentDistance(x, y, ax, ay, bx, by));
                    if ((ay <= y) != (by <= y)) {
                        float t = (y - ay) / (by - ay);
                        if (x < ax + t * (bx - ax)) {
                            crossings++;
                            winding += by > ay ? 1 : -1;
                        }
                    }
                }
                boolean inside = evenOdd ? (crossings & 1) == 1 : winding != 0;
                yield inside ? -min : min;
            }
        };
    }

    private static float segmentDistance(float px, float py, float ax, float ay, float bx, float by) {
        float dx = bx - ax, dy = by - ay;
        float len2 = dx * dx + dy * dy;
        float t = len2 <= 0f ? 0f : Math.max(0f, Math.min(1f, ((px - ax) * dx + (py - ay) * dy) / len2));
        return (float) Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
    }

    /** 楕円までの距離の近似（勾配で正規化。円なら厳密）。 */
    static float sdEllipse(float px, float py, float rx, float ry) {
        float qx = Math.abs(px) / rx, qy = Math.abs(py) / ry;
        float k0 = (float) Math.hypot(qx, qy);
        float k1 = (float) Math.hypot(px / (rx * rx), py / (ry * ry));
        if (k1 < 1e-9f) return -Math.min(rx, ry);
        return k0 * (k0 - 1f) / k1;
    }

    /** 楕円の角を持つ角丸矩形までの距離（シェーダーの {@code sdEllipseBox}）。{@code rx / ry} は左上・右上・右下・左下。 */
    static float sdEllipseBox(float px, float py, float bx, float by, float[] rx, float[] ry) {
        int corner = px > 0 ? (py > 0 ? 2 : 1) : (py > 0 ? 3 : 0);
        float rrx = Math.min(rx[corner], bx), rry = Math.min(ry[corner], by);
        float ax = Math.abs(px) - bx, ay = Math.abs(py) - by;
        if (rrx <= 0f || rry <= 0f) {
            return (float) Math.hypot(Math.max(ax, 0f), Math.max(ay, 0f)) + Math.min(Math.max(ax, ay), 0f);
        }
        float qx = ax + rrx, qy = ay + rry;
        if (qx > 0f && qy > 0f) {
            float k0 = (float) Math.hypot(qx / rrx, qy / rry);
            float k1 = (float) Math.hypot(qx / (rrx * rrx), qy / (rry * rry));
            return k0 * (k0 - 1f) / Math.max(k1, 1e-6f);
        }
        return Math.max(qx - rrx, qy - rry);
    }

    // ==========================================
    // SVG のパスデータ（M L H V C S Q T A Z とその小文字）を、輪ごとの折れ線に平らにする
    // ==========================================

    private static final class SvgPath {
        private final String d;
        private int pos;

        private SvgPath(String d) {
            this.d = d;
        }

        /** 輪（点の並び）の列。読めないデータは null。開いた部分パスは塗りでは閉じるので、そのまま輪として返す。 */
        static List<List<float[]>> flatten(String data, float tolerance) {
            try {
                return new SvgPath(data).run(tolerance);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        private List<List<float[]>> run(float tol) {
            List<List<float[]>> loops = new ArrayList<>();
            List<float[]> cur = null;
            float x = 0, y = 0, sx = 0, sy = 0, lastCx = 0, lastCy = 0;
            char cmd = 0, prev = 0;
            while (true) {
                skipSeparators();
                if (pos >= d.length()) break;
                char c = d.charAt(pos);
                if (Character.isLetter(c)) {
                    cmd = c;
                    pos++;
                    if (cmd == 'Z' || cmd == 'z') {
                        if (cur != null) {
                            x = sx;
                            y = sy;
                            cur = null;
                        }
                        prev = cmd;
                        continue;
                    }
                } else if (cmd == 0) {
                    throw new IllegalArgumentException("コマンドが無い");
                } else if (cmd == 'M') {
                    cmd = 'L';
                } else if (cmd == 'm') {
                    cmd = 'l';
                }
                boolean rel = Character.isLowerCase(cmd);
                switch (Character.toUpperCase(cmd)) {
                    case 'M' -> {
                        float nx = num() + (rel ? x : 0), ny = num() + (rel ? y : 0);
                        cur = new ArrayList<>();
                        loops.add(cur);
                        cur.add(new float[]{nx, ny});
                        x = sx = nx;
                        y = sy = ny;
                    }
                    case 'L', 'H', 'V' -> {
                        float nx = x, ny = y;
                        char u = Character.toUpperCase(cmd);
                        if (u == 'L') {
                            nx = num() + (rel ? x : 0);
                            ny = num() + (rel ? y : 0);
                        } else if (u == 'H') {
                            nx = num() + (rel ? x : 0);
                        } else {
                            ny = num() + (rel ? y : 0);
                        }
                        cur = ensure(loops, cur, x, y);
                        cur.add(new float[]{nx, ny});
                        x = nx;
                        y = ny;
                    }
                    case 'C', 'S' -> {
                        float x1, y1;
                        if (Character.toUpperCase(cmd) == 'C') {
                            x1 = num() + (rel ? x : 0);
                            y1 = num() + (rel ? y : 0);
                        } else {
                            boolean wasCubic = Character.toUpperCase(prev) == 'C' || Character.toUpperCase(prev) == 'S';
                            x1 = wasCubic ? 2 * x - lastCx : x;
                            y1 = wasCubic ? 2 * y - lastCy : y;
                        }
                        float x2 = num() + (rel ? x : 0), y2 = num() + (rel ? y : 0);
                        float nx = num() + (rel ? x : 0), ny = num() + (rel ? y : 0);
                        cur = ensure(loops, cur, x, y);
                        cubic(cur, x, y, x1, y1, x2, y2, nx, ny, tol);
                        lastCx = x2;
                        lastCy = y2;
                        x = nx;
                        y = ny;
                    }
                    case 'Q', 'T' -> {
                        float x1, y1;
                        if (Character.toUpperCase(cmd) == 'Q') {
                            x1 = num() + (rel ? x : 0);
                            y1 = num() + (rel ? y : 0);
                        } else {
                            boolean wasQuad = Character.toUpperCase(prev) == 'Q' || Character.toUpperCase(prev) == 'T';
                            x1 = wasQuad ? 2 * x - lastCx : x;
                            y1 = wasQuad ? 2 * y - lastCy : y;
                        }
                        float nx = num() + (rel ? x : 0), ny = num() + (rel ? y : 0);
                        cur = ensure(loops, cur, x, y);
                        cubic(cur, x, y, x + 2f / 3f * (x1 - x), y + 2f / 3f * (y1 - y), nx + 2f / 3f * (x1 - nx), ny + 2f / 3f * (y1 - ny), nx, ny, tol);
                        lastCx = x1;
                        lastCy = y1;
                        x = nx;
                        y = ny;
                    }
                    case 'A' -> {
                        float rx = num(), ry = num(), rot = num();
                        boolean large = flag(), sweep = flag();
                        float nx = num() + (rel ? x : 0), ny = num() + (rel ? y : 0);
                        cur = ensure(loops, cur, x, y);
                        arc(cur, x, y, rx, ry, rot, large, sweep, nx, ny, tol);
                        x = nx;
                        y = ny;
                    }
                    default -> throw new IllegalArgumentException("知らないコマンド: " + cmd);
                }
                prev = cmd;
            }
            loops.removeIf(l -> l.size() < 2);
            return loops;
        }

        /** コマンドの前に M が無いパス（Z の後の続きなど）でも、現在点から輪を始める。 */
        private static List<float[]> ensure(List<List<float[]>> loops, List<float[]> cur, float x, float y) {
            if (cur != null) return cur;
            List<float[]> loop = new ArrayList<>();
            loop.add(new float[]{x, y});
            loops.add(loop);
            return loop;
        }

        private static void cubic(List<float[]> out, float x0, float y0, float x1, float y1, float x2, float y2, float x3, float y3, float tol) {
            double dd = Math.max(Math.hypot(x0 - 2 * x1 + x2, y0 - 2 * y1 + y2), Math.hypot(x1 - 2 * x2 + x3, y1 - 2 * y2 + y3));
            int n = (int) Math.max(1, Math.min(48, Math.ceil(Math.sqrt(3.0 * dd / (4.0 * tol)))));
            for (int i = 1; i <= n; i++) {
                double t = (double) i / n, u = 1 - t;
                out.add(new float[]{
                        (float) (u * u * u * x0 + 3 * u * u * t * x1 + 3 * u * t * t * x2 + t * t * t * x3),
                        (float) (u * u * u * y0 + 3 * u * u * t * y1 + 3 * u * t * t * y2 + t * t * t * y3)});
            }
        }

        /** 終点つきの弧（SVG 1.1 付録 F.6）を中心表現にして、折れ線にする。 */
        private static void arc(List<float[]> out, float x0, float y0, float rx, float ry, float rotDeg, boolean large, boolean sweep,
                                float x1, float y1, float tol) {
            if (x0 == x1 && y0 == y1) return;
            rx = Math.abs(rx);
            ry = Math.abs(ry);
            if (rx == 0 || ry == 0) {
                out.add(new float[]{x1, y1});
                return;
            }
            double phi = Math.toRadians(rotDeg), cos = Math.cos(phi), sin = Math.sin(phi);
            double dx = (x0 - x1) / 2.0, dy = (y0 - y1) / 2.0;
            double x1p = cos * dx + sin * dy, y1p = -sin * dx + cos * dy;
            double lambda = x1p * x1p / ((double) rx * rx) + y1p * y1p / ((double) ry * ry);
            if (lambda > 1) {
                double s = Math.sqrt(lambda);
                rx *= s;
                ry *= s;
            }
            double num = (double) rx * rx * ry * ry - (double) rx * rx * y1p * y1p - (double) ry * ry * x1p * x1p;
            double den = (double) rx * rx * y1p * y1p + (double) ry * ry * x1p * x1p;
            double coef = (large == sweep ? -1 : 1) * Math.sqrt(Math.max(0, num / den));
            double cxp = coef * rx * y1p / ry, cyp = -coef * ry * x1p / rx;
            double cx = cos * cxp - sin * cyp + (x0 + x1) / 2.0, cy = sin * cxp + cos * cyp + (y0 + y1) / 2.0;
            double theta1 = Math.atan2((y1p - cyp) / ry, (x1p - cxp) / rx);
            double theta2 = Math.atan2((-y1p - cyp) / ry, (-x1p - cxp) / rx);
            double delta = theta2 - theta1;
            if (!sweep && delta > 0) delta -= 2 * Math.PI;
            else if (sweep && delta < 0) delta += 2 * Math.PI;
            double r = Math.max(rx, ry);
            double step = r <= tol ? Math.PI / 2 : 2 * Math.acos(Math.max(0.0, 1 - tol / r));
            int n = (int) Math.max(1, Math.min(96, Math.ceil(Math.abs(delta) / Math.max(step, 0.02))));
            for (int i = 1; i <= n; i++) {
                double a = theta1 + delta * i / n;
                double ex = rx * Math.cos(a), ey = ry * Math.sin(a);
                out.add(new float[]{(float) (cos * ex - sin * ey + cx), (float) (sin * ex + cos * ey + cy)});
            }
        }

        private void skipSeparators() {
            while (pos < d.length() && (Character.isWhitespace(d.charAt(pos)) || d.charAt(pos) == ',')) pos++;
        }

        private boolean flag() {
            skipSeparators();
            if (pos >= d.length()) throw new IllegalArgumentException("フラグが足りない");
            char c = d.charAt(pos++);
            if (c != '0' && c != '1') throw new IllegalArgumentException("フラグでない");
            return c == '1';
        }

        private float num() {
            skipSeparators();
            int start = pos;
            if (pos < d.length() && (d.charAt(pos) == '+' || d.charAt(pos) == '-')) pos++;
            boolean digits = false, dot = false;
            while (pos < d.length()) {
                char c = d.charAt(pos);
                if (c >= '0' && c <= '9') {
                    digits = true;
                    pos++;
                } else if (c == '.' && !dot) {
                    dot = true;
                    pos++;
                } else {
                    break;
                }
            }
            if (digits && pos < d.length() && (d.charAt(pos) == 'e' || d.charAt(pos) == 'E')) {
                int save = pos++;
                if (pos < d.length() && (d.charAt(pos) == '+' || d.charAt(pos) == '-')) pos++;
                int expStart = pos;
                while (pos < d.length() && Character.isDigit(d.charAt(pos))) pos++;
                if (expStart == pos) pos = save;
            }
            if (!digits) throw new IllegalArgumentException("数でない");
            return Float.parseFloat(d.substring(start, pos));
        }
    }
}
