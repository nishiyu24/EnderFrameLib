package com.nishiyu.enderframelib.core.kernel;

/**
 * エンジンの設定の読み口（ENGINE_REARCHITECTURE_PLAN S1 ①）。{@code enderframe.*} のシステムプロパティを読むのはこのクラスだけで、
 * 各値はクラスの初期化のときに 1 回だけ読む（JVM の起動引数 {@code -D} で決める。実行中に {@code System.setProperty} しても変わらない）。
 *
 * <p>各クラスの切り替えの欄（{@code LayoutBox.cutoffEnabled} など）は、ここの値を初期値に持つ。テストはその欄を直接切り替える
 * （面ごとのフラグへ移すのは S1 ③）。キーの種類と説明の一覧は {@code core/src/test/resources/system-properties.txt}、
 * 読み口がここ 1 つであることは {@code SystemPropertyRatchetTest} が見張る。新しい設定はここに足す。</p>
 */
public final class EngineFlags {
    private EngineFlags() { }

    // ---- 近道（既定オン。キーを立てると切る）。:core:shortcutMatrix が 1 つずつ・2 つずつ切って、結果が変わらないことを確かめる ----

    /** {@code enderframe.css.noshare}: ComputedStyle.SHARE_ENABLED（算出スタイルの共有）（使う所: {@code ComputedStyle}） */
    public static final boolean STYLE_SHARE = !Boolean.getBoolean("enderframe.css.noshare");

    /** {@code enderframe.layout.noblockpercentkey}: LayoutBox.blockPercentFreeEnabled（使う所: {@code LayoutBox}） */
    public static final boolean BLOCK_PERCENT_FREE = !Boolean.getBoolean("enderframe.layout.noblockpercentkey");

    /** {@code enderframe.layout.nocutoff}: LayoutBox.cutoffEnabled（使う所: {@code LayoutBox}） */
    public static final boolean CUTOFF = !Boolean.getBoolean("enderframe.layout.nocutoff");

    /** {@code enderframe.layout.nodefiniteleaf}: LayoutBox.definiteLeafReuse（使う所: {@code LayoutBox}） */
    public static final boolean DEFINITE_LEAF_REUSE = !Boolean.getBoolean("enderframe.layout.nodefiniteleaf");

    /** {@code enderframe.layout.nofitreuse}: LayoutBox.fitReuseEnabled（使う所: {@code LayoutBox}） */
    public static final boolean FIT_REUSE = !Boolean.getBoolean("enderframe.layout.nofitreuse");

    /** {@code enderframe.layout.noflexskip}: FlexLayoutEngine.skipSameWidthRemeasure（使う所: {@code FlexLayoutEngine}） */
    public static final boolean FLEX_SKIP_SAME_WIDTH = !Boolean.getBoolean("enderframe.layout.noflexskip");

    /** {@code enderframe.layout.nofloatmemo}: BlockLayoutEngine.floatMemoEnabled（使う所: {@code BlockLayoutEngine}） */
    public static final boolean FLOAT_MEMO = !Boolean.getBoolean("enderframe.layout.nofloatmemo");

    /** {@code enderframe.layout.nofreshreuse}: RenderPipeline.FRESH_LAYOUT_PASS_REUSE（使う所: {@code RenderPipeline}） */
    public static final boolean FRESH_LAYOUT_PASS_REUSE = !Boolean.getBoolean("enderframe.layout.nofreshreuse");

    /** {@code enderframe.layout.nolazythrough}: LayoutBox.lazyThroughIneligible（使う所: {@code LayoutBox}） */
    public static final boolean LAZY_THROUGH_INELIGIBLE = !Boolean.getBoolean("enderframe.layout.nolazythrough");

    /** {@code enderframe.layout.noparallel}: LayoutTaskPool.parallelEnabled（B2 の並列化）（使う所: {@code LayoutTaskPool}） */
    public static final boolean LAYOUT_PARALLEL = !Boolean.getBoolean("enderframe.layout.noparallel");

    /** {@code enderframe.layout.noresizeskip}: RenderPipeline.RESIZE_SKIP_REBUILD（Q7）（使う所: {@code RenderPipeline}） */
    public static final boolean RESIZE_SKIP_REBUILD = !Boolean.getBoolean("enderframe.layout.noresizeskip");

    /** {@code enderframe.layout.nosamerun}: LayoutBox.samePassReuse（使う所: {@code LayoutBox}） */
    public static final boolean SAME_PASS_REUSE = !Boolean.getBoolean("enderframe.layout.nosamerun");

    /** {@code enderframe.layout.noshift}: LayoutBox.SHIFT_ENABLED（部分木の平行移動）（使う所: {@code LayoutBox}） */
    public static final boolean SHIFT = !Boolean.getBoolean("enderframe.layout.noshift");

    /** {@code enderframe.layout.notentative}: LayoutBox.tentativeReuse（使う所: {@code LayoutBox}） */
    public static final boolean TENTATIVE_REUSE = !Boolean.getBoolean("enderframe.layout.notentative");

    /** {@code enderframe.layout.noviewportwidthfree}: LayoutBox.viewportWidthFreeReuse（Q6）（使う所: {@code LayoutBox}） */
    public static final boolean VIEWPORT_WIDTH_FREE_REUSE = !Boolean.getBoolean("enderframe.layout.noviewportwidthfree");

    /** {@code enderframe.layout.nowidthreuse}: LayoutBox.widthIndependentReuse（使う所: {@code LayoutBox}） */
    public static final boolean WIDTH_INDEPENDENT_REUSE = !Boolean.getBoolean("enderframe.layout.nowidthreuse");

    /** {@code enderframe.paint.nochunks}: PaintOrder.chunksEnabled（使う所: {@code PaintOrder}） */
    public static final boolean PAINT_CHUNKS = !Boolean.getBoolean("enderframe.paint.nochunks");

    /** {@code enderframe.paint.nocullrange}: PaintSceneDriver.cullRecordedRanges（使う所: {@code PaintSceneDriver}） */
    public static final boolean CULL_RECORDED_RANGES = !Boolean.getBoolean("enderframe.paint.nocullrange");

    /** {@code enderframe.paint.nodiffpatch}: PaintSceneDriver.patchDiffIndex（使う所: {@code PaintSceneDriver}） */
    public static final boolean PATCH_DIFF_INDEX = !Boolean.getBoolean("enderframe.paint.nodiffpatch");

    /** {@code enderframe.paint.nomemo}: DisplayListBuilder.MEMO_ENABLED・BoxAnimation.NO_MEMO（使う所: {@code BoxAnimation}） */
    public static final boolean NO_PAINT_MEMO = Boolean.getBoolean("enderframe.paint.nomemo");

    /** {@code enderframe.paint.noparentrelative}: EngineContext.parentRelativeDisplayItems（B3 の親相対化）（使う所: {@code EngineContext}） */
    public static final boolean PARENT_RELATIVE = !Boolean.getBoolean("enderframe.paint.noparentrelative");

    /** {@code enderframe.paint.noprune}: DisplayListBuilder.PRUNE_ENABLED（使う所: {@code DisplayListBuilder}） */
    public static final boolean PRUNE = !Boolean.getBoolean("enderframe.paint.noprune");

    /** {@code enderframe.paint.noreplay}: SoftwareRenderBackend.glReplay（使う所: {@code SoftwareRenderBackend}） */
    public static final boolean SOFT_GL_REPLAY = !Boolean.getBoolean("enderframe.paint.noreplay");

    /** {@code enderframe.paint.noscrollnode}: DisplayListBuilder.scrollContentNodes（使う所: {@code DisplayListBuilder}） */
    public static final boolean SCROLL_CONTENT_NODES = !Boolean.getBoolean("enderframe.paint.noscrollnode");

    /** {@code enderframe.paint.noshiftpatch}: PaintSceneDriver.patchShiftIndex（使う所: {@code PaintSceneDriver}） */
    public static final boolean PATCH_SHIFT_INDEX = !Boolean.getBoolean("enderframe.paint.noshiftpatch");

    /** {@code enderframe.paint.nostamps}: UiView.changeStampsEnabled（使う所: {@code UiView}） */
    public static final boolean CHANGE_STAMPS = !Boolean.getBoolean("enderframe.paint.nostamps");

    /** {@code enderframe.paint.notextsetup}: SoftwareRenderBackend.textSetupReuse（P9b）（使う所: {@code SoftwareRenderBackend}） */
    public static final boolean TEXT_SETUP_REUSE = !Boolean.getBoolean("enderframe.paint.notextsetup");

    /** {@code enderframe.paint.notranslate}: DisplayListBuilder.TRANSLATE_ENABLED（使う所: {@code DisplayListBuilder}） */
    public static final boolean PAINT_TRANSLATE = !Boolean.getBoolean("enderframe.paint.notranslate");

    /** {@code enderframe.paint.nowindow}: DisplayListBuilder.WINDOWED_ENABLED（窓付き表示リスト）（使う所: {@code DisplayListBuilder}） */
    public static final boolean WINDOWED = !Boolean.getBoolean("enderframe.paint.nowindow");

    /** {@code enderframe.sheetcache.off}: DocumentLoader.sheetTemplateCache（使う所: {@code DocumentLoader}） */
    public static final boolean SHEET_CACHE = !Boolean.getBoolean("enderframe.sheetcache.off");

    /** {@code enderframe.soft.nocull}: SoftwareRenderBackend.cullOffscreen（使う所: {@code SoftwareRenderBackend}） */
    public static final boolean SOFT_CULL_OFFSCREEN = !Boolean.getBoolean("enderframe.soft.nocull");

    /** {@code enderframe.soft.nodamage}: SoftwareRenderBackend.damageEnabled（使う所: {@code SoftwareRenderBackend}） */
    public static final boolean SOFT_DAMAGE = !Boolean.getBoolean("enderframe.soft.nodamage");

    /** {@code enderframe.soft.nofastsolid}: SoftwareRenderBackend.fastSolid（使う所: {@code SoftwareRenderBackend}） */
    public static final boolean SOFT_FAST_SOLID = !Boolean.getBoolean("enderframe.soft.nofastsolid");

    /** {@code enderframe.soft.notiled}: TileQueue.tiled（タイル並列ラスタ）（使う所: {@code TileQueue}） */
    public static final boolean SOFT_TILED = !Boolean.getBoolean("enderframe.soft.notiled");

    /** {@code enderframe.ui.nochildpatch}: UiView.childPatchEnabled（使う所: {@code UiView}） */
    public static final boolean CHILD_PATCH = !Boolean.getBoolean("enderframe.ui.nochildpatch");

    /** {@code enderframe.ui.nocombinedtext}: UiView.combinedTextPatchEnabled（使う所: {@code UiView}） */
    public static final boolean COMBINED_TEXT_PATCH = !Boolean.getBoolean("enderframe.ui.nocombinedtext");

    /** {@code enderframe.ui.nostaletextpatch}: UiView.staleTextPatchEnabled（使う所: {@code UiView}） */
    public static final boolean STALE_TEXT_PATCH = !Boolean.getBoolean("enderframe.ui.nostaletextpatch");

    // ---- 従来の挙動へ戻す切り替え（結果が変わる） ----

    /** {@code enderframe.css.textfullcascade}: RenderPipeline.TEXT_STYLE_FROM_PARENT を切り、文字の節にもカスケードを全部かける（使う所: {@code RenderPipeline}） */
    public static final boolean TEXT_STYLE_FROM_PARENT = !Boolean.getBoolean("enderframe.css.textfullcascade");

    /** {@code enderframe.parser.dropnewlinews}: HtmlParser の要素の間の改行入りの空白を捨てる（A11 第 4 段より前の挙動）（使う所: {@code HtmlParser}） */
    public static final boolean KEEP_NEWLINE_WHITESPACE = !Boolean.getBoolean("enderframe.parser.dropnewlinews");

    // ---- 数値の調整（スレッド数・キャッシュの大きさ・閾値） ----

    /** {@code enderframe.paint.cullmargin}: DisplayListBuilder.CULL_MARGIN（画面外の間引きの余白 px。既定 640）（使う所: {@code DisplayListBuilder}） */
    public static final float CULL_MARGIN = Float.parseFloat(System.getProperty("enderframe.paint.cullmargin", "640"));

    /** {@code enderframe.paint.windowmin}: DisplayListBuilder.WINDOW_MIN_OWNERS（窓付きにする最小の持ち主数。既定 800）（使う所: {@code DisplayListBuilder}） */
    public static final int WINDOW_MIN_OWNERS = Integer.getInteger("enderframe.paint.windowmin", 800);

    /** {@code enderframe.raster.serial}: Rows.PARALLEL を切り、行の塗りを直列にする（使う所: {@code Rows}） */
    public static final boolean RASTER_PARALLEL = System.getProperty("enderframe.raster.serial") == null;

    /** {@code enderframe.split}: LayoutTaskPool.SPLIT_PER_PARTICIPANT（並列区間の分割数。既定 4）（使う所: {@code LayoutTaskPool}） */
    public static final int SPLIT_PER_PARTICIPANT = Integer.getInteger("enderframe.split", 4);

    /** {@code enderframe.text.cache}: TextShaper の整形キャッシュの容量（既定 4096）（使う所: {@code TextShaper}） */
    public static final int TEXT_CACHE = Integer.getInteger("enderframe.text.cache", 4096);

    /** {@code enderframe.threads}: ワーカー数（A3。EnderEngine.workerThreads と同じ）（使う所: {@code LayoutTaskPool}） */
    public static final Integer THREADS = Integer.getInteger("enderframe.threads");

    // ---- 計測・検証（既定オフ。テストや計測でだけ立てる） ----

    /** {@code enderframe.layout.probe}: LayoutParallelProbe（並列化の採取）（使う所: {@code LayoutParallelProbe}） */
    public static final boolean LAYOUT_PROBE = Boolean.getBoolean("enderframe.layout.probe");

    /** {@code enderframe.layout.verifyfloats}: BlockLayoutEngine.verifyFloatMemo（:core:test が立てる）（使う所: {@code BlockLayoutEngine}） */
    public static final boolean VERIFY_FLOATS = Boolean.getBoolean("enderframe.layout.verifyfloats");

    /** {@code enderframe.paint.verifystamps}: DisplayListBuilder.verifyStamps（:core:test が立てる）（使う所: {@code DisplayListBuilder}） */
    public static final boolean VERIFY_STAMPS = Boolean.getBoolean("enderframe.paint.verifystamps");

    /** {@code enderframe.profile}: StageProfiler の段別計測（使う所: {@code StageProfiler}） */
    public static final boolean PROFILE = Boolean.getBoolean("enderframe.profile");

    /** {@code enderframe.profile.alloc}: StageProfiler の段別の確保量（使う所: {@code StageProfiler}） */
    public static final boolean PROFILE_ALLOC = Boolean.getBoolean("enderframe.profile.alloc");

    /** {@code enderframe.soft.damagedebug}: 損傷範囲の決定の理由を出力（使う所: {@code SoftwareRenderBackend}） */
    public static final boolean SOFT_DAMAGE_DEBUG = Boolean.getBoolean("enderframe.soft.damagedebug");

    // ---- GPU の面の切り替え・調整（GPU が無い環境では効かない） ----

    /** {@code enderframe.gl.fullresblur}: GpuRenderBackend.downsampledBlur（縮小してぼかす）を切る（使う所: {@code GpuRenderBackend}） */
    public static final boolean GL_DOWNSAMPLED_BLUR = !Boolean.getBoolean("enderframe.gl.fullresblur");

    /** {@code enderframe.gl.fullscreenlayers}: GpuRenderBackend.boundedLayers（層を外接矩形に限る）を切る（使う所: {@code GpuRenderBackend}） */
    public static final boolean GL_BOUNDED_LAYERS = !Boolean.getBoolean("enderframe.gl.fullscreenlayers");

    /** {@code enderframe.gl.noreplay}: RectStream.replayEnabled（項目単位の保持記録の再生）（使う所: {@code RectStream}） */
    public static final boolean GL_REPLAY = !Boolean.getBoolean("enderframe.gl.noreplay");

    /** {@code enderframe.gl.rectcapacity}: RectStream の初期容量（図形数。既定 16384）（使う所: {@code GpuRenderBackend}） */
    public static final int GL_RECT_CAPACITY = Integer.getInteger("enderframe.gl.rectcapacity", 16384);

    /** {@code enderframe.gl.recttrees}: GpuRenderBackend.rectTrees（"false" で切る）（使う所: {@code GpuRenderBackend}） */
    public static final boolean GL_RECT_TREES = !"false".equals(System.getProperty("enderframe.gl.recttrees"));

    /** {@code enderframe.gl.retainscroll}: GpuRenderBackend.retainedScroll（"false" で切る）（使う所: {@code GpuRenderBackend}） */
    public static final boolean GL_RETAINED_SCROLL = !"false".equals(System.getProperty("enderframe.gl.retainscroll"));

    /** {@code enderframe.gpu.nodamage}: GpuRenderBackend.damageEnabled（損傷範囲だけの描画。B10）（使う所: {@code GpuRenderBackend}） */
    public static final boolean GPU_DAMAGE = !Boolean.getBoolean("enderframe.gpu.nodamage");
}
