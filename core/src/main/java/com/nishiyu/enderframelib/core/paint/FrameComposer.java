package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.css.model.Keyframes;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.PaintOffsets;
import com.nishiyu.enderframelib.core.paint.model.ScrollContentInfo;
import com.nishiyu.enderframelib.core.paint.scene.DynamicSceneState;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 描画器の側でフレームのシーンから表示項目の列を組む（M3b-4c-2b-2b。以前はビューが変化のたびに組んで持っていた）。描画器の準備
 * （{@link FrameDamage#prepare}）が 1 フレームに 1 度呼び、組んだ列を準備した値 {@link PreparedFrame} に持たせる。
 *
 * <p>中身を 1 つの節にまとめるスクロールコンテナはスクロール 0 で組み（{@link SceneDisplayListAdapter#emitUnscrolled}）、今のスクロール量との差は
 * 中身の節の平行移動として足す（{@link ScrollTranslation}）。</p>
 *
 * <p><b>カリング</b>（M3b-4c-2b-2c）: 描くフレームはビューポートにかかるチャンクだけを組む（包含矩形は塗る範囲。中身をスクロール 0 で組む
 * コンテナの中は今のスクロール量だけずらした範囲で選ぶ）。列は組んだときのスクロール量でだけ正しいので、どのスクロールが動いても組み直す
 * （余白を持って越えたら組み直す形は採らない。計画書 §3.4 の 2b-2c のスクロールの方針）。利用者の平行移動があるフレームは描く位置が組む位置と
 * 違うので全部を組む（当たり判定のカリングと同じ）。</p>
 *
 * <p>前のフレームに組んだ列は、シーンの根が同じ実体で、ビューポート・カリングの有無が同じで、スクロール量が組んだときのまま（カリングしないときは
 * まとめていないコンテナ〔根・html・body・アンカーが追従するもの・1 つの節にまとめられなかったもの〕のスクロール量だけを見る）なら使い回す
 * （計画書 §2.3 の表の L4c）。</p>
 *
 * <p>組み方の入力（キーフレーム・装置の画素比・ビューポート）はシーンの添え物 {@link SceneSource} から読む。描画器が 1 つ持つ。スレッドセーフではない。</p>
 */
public final class FrameComposer {
    /**
     * 組んだ列と、そのフレームの平行移動（利用者の分にスクロールの中身の移動を足した不変の表）・中身をまとめたスクロールコンテナ・列の階層
     * （項目ごとの親の錨の位置と保持層の範囲。シーンの無いフレームは null で、描く側が項目の鍵から作る）・ホストが描く中身（重ね描きの分を含み、
     * 描かない節の分を除く。M3b-4c-2b-3b-2b-2）。
     */
    public record Composed(List<DisplayItem> items, PaintOffsets offsets, List<ScrollContentInfo> contents, FrameStructure structure,
                           List<com.nishiyu.enderframelib.core.paint.model.HostContent> hostContents) {
        public Composed(List<DisplayItem> items, PaintOffsets offsets, List<ScrollContentInfo> contents, FrameStructure structure) {
            this(items, offsets, contents, structure, List.of());
        }
    }

    private SceneDisplayListAdapter adapter;
    private Map<String, Keyframes> keyframes;
    private float dpr = Float.NaN;
    private int width = -1, height = -1;
    /** 前に組んだシーンの根（シーンは不変の木なので、根が同じ実体なら組む結果も同じ）。 */
    private com.nishiyu.enderframelib.core.paint.scene.SceneLayer lastRoot;
    private List<DisplayItem> lastItems = List.of();
    private List<ScrollContentInfo> lastContents = List.of();
    private FrameStructure lastStructure = FrameStructure.EMPTY;
    private List<com.nishiyu.enderframelib.core.paint.model.HostContent> lastHost = List.of();
    /** 前に組んだときにホストが描く中身を聞いた相手。 */
    private com.nishiyu.enderframelib.core.paint.model.HostContent.Filter lastHostTextures = com.nishiyu.enderframelib.core.paint.model.HostContent.Filter.NONE;
    private Map<HtmlNode, float[]> scrollAtBuild = Map.of();
    private final Set<HtmlNode> handled = Collections.newSetFromMap(new IdentityHashMap<>());
    /** 前に組んだ列がカリングしたか。 */
    private boolean lastCulled;
    private long builds;

    /** シーンの無いフレームはその列のまま、シーンのあるフレームはシーンから組んだ列（ホストが描く中身は聞かない）。 */
    public Composed compose(RenderFrame f) {
        return compose(f, com.nishiyu.enderframelib.core.paint.model.HostContent.Filter.NONE);
    }

    /**
     * {@link #compose(RenderFrame)} の、組みながらホスト {@code host} が描く中身（{@link com.nishiyu.enderframelib.core.paint.model.HostContent.Filter#wants}）を集める版
     * （M3b-4c-2b-3b-2b-2。ホストは表示項目の列を読まず、準備した値の {@code hostContents} だけを見る）。
     */
    public Composed compose(RenderFrame f, com.nishiyu.enderframelib.core.paint.model.HostContent.Filter host) {
        if (host == null) host = com.nishiyu.enderframelib.core.paint.model.HostContent.Filter.NONE;
        SceneSource s = f.scene();
        if (s == null) return new Composed(f.items(), f.offsets(), List.of(), null, hostContents(List.of(), f, host));
        DynamicSceneState dyn = s.dynamic();
        int w = Math.round(dyn.viewportW), h = Math.round(dyn.viewportH);
        if (adapter == null || s.keyframes() != keyframes || Float.compare(s.devicePixelRatio(), dpr) != 0) {
            adapter = new SceneDisplayListAdapter(w, h, s.keyframes());
            adapter.setDevicePixelRatio(s.devicePixelRatio());
            keyframes = s.keyframes();
            dpr = s.devicePixelRatio();
            width = w;
            height = h;
            lastRoot = null;
        } else if (w != width || h != height) {
            adapter.setViewport(w, h);
            width = w;
            height = h;
            lastRoot = null;
        }
        ScrollState scroll = dyn.scroll;
        boolean cull = f.offsets().isEmpty();
        if (s.scene().root != lastRoot || cull != lastCulled || host != lastHostTextures
                || scroll.hasUncheckedChange(scrollAtBuild, cull ? Set.of() : handled)) {
            adapter.setHostTextures(host);
            long t0 = com.nishiyu.enderframelib.core.diag.StageProfiler.begin(com.nishiyu.enderframelib.core.diag.PipelineStage.DISPLAY_LIST);
            try {
                float[] view = cull ? new float[]{0f, 0f, w, h} : null;
                lastItems = Collections.unmodifiableList(adapter.emitUnscrolled(s.scene(), dyn, view));
            } finally {
                com.nishiyu.enderframelib.core.diag.StageProfiler.end(com.nishiyu.enderframelib.core.diag.PipelineStage.DISPLAY_LIST, t0);
            }
            lastContents = adapter.scrollContents();
            lastStructure = adapter.structure();
            lastHost = adapter.hostContents();
            lastHostTextures = host;
            handled.clear();
            for (int i = 0, n = lastContents.size(); i < n; i++) handled.add(lastContents.get(i).node());
            scrollAtBuild = scroll.snapshot();
            lastRoot = s.scene().root;
            lastCulled = cull;
            builds++;
        }
        return new Composed(lastItems, ScrollTranslation.apply(f.offsets(), lastContents, scroll), lastContents, lastStructure,
                hostContents(lastHost, f, host));
    }

    /**
     * このフレームのホストが描く中身: シーンから組んだ分 {@code scene}（シーンの無いフレームは列の分）に重ね描きの分を足し、描かない節の分を除く。
     */
    private static List<com.nishiyu.enderframelib.core.paint.model.HostContent> hostContents(List<com.nishiyu.enderframelib.core.paint.model.HostContent> scene, RenderFrame f, com.nishiyu.enderframelib.core.paint.model.HostContent.Filter host) {
        if (host == com.nishiyu.enderframelib.core.paint.model.HostContent.Filter.NONE) return List.of();
        List<com.nishiyu.enderframelib.core.paint.model.HostContent> out = scene;
        List<DisplayItem> extra = f.scene() == null ? f.items() : List.of();
        for (List<DisplayItem> list : List.of(extra, f.overlays())) {
            for (int i = 0, n = list.size(); i < n; i++) {
                DisplayItem it = list.get(i);
                if (!com.nishiyu.enderframelib.core.paint.model.HostContent.asksHost(it) || !host.wants(it.type(), it.text(),
                        com.nishiyu.enderframelib.core.paint.model.HostContent.fontFamilyOf(it), it.events() != null ? it.events() : Map.of())) continue;
                if (out == scene) out = new java.util.ArrayList<>(scene);
                out.add(com.nishiyu.enderframelib.core.paint.model.HostContent.of(it));
            }
        }
        if (f.exclude() != null && f.scene() != null && !out.isEmpty()) {
            it.unimi.dsi.fastutil.ints.IntSet gone = f.exclude().nodesIn(f.scene().scene());
            if (!gone.isEmpty()) {
                List<com.nishiyu.enderframelib.core.paint.model.HostContent> kept = new java.util.ArrayList<>(out.size());
                for (com.nishiyu.enderframelib.core.paint.model.HostContent c : out) if (!gone.contains(c.owner())) kept.add(c);
                out = kept;
            }
        }
        return out;
    }

    /** 列を組んだ回数の累計（使い回しの確かめ用）。 */
    public long builds() { return builds; }

    /** 前に組んだ列を捨てる（次は必ず組む）。 */
    public void reset() {
        lastRoot = null;
        lastItems = List.of();
        lastContents = List.of();
        lastStructure = FrameStructure.EMPTY;
        lastHost = List.of();
        scrollAtBuild = Map.of();
        handled.clear();
    }
}
