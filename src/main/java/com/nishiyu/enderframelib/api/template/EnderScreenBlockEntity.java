package com.nishiyu.enderframelib.api.template;

import com.nishiyu.enderframelib.api.EnderFrameAPI;
import com.nishiyu.enderframelib.api.Ui;
import com.nishiyu.enderframelib.api.WebScreenBlockEntity;
import com.nishiyu.enderframelib.api.state.UIState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * インタラクティブなブロック画面のひな形（ブロックエンティティ側）。
 *
 * <p>同じ向きで平面に隣り合った {@link EnderScreenBlock} が、欠けのない長方形になると 1 枚の画面にまとまる
 * （左下が master）。長方形にならないとき（L 字など）は、各ブロックが 1×1 の画面になる。
 * 構成するブロックが 1 つでも壊れると、その画面のセッションは捨てられ、残りのブロックは初期化される（何も映さない）。
 * ブロックを置いただけでは何も映らない。{@link #draw(Level, BlockPos...)} の命令を出すと、つながったブロックが画面になる。
 * 向き・サイズ・master は NBT とブロック更新パケットで同期される。</p>
 *
 * <p>使う側が書くのは、{@code BlockEntityType} の登録と、必要なら {@link #getSessionId()} /
 * {@link #onScreenFormed(int, int)} の上書きだけ。表示（{@code BlockSurfaceRenderer}）と入力
 * （視線のヒット判定・入力欄）は、{@link WebScreenBlockEntity} を実装していれば自動で働く。</p>
 */
@ApiStatus.AvailableSince("1.1")
public class EnderScreenBlockEntity extends BlockEntity implements WebScreenBlockEntity {

    /** 1 枚の画面の最大の幅・高さ（ブロック数）。これを超える長方形は 1×1 に分かれる。 */
    public static final int MAX_SIZE = 16;

    private Direction facing = Direction.NORTH;
    private BlockPos masterPos;
    private int width = 1;
    private int height = 1;
    /** 描く命令（{@link #draw}）を受けて画面になっているか。そうでないブロックは何も映さず、入力も受けない。 */
    private boolean drawn;
    /** 読み込んだ（または最後に保存した）テンプレートの状態。状態が作られるときに戻す。 */
    private CompoundTag savedState;
    private UIState liveState;

    public EnderScreenBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    // ------------------------------------------------------------------
    // WebScreenBlockEntity
    // ------------------------------------------------------------------

    @Override
    public boolean isMaster() {
        return drawn && (masterPos == null || masterPos.equals(worldPosition));
    }

    @Override
    public boolean isActive() {
        return drawn;
    }

    @Override
    public BlockPos getScreenMasterPos() {
        return masterPos != null ? masterPos : worldPosition;
    }

    @Override
    public Direction getFacing() {
        return facing;
    }

    @Override
    public int getScreenWidth() {
        return width;
    }

    @Override
    public int getScreenHeight() {
        return height;
    }

    /**
     * 画面のセッション ID。既定は master の位置から作る（画面ごとに別のセッション）。
     * 固定のセッションを使いたい MOD は上書きする。{@link #getTemplateId()} を返すときは、テンプレートの状態の
     * インスタンス ID（master の位置から決まる）になる。
     */
    @Override
    public String getSessionId() {
        BlockPos master = getScreenMasterPos();
        if (getTemplateId() != null) return Ui.Template.blockInstanceId(master);
        String dimension = level != null ? level.dimension().location().toString().replaceAll("[^a-z0-9_-]", "_") : "unknown";
        return "screen_" + dimension + "_" + master.getX() + "_" + master.getY() + "_" + master.getZ();
    }

    // ------------------------------------------------------------------
    // テンプレートで動かす画面
    // ------------------------------------------------------------------

    /**
     * 画面を動かすクライアント側のテンプレート（例: {@code "mymod:panel"}）。上書きすると、サーバーは HTML のセッションを持たず、
     * {@link #screenState()} の Key-Value だけでこの画面を動かす。{@code null}（既定）なら、{@link #getSessionId()} の
     * セッションを映す従来の画面になる。
     */
    @Override
    public @Nullable String getTemplateId() {
        return null;
    }

    /**
     * テンプレートの画面の状態（master のものを共有する）。値を {@code set} すると、その tick の終わりに画面を見ている
     * プレイヤーへ差分だけが届き、クリックなどは {@code onAction} に届く。サーバー側でだけ呼ぶこと。
     *
     * @throws IllegalStateException {@link #getTemplateId()} が {@code null}、またはレベルに置かれていないとき
     */
    public UIState screenState() {
        String templateId = getTemplateId();
        if (templateId == null || level == null || level.isClientSide) {
            throw new IllegalStateException("screenState() needs a template id and a server level");
        }
        BlockEntity master = level.getBlockEntity(getScreenMasterPos());
        return EnderFrameAPI.get().stateOf(master != null ? master : this, templateId);
    }

    // ------------------------------------------------------------------
    // フック（サーバー側）
    // ------------------------------------------------------------------

    /**
     * テンプレートの状態が作られるたびに（置いた後・チャンクを読み込んだ後の最初の利用で）、master だけに呼ばれる。
     * 呼ばれる時点で、NBT に保存してあった値は戻してある。足りない値の初期化と {@code onAction} の登録をここで行う。
     * 状態の全ての値はこのブロックエンティティの NBT に保存される。
     */
    protected void onScreenState(UIState state) {
    }

    @Override
    public final void onStateCreated(UIState state) {
        liveState = state;
        if (savedState != null) state.load(savedState);
        onScreenState(state);
    }

    /**
     * 画面の形が決まったとき（置いた・壊した・隣が変わった後）に、master だけに呼ばれる。
     * セッションを読み込む、または大きさを合わせ直す場所。幅・高さはブロック数。
     */
    protected void onScreenFormed(int width, int height) {
    }

    // ------------------------------------------------------------------
    // 向きとマルチブロック
    // ------------------------------------------------------------------

    /** 向きだけを変える（ネットワークは組み直さない）。続けて {@link #draw(Level, BlockPos...)} を呼ぶ。 */
    public void setFacing(Direction facing) {
        if (facing == null || facing.getAxis().isVertical()) facing = Direction.NORTH;
        this.facing = facing;
        markChangedAndSync();
    }

    /** 画面の平面の中で、このブロックに隣り合う 4 つの位置。 */
    public List<BlockPos> neighborsInPlane() {
        Direction right = facing.getCounterClockWise();
        return List.of(worldPosition.relative(right), worldPosition.relative(right.getOpposite()),
                worldPosition.above(), worldPosition.below());
    }

    /** この画面（master から右へ幅、上へ高さ）の全ブロックの位置。 */
    List<BlockPos> screenBlocks() {
        List<BlockPos> out = new ArrayList<>();
        Direction right = facing.getCounterClockWise();
        BlockPos origin = getScreenMasterPos();
        for (int u = 0; u < width; u++) {
            for (int v = 0; v < height; v++) out.add(origin.relative(right, u).above(v));
        }
        return out;
    }

    /**
     * 画面を構成するブロックの 1 つが壊れるとき（壊れる前に）呼ぶ。この画面のセッションを捨て、残りのブロックを初期化する。
     * 初期化したブロックは何も映さず、{@link #draw(Level, BlockPos...)} の命令が来るまで画面にならない。
     */
    void onScreenBlockBreaking() {
        if (level == null || level.isClientSide) return;
        if (level.getBlockEntity(getScreenMasterPos()) instanceof EnderScreenBlockEntity master) master.discardSession();
        else discardSession();
        for (BlockPos pos : screenBlocks()) {
            if (!pos.equals(worldPosition) && level.getBlockEntity(pos) instanceof EnderScreenBlockEntity member) member.reset();
        }
    }

    /**
     * {@code starts} の位置（と、そこから平面でつながるブロック）の画面を組み直す。サーバー側で呼ぶ。
     * 同じ画面に含まれる開始位置は 1 回しか処理しない。
     */
    public static void draw(Level level, BlockPos... starts) {
        if (level == null || level.isClientSide) return;
        Set<BlockPos> done = new HashSet<>();
        for (BlockPos start : starts) {
            if (done.contains(start)) continue;
            if (!(screenAt(level, start, null) instanceof EnderScreenBlockEntity first)) continue;
            List<EnderScreenBlockEntity> members = collect(level, first);
            for (EnderScreenBlockEntity m : members) done.add(m.worldPosition);
            form(members);
        }
    }

    private static EnderScreenBlockEntity screenAt(Level level, BlockPos pos, EnderScreenBlockEntity like) {
        if (!level.hasChunkAt(pos)) return null;
        if (!(level.getBlockState(pos).getBlock() instanceof EnderScreenBlock)) return null;
        if (!(level.getBlockEntity(pos) instanceof EnderScreenBlockEntity be)) return null;
        if (like != null && (be.getType() != like.getType() || be.facing != like.facing)) return null;
        return be;
    }

    /** {@code start} から、同じ種類・同じ向きで平面につながるブロックを集める（上限あり）。 */
    private static List<EnderScreenBlockEntity> collect(Level level, EnderScreenBlockEntity start) {
        List<EnderScreenBlockEntity> members = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        Deque<EnderScreenBlockEntity> queue = new ArrayDeque<>();
        seen.add(start.worldPosition);
        queue.add(start);
        while (!queue.isEmpty() && members.size() <= MAX_SIZE * MAX_SIZE) {
            EnderScreenBlockEntity current = queue.poll();
            members.add(current);
            for (BlockPos next : current.neighborsInPlane()) {
                if (!seen.add(next)) continue;
                EnderScreenBlockEntity be = screenAt(level, next, start);
                if (be != null) queue.add(be);
            }
        }
        return members;
    }

    /** 欠けのない長方形なら 1 枚の画面に、そうでなければ各ブロックを 1×1 にする。 */
    private static void form(List<EnderScreenBlockEntity> members) {
        EnderScreenBlockEntity first = members.get(0);
        Direction right = first.facing.getCounterClockWise();
        int minU = Integer.MAX_VALUE, maxU = Integer.MIN_VALUE, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (EnderScreenBlockEntity m : members) {
            int u = m.worldPosition.getX() * right.getStepX() + m.worldPosition.getZ() * right.getStepZ();
            minU = Math.min(minU, u);
            maxU = Math.max(maxU, u);
            minY = Math.min(minY, m.worldPosition.getY());
            maxY = Math.max(maxY, m.worldPosition.getY());
        }
        int w = maxU - minU + 1;
        int h = maxY - minY + 1;
        boolean rectangle = w <= MAX_SIZE && h <= MAX_SIZE && members.size() == w * h;

        if (!rectangle) {
            for (EnderScreenBlockEntity m : members) m.apply(m.worldPosition, 1, 1);
            return;
        }
        BlockPos master = null;
        for (EnderScreenBlockEntity m : members) {
            int u = m.worldPosition.getX() * right.getStepX() + m.worldPosition.getZ() * right.getStepZ();
            if (u == minU && m.worldPosition.getY() == minY) master = m.worldPosition;
        }
        for (EnderScreenBlockEntity m : members) m.apply(master, w, h);
    }

    /** この画面の閲覧を、見ていたプレイヤーから外す（master でなくなった・閉じた）。 */
    private void dropSurface() {
        if (getTemplateId() != null && level != null && !level.isClientSide) EnderFrameAPI.get().refreshSurface(this);
    }

    /** 初期化する。何も映さず、入力も受けず、画面のセッションも持たない。{@link #apply} で再び画面になる。 */
    private void reset() {
        drawn = false;
        masterPos = null;
        width = 1;
        height = 1;
        savedState = null;
        liveState = null;
        markChangedAndSync();
    }

    /** 画面のセッションを捨てる（状態の値も、見ていたプレイヤーのセッションも）。再び描くときは最初から作り直す。 */
    private void discardSession() {
        savedState = null;
        liveState = null;
        if (level == null || level.isClientSide) return;
        if (getTemplateId() != null) EnderFrameAPI.get().discardSurface(this);
        else EnderFrameAPI.get().closeSession(getSessionId());
    }

    private void apply(BlockPos master, int w, int h) {
        boolean wasMaster = isMaster();
        drawn = true;
        masterPos = master;
        width = w;
        height = h;
        markChangedAndSync();
        if (wasMaster && !isMaster()) dropSurface();
        if (isMaster()) {
            onScreenFormed(w, h);
            // 大きさが変わったので、見ているプレイヤーのセッションを作り直させる
            if (getTemplateId() != null && level != null && !level.isClientSide) EnderFrameAPI.get().refreshSurface(this);
        }
    }

    private void markChangedAndSync() {
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_ALL);
        }
    }

    // ------------------------------------------------------------------
    // 保存・同期
    // ------------------------------------------------------------------

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putString("Facing", facing.getName());
        tag.putLong("Master", getScreenMasterPos().asLong());
        tag.putInt("W", width);
        tag.putInt("H", height);
        tag.putBoolean("Drawn", drawn);
        if (isMaster() && getTemplateId() != null) {
            // 閉じた状態は空になるので、中身のある最後の保存を残す
            CompoundTag live = liveState != null ? liveState.toCompoundTag() : null;
            if (live != null && !live.isEmpty()) savedState = live;
            if (savedState != null) tag.put("State", savedState);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        Direction d = Direction.byName(tag.getString("Facing"));
        facing = d != null && !d.getAxis().isVertical() ? d : Direction.NORTH;
        masterPos = tag.contains("Master") ? BlockPos.of(tag.getLong("Master")) : null;
        width = Math.max(1, tag.getInt("W"));
        height = Math.max(1, tag.getInt("H"));
        drawn = tag.getBoolean("Drawn");
        savedState = tag.contains("State", 10) ? tag.getCompound("State") : null;
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = saveWithoutMetadata(registries);
        tag.remove("State"); // 状態はパケットで届く。ブロック更新には載せない
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
