package com.nishiyu.enderframelib.core.paint.model;

import com.nishiyu.enderframelib.core.css.model.AnimationSpec;
import com.nishiyu.enderframelib.core.kernel.memory.AtomTable;
import com.nishiyu.enderframelib.core.paint.*;
import com.nishiyu.enderframelib.core.paint.model.*;

import java.util.Map;

/**
 * レンダリングパイプラインで使用されるUI要素モデル。
 * 構造化メタデータ（RenderMeta）および型付きプロパティを保持する不変レコード。
 */
public record DisplayItem(
        String id,
        String baseNodeId,
        ElementType type,
        String text,
        Map<String, String> events,
        int transitionDuration,
        AnimationSpec animDef,
        ImageFitMode fitMode,
        RenderMeta meta,
        long key,
        /** 変わりやすい欄（位置・寸法・色・不透明度・変形）の置き場。必ずある（B9 の M2）。読み出しは下のアクセサ経由。 */
        PaintCell cell
) {
    /** 互換用: {@code key} を {@link #keyOf(String)} で導く。 */
    public DisplayItem(String id, String baseNodeId, ElementType type, int x, int y, int width, int height, String text,
                       int color, int bgColor, Map<String, String> events, int radius, float opacity,
                       float translateX, float translateY, float scaleX, float scaleY, float rotate,
                       int transitionDuration, AnimationSpec animDef, ImageFitMode fitMode, RenderMeta meta) {
        this(id, baseNodeId, type, x, y, width, height, text, color, bgColor, events, radius, opacity,
                translateX, translateY, scaleX, scaleY, rotate, transitionDuration, animDef, fitMode, meta, keyOf(id));
    }

    /** すべての欄を値で渡して作る（従来の正規コンストラクタと同じ並び）。鍵が 0（未割り当て）なら ID から導く。 */
    public DisplayItem(String id, String baseNodeId, ElementType type, int x, int y, int width, int height, String text,
                       int color, int bgColor, Map<String, String> events, int radius, float opacity,
                       float translateX, float translateY, float scaleX, float scaleY, float rotate,
                       int transitionDuration, AnimationSpec animDef, ImageFitMode fitMode, RenderMeta meta, long key) {
        this(id, baseNodeId, type, text, events, transitionDuration, animDef, fitMode, meta, key,
                new PaintCell(x, y, width, height, color, bgColor, radius, opacity, translateX, translateY, scaleX, scaleY, rotate));
    }

    /** 正規コンストラクタ。セルが無い項目は作れない。鍵が 0（未割り当て）なら ID から導く。 */
    public DisplayItem {
        java.util.Objects.requireNonNull(cell, "cell");
        if (key == 0L) key = keyOf(id);
    }

    /** 塗りの欄（色・背景色・不透明度）が最後に書かれたときの通し番号。書かれていなければ 0。 */
    public int paintEpoch() { return cell.epoch; }

    /**
     * {@code next} が、塗りの 3 欄（色・背景色・不透明度）以外は同じ項目か（id・種類・文字・イベント・メタ・鍵・位置・寸法・変形がすべて同じ）。
     * 同じなら、項目を作り直さず {@link #repaintFrom} で塗りだけを書ける。
     */
    public boolean sameExceptPaint(DisplayItem next) {
        if (this == next) return true;
        PaintCell a = cell, b = next.cell;
        return key == next.key && type == next.type && transitionDuration == next.transitionDuration && fitMode == next.fitMode
                && a.x == b.x && a.y == b.y && a.width == b.width && a.height == b.height && a.radius == b.radius
                && Float.compare(a.translateX, b.translateX) == 0 && Float.compare(a.translateY, b.translateY) == 0
                && Float.compare(a.scaleX, b.scaleX) == 0 && Float.compare(a.scaleY, b.scaleY) == 0
                && Float.compare(a.rotate, b.rotate) == 0
                && java.util.Objects.equals(id, next.id) && java.util.Objects.equals(baseNodeId, next.baseNodeId)
                && java.util.Objects.equals(text, next.text) && java.util.Objects.equals(events, next.events)
                && java.util.Objects.equals(animDef, next.animDef) && java.util.Objects.equals(meta, next.meta);
    }

    /**
     * これから作ろうとしている項目の値（{@code DisplayItem} の引数）が、塗りの 3 欄以外はこの項目と同じか。同じなら、作らずに
     * {@link #repaint} で塗りだけを書いてこの項目を使い回せる（構築側の再利用。B9 の M3b）。値の比較だけで何も確保しない。
     */
    public boolean sameExceptPaint(String nid, String nBase, ElementType nType, int nx, int ny, int nw, int nh, String nText,
                                   Map<String, String> nEvents, int nRadius, float ntx, float nty, float nsx, float nsy, float nrot,
                                   int nTrans, AnimationSpec nAnim, ImageFitMode nFit, RenderMeta nMeta, long nKey) {
        PaintCell a = cell;
        if (type != nType || transitionDuration != nTrans || fitMode != nFit || key != nKey
                || a.x != nx || a.y != ny || a.width != nw || a.height != nh || a.radius != nRadius
                || Float.compare(a.translateX, ntx) != 0 || Float.compare(a.translateY, nty) != 0
                || Float.compare(a.scaleX, nsx) != 0 || Float.compare(a.scaleY, nsy) != 0 || Float.compare(a.rotate, nrot) != 0) {
            return false;
        }
        return java.util.Objects.equals(id, nid) && java.util.Objects.equals(baseNodeId, nBase)
                && java.util.Objects.equals(text, nText) && java.util.Objects.equals(events, nEvents)
                && java.util.Objects.equals(animDef, nAnim) && java.util.Objects.equals(meta, nMeta);
    }

    /** 塗りの 3 欄を書く（{@link #sameExceptPaint} が true のときだけ呼ぶ）。変わったら true。 */
    public boolean repaint(int newColor, int newBgColor, float newOpacity) {
        return cell.writePaint(newColor, newBgColor, newOpacity);
    }

    /** 塗りの 3 欄を {@code next} の値で書く（{@link #sameExceptPaint} が true のときだけ呼ぶ）。変わったら true。 */
    public boolean repaintFrom(DisplayItem next) {
        if (this == next) return false;
        return cell.writePaint(next.cell.color, next.cell.bgColor, next.cell.opacity);
    }

    /**
     * この項目をスナップショットの位置 {@code pos}（時計 {@code clock}）に入れたことを覚える。以後、塗り・移動量を書くたびに時計へ印が付く。
     * {@code ImmutableItemList} だけが呼ぶ。
     */
    public void attach(ChunkClock clock, int pos) {
        cell.clock = clock;
        cell.pos = pos;
    }

    public int x() { return cell.x; }
    public int y() { return cell.y; }
    public int width() { return cell.width; }
    public int height() { return cell.height; }
    public int color() { return cell.color; }
    public int bgColor() { return cell.bgColor; }
    public int radius() { return cell.radius; }
    public float opacity() { return cell.opacity; }
    /** 見える平行移動（基準の移動量＋描画の上だけのオフセット）。 */
    public float translateX() { return cell.offsetX == 0f ? cell.translateX : cell.translateX + cell.offsetX; }
    public float translateY() { return cell.offsetY == 0f ? cell.translateY : cell.translateY + cell.offsetY; }
    /** 表示リストの構築が決めた移動量（オフセットを含まない）。構築側が派生の項目を作るときはこちらを使う。 */
    public float baseTranslateX() { return cell.translateX; }
    public float baseTranslateY() { return cell.translateY; }
    /** 姿勢に関わる欄（移動量）が最後に書かれたときの通し番号。書かれていなければ 0。 */
    public int geomEpoch() { return cell.geomEpoch; }

    /**
     * 描画の上だけの平行移動を書く（{@code UiView.setPaintOffset}・スクロール）。項目は作り直さない。変わったら true。
     * 構築した項目に載せるだけで、{@link #baseTranslateX} は変わらない。
     */
    public boolean setPaintOffset(float dx, float dy) {
        return cell.writeOffset(dx, dy);
    }
    public float scaleX() { return cell.scaleX; }
    public float scaleY() { return cell.scaleY; }
    public float rotate() { return cell.rotate; }

    /**
     * 描画 ID を密な {@code long} の鍵へ写す（索引・アニメ状態・ヒットテストが文字列のハッシュ／比較をせずに済むように）。
     * 実体はカーネルの {@link AtomTable}（プロセス共有の文字列 → 整数 ID の辞書）。同じ文字列は常に同じ鍵、異なる文字列は異なる鍵。
     * {@code null} と空文字列は 0（未割り当て）。
     */
    public static long keyOf(String id) {
        return AtomTable.shared().intern(id);
    }

    /**
     * ノードのアリーナハンドル（nodeId）と部品番号（partOrdinal: 0〜65535）から 64-bit 整数鍵を生成する（戦略 T2）。
     * 文字列連結や {@link AtomTable#intern} のハッシュ照合を一切行わずに O(1) で即座に計算できる。
     */
    public static long keyOf(int nodeHandle, int partOrdinal) {
        if (nodeHandle <= 0) return 0L;
        return (((long) nodeHandle) << 16) | (partOrdinal & 0xFFFFL);
    }

    // ------------------------------------------------------------------ 部品番号領域（戦略 T2 / P0-3）
    // 0x0000〜0x6FFF: テキスト断片（fragIds: 0〜28671）
    // 0x7000〜0x7FFF: 主要部品（節自身・背景・枠線・フォーム・メディア・背景レイヤー）
    // 0x8000〜0xBFFF: 装飾断片（インライン断片・枠線角丸: FRAG_PART_BASE = 0x8000）
    // 0xC000〜0xDFFF: 外側の影（SHADOW_PART_BASE = 0xC000）
    // 0xE000〜0xFFFF: 内側の影（INSET_SHADOW_PART_BASE = 0xE000）

    public static final int PART_NODE = 0x7000;
    public static final int PART_BG = 0x7001;
    public static final int PART_BORDER = 0x7002;
    public static final int PART_INPUT = 0x7003;
    public static final int PART_CHECKBOX = 0x7004;
    public static final int PART_RADIO = 0x7005;
    public static final int PART_SELECT = 0x7006;
    public static final int PART_TEXTAREA = 0x7007;
    public static final int PART_CANVAS = 0x7008;
    public static final int PART_SCENE = 0x7009;
    public static final int PART_MEDIA = 0x700A;
    public static final int PART_PLACEHOLDER = 0x700B;
    public static final int PART_SCROLLBAR_V = 0x700C;
    public static final int PART_SCROLLBAR_H = 0x700D;
    public static final int PART_SCROLLBAR_TRACK_V = 0x700E;
    public static final int PART_SCROLLBAR_TRACK_H = 0x700F;
    public static final int PART_PROGRESS = 0x7010;
    public static final int PART_CIRCLE_BORDER = 0x7011;
    public static final int PART_BORDER_TOP = 0x7012;
    public static final int PART_BORDER_RIGHT = 0x7013;
    public static final int PART_BORDER_BOTTOM = 0x7014;
    public static final int PART_BORDER_LEFT = 0x7015;
    public static final int PART_GHOST_IMAGE = 0x7016;
    public static final int PART_BGL_FALLBACK = 0x7017;
    public static final int PART_SCROLLBAR_NODE_V = 0x7018;
    public static final int PART_SCROLLBAR_NODE_H = 0x7019;
    public static final int PART_SCROLL_CONTENT = 0x701A;
    public static final int PART_BGL_BASE = 0x7100;
    public static final int PART_BORDER_ROUND_BASE = 0x7180;
    public static final int PART_BORDER_IMAGE_BASE = 0x7200;

    /** 節自身（錨 NODE）の整数鍵。 */
    public static long nodeKeyOf(int nodeHandle) {
        return keyOf(nodeHandle, PART_NODE);
    }

    /** 節の背景矩形（_bg）の整数鍵。 */
    public static long bgKeyOf(int nodeHandle) {
        return keyOf(nodeHandle, PART_BG);
    }

    /** 鍵から元のノードハンドルを復元する（{@link #keyOf(int, int)} でパックされた鍵のみ）。 */
    public static int nodeHandleOf(long key) {
        return (int) (key >>> 16);
    }

    /** 鍵から部品番号を復元する（{@link #keyOf(int, int)} でパックされた鍵のみ）。 */
    public static int partOrdinalOf(long key) {
        return (int) (key & 0xFFFFL);
    }

    public String baseId() {
        return (baseNodeId != null && !baseNodeId.isEmpty()) ? baseNodeId : id;
    }

    /**
     * 描画 ID のうち持ち主の節の ID を除いた部分（背景なら {@code _bg}、節自身なら空）。
     * 節の ID の綴りに依存せず、{@link #baseId()} との差だけで決まる。
     */
    public String partSuffix() {
        String base = baseId();
        return id.startsWith(base) ? id.substring(base.length()) : "";
    }

    /** この項目が持ち主の節の部品 {@code suffix}（{@code _sb_v} など）か。 */
    public boolean isPart(String suffix) {
        String base = baseId();
        return id.length() == base.length() + suffix.length() && id.startsWith(base) && id.endsWith(suffix);
    }

    /** 型付きの付帯情報。メタが無ければ空。 */
    public RenderHints hints() {
        return meta != null ? meta.hints() : RenderHints.NONE;
    }

    public boolean hasHover() {
        return meta != null && meta.interactiveState();
    }

    public boolean hasPseudo() {
        return meta != null && meta.interactiveState();
    }

    /**
     * 今の値を固定した写し（別のセルを持つ）。塗りは項目の作り直しなしにセルへ書かれうる（B9 の M3）ので、
     * 後の描画の影響を受けない「ある時点の見た目」を残したいとき（テストの比較用の控えなど）に使う。
     */
    public DisplayItem frozen() {
        return new DisplayItem(id, baseNodeId, type, text, events, transitionDuration, animDef, fitMode, meta, key, cell.copy());
    }

    public DisplayItem withText(String newText) {
        return new DisplayItem(id, baseNodeId, type, newText, events, transitionDuration, animDef, fitMode, meta, key, cell.copy());
    }

    public DisplayItem withPosition(int newX, int newY) {
        if (x() == newX && y() == newY) return this;
        return new DisplayItem(id, baseNodeId, type, text, events, transitionDuration, animDef, fitMode, meta, key,
                cell.withPosition(newX, newY));
    }

    public DisplayItem withMeta(RenderMeta newMeta) {
        if (meta == newMeta) return this;
        return new DisplayItem(id, baseNodeId, type, text, events, transitionDuration, animDef, fitMode, newMeta, key, cell.copy());
    }

    public DisplayItem withPositionAndMeta(int newX, int newY, RenderMeta newMeta) {
        if (x() == newX && y() == newY && meta == newMeta) return this;
        return new DisplayItem(id, baseNodeId, type, text, events, transitionDuration, animDef, fitMode, newMeta, key,
                cell.withPosition(newX, newY));
    }

    public DisplayItem withBoundsAndMeta(int newX, int newY, int newWidth, int newHeight, RenderMeta newMeta) {
        if (x() == newX && y() == newY && width() == newWidth && height() == newHeight && meta == newMeta) return this;
        return new DisplayItem(id, baseNodeId, type, text, events, transitionDuration, animDef, fitMode, newMeta, key,
                cell.withBounds(newX, newY, newWidth, newHeight));
    }

    /**
     * 親相対座標を持つ項目 {@code it} の絶対位置 {@code (x, y)} を解決した {@link DisplayItem} を返す
     * （{@code relativeOrigin} を持つ祖先 {@code NODE} の位置を足し合わせる）。
     */
    public static DisplayItem resolveAbsolute(java.util.List<DisplayItem> items, DisplayItem it) {
        return it == null || items == null ? it : resolveAbsolute(items, null, it);
    }

    /** {@link #resolveAbsolute(java.util.List, DisplayItem)} の {@link java.util.Map} 版（{@code id -> DisplayItem}）。 */
    public static DisplayItem resolveAbsolute(java.util.Map<String, DisplayItem> byId, DisplayItem it) {
        return it == null || byId == null ? it : resolveAbsolute(null, byId, it);
    }

    private static DisplayItem resolveAbsolute(java.util.List<DisplayItem> items, java.util.Map<String, DisplayItem> byId, DisplayItem it) {
        int ax = it.x(), ay = it.y();
        String pid = it.hints().parentId();
        for (int guard = 0; pid != null && !pid.isEmpty() && guard < 64; guard++) {
            DisplayItem anc = null;
            if (byId != null) {
                anc = byId.get(pid);
            } else {
                for (int i = 0, n = items.size(); i < n; i++) {
                    DisplayItem cand = items.get(i);
                    if (pid.equals(cand.id())) {
                        anc = cand;
                        break;
                    }
                }
            }
            if (anc == null) break;
            if (anc.type() == ElementType.NODE && anc.hints().relativeOrigin()) {
                ax += anc.x();
                ay += anc.y();
            }
            pid = anc.hints().parentId();
        }
        return it.withPosition(ax, ay);
    }
}
