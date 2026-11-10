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
        int x,
        int y,
        int width,
        int height,
        String text,
        int color,
        int bgColor,
        Map<String, String> events,
        int radius,
        float opacity,
        float translateX,
        float translateY,
        float scaleX,
        float scaleY,
        float rotate,
        int transitionDuration,
        AnimationSpec animDef,
        ImageFitMode fitMode,
        RenderMeta meta,
        long key
) {
    /** 互換用: {@code key} を {@link #keyOf(String)} で導く。 */
    public DisplayItem(String id, String baseNodeId, ElementType type, int x, int y, int width, int height, String text,
                       int color, int bgColor, Map<String, String> events, int radius, float opacity,
                       float translateX, float translateY, float scaleX, float scaleY, float rotate,
                       int transitionDuration, AnimationSpec animDef, ImageFitMode fitMode, RenderMeta meta) {
        this(id, baseNodeId, type, x, y, width, height, text, color, bgColor, events, radius, opacity,
                translateX, translateY, scaleX, scaleY, rotate, transitionDuration, animDef, fitMode, meta, keyOf(id));
    }

    /** 正規コンストラクタ。鍵が 0（未割り当て）なら ID から導く。 */
    public DisplayItem {
        if (key == 0L) key = keyOf(id);
    }

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
    public static final int PART_BGL_BASE = 0x7100;

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

    public DisplayItem withText(String newText) {
        return new DisplayItem(id, baseNodeId, type, x, y, width, height, newText, color, bgColor, events, radius, opacity, translateX, translateY, scaleX, scaleY, rotate, transitionDuration, animDef, fitMode, meta, key);
    }

    public DisplayItem withTranslation(float newTranslateX, float newTranslateY) {
        return new DisplayItem(id, baseNodeId, type, x, y, width, height, text, color, bgColor, events, radius, opacity, newTranslateX, newTranslateY, scaleX, scaleY, rotate, transitionDuration, animDef, fitMode, meta, key);
    }
}
