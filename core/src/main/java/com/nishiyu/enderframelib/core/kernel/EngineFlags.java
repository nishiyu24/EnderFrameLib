package com.nishiyu.enderframelib.core.kernel;

/**
 * エンジンの設定の読み口（ENGINE_REARCHITECTURE_PLAN S1 ①）。{@code enderframe.*} のシステムプロパティを読むのはこのクラスだけで、
 * 各値はクラスの初期化のときに 1 回だけ読む（JVM の起動引数 {@code -D} で決める。実行中に {@code System.setProperty} しても変わらない）。
 *
 * <p>近道・切り分けの切り替え（{@link Shortcut}）は、ここの値を初期値に持つ。
 * 実行中に変えるのはそちら（{@link Shortcut#set} / {@link Shortcut#with}）。キーの種類と説明の一覧は {@code core/src/test/resources/system-properties.txt}、
 * 読み口がここ 1 つであることは {@code SystemPropertyRatchetTest} が見張る。新しい設定はここに足す。</p>
 */
public final class EngineFlags {
    private EngineFlags() { }

    // ---- 近道（既定オン。キーを立てると切る）。:core:shortcutMatrix が 1 つずつ・2 つずつ切って、結果が変わらないことを確かめる ----

    /** {@code enderframe.layout.noparallel}: LayoutTaskPool.parallelEnabled（B2 の並列化）（使う所: {@code LayoutTaskPool}） */
    public static final boolean LAYOUT_PARALLEL = !Boolean.getBoolean("enderframe.layout.noparallel");

    /** {@code enderframe.paint.notextsetup}: SoftwareRenderBackend.textSetupReuse（P9b）（使う所: {@code SoftwareRenderBackend}） */
    public static final boolean TEXT_SETUP_REUSE = !Boolean.getBoolean("enderframe.paint.notextsetup");

    /** {@code enderframe.sheetcache.off}: DocumentLoader.sheetTemplateCache（使う所: {@code DocumentLoader}） */
    public static final boolean SHEET_CACHE = !Boolean.getBoolean("enderframe.sheetcache.off");

    /** {@code enderframe.soft.nocull}: SoftwareRenderBackend.cullOffscreen（使う所: {@code SoftwareRenderBackend}） */
    public static final boolean SOFT_CULL_OFFSCREEN = !Boolean.getBoolean("enderframe.soft.nocull");

    /** {@code enderframe.soft.nofastsolid}: SoftwareRenderBackend.fastSolid（使う所: {@code SoftwareRenderBackend}） */
    public static final boolean SOFT_FAST_SOLID = !Boolean.getBoolean("enderframe.soft.nofastsolid");

    /** {@code enderframe.soft.notiled}: TileQueue.tiled（タイル並列ラスタ）（使う所: {@code TileQueue}） */
    public static final boolean SOFT_TILED = !Boolean.getBoolean("enderframe.soft.notiled");

    // ---- 従来の挙動へ戻す切り替え（結果が変わる） ----

    /** {@code enderframe.parser.dropnewlinews}: HtmlParser の要素の間の改行入りの空白を捨てる（A11 第 4 段より前の挙動）（使う所: {@code HtmlParser}） */
    public static final boolean KEEP_NEWLINE_WHITESPACE = !Boolean.getBoolean("enderframe.parser.dropnewlinews");

    // ---- 数値の調整（スレッド数・キャッシュの大きさ・閾値） ----

    /** {@code enderframe.raster.serial}: Rows.PARALLEL を切り、行の塗りを直列にする（使う所: {@code Rows}） */
    public static final boolean RASTER_PARALLEL = System.getProperty("enderframe.raster.serial") == null;

    /** {@code enderframe.split}: LayoutTaskPool.SPLIT_PER_PARTICIPANT（並列区間の分割数。既定 4）（使う所: {@code LayoutTaskPool}） */
    public static final int SPLIT_PER_PARTICIPANT = Integer.getInteger("enderframe.split", 4);

    /** {@code enderframe.threads}: ワーカー数（A3。EnderEngine.workerThreads と同じ）（使う所: {@code LayoutTaskPool}） */
    public static final Integer THREADS = Integer.getInteger("enderframe.threads");

    // ---- 計測・検証（既定オフ。テストや計測でだけ立てる） ----

    /** {@code enderframe.profile}: StageProfiler の段別計測（使う所: {@code StageProfiler}） */
    public static final boolean PROFILE = Boolean.getBoolean("enderframe.profile");

    /** {@code enderframe.profile.alloc}: StageProfiler の段別の確保量（使う所: {@code StageProfiler}） */
    public static final boolean PROFILE_ALLOC = Boolean.getBoolean("enderframe.profile.alloc");

    // ---- GPU の面の切り替え・調整（GPU が無い環境では効かない） ----

    /** {@code enderframe.gl.fullresblur}: GpuRenderBackend.downsampledBlur（縮小してぼかす）を切る（使う所: {@code GpuRenderBackend}） */
    public static final boolean GL_DOWNSAMPLED_BLUR = !Boolean.getBoolean("enderframe.gl.fullresblur");

    /** {@code enderframe.gl.fullscreenlayers}: GpuRenderBackend.boundedLayers（層を外接矩形に限る）を切る（使う所: {@code GpuRenderBackend}） */
    public static final boolean GL_BOUNDED_LAYERS = !Boolean.getBoolean("enderframe.gl.fullscreenlayers");

    /** {@code enderframe.gl.rectcapacity}: RectStream の初期容量（図形数。既定 16384）（使う所: {@code GpuRenderBackend}） */
    public static final int GL_RECT_CAPACITY = Integer.getInteger("enderframe.gl.rectcapacity", 16384);

    /** {@code enderframe.gl.recttrees}: GpuRenderBackend.rectTrees（"false" で切る）（使う所: {@code GpuRenderBackend}） */
    public static final boolean GL_RECT_TREES = !"false".equals(System.getProperty("enderframe.gl.recttrees"));

    /** {@code enderframe.gl.retainscroll}: GpuRenderBackend.retainedScroll（"false" で切る）（使う所: {@code GpuRenderBackend}） */
    public static final boolean GL_RETAINED_SCROLL = !"false".equals(System.getProperty("enderframe.gl.retainscroll"));

}
