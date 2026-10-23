package com.nishiyu.enderframelib.network;

import com.nishiyu.enderframelib.EnderFrameLib;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.ShortTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Headless Server State の Java オブジェクト値と NBT タグの相互変換ヘルパー。
 *
 * <p>パケット上の表現（キー辞書 {@link KeyTable} による番号化、受信側の辞書 {@link ReceivedKeyTables}、
 * 番号付きの値の読み書き）もここに置く。</p>
 */
@ApiStatus.Internal
public final class StateNbtConverter {

    private StateNbtConverter() {}

    /**
     * Java オブジェクト値を判別し、適切な NBT 型として {@link CompoundTag} に書き込みます。
     *
     * @param tag   書き込み対象の CompoundTag
     * @param key   キー名
     * @param value 設定する値（null の場合はキーを削除）
     */
    public static void putValue(@NotNull CompoundTag tag, @NotNull String key, @Nullable Object value) {
        if (value == null) {
            tag.remove(key);
            return;
        }

        if (value instanceof Tag nbtTag) {
            tag.put(key, nbtTag);
        } else if (value instanceof Boolean b) {
            tag.putBoolean(key, b);
        } else if (value instanceof Integer i) {
            tag.putInt(key, i);
        } else if (value instanceof Long l) {
            tag.putLong(key, l);
        } else if (value instanceof Double d) {
            tag.putDouble(key, d);
        } else if (value instanceof Float f) {
            tag.putFloat(key, f);
        } else if (value instanceof String s) {
            tag.putString(key, s);
        } else if (value instanceof Byte b) {
            tag.putByte(key, b);
        } else if (value instanceof Short s) {
            tag.putShort(key, s);
        } else if (value instanceof byte[] bytes) {
            tag.putByteArray(key, bytes);
        } else if (value instanceof int[] ints) {
            tag.putIntArray(key, ints);
        } else if (value instanceof long[] longs) {
            tag.putLongArray(key, longs);
        } else if (value instanceof Map<?, ?> map) {
            tag.put(key, toCompoundTag(map));
        } else if (value instanceof List<?> list) {
            tag.put(key, toListTag(list));
        } else {
            tag.putString(key, String.valueOf(value));
        }
    }

    /**
     * 任意の Java オブジェクトを NBT {@link Tag} に変換します。
     */
    @Nullable
    public static Tag toTag(@Nullable Object value) {
        if (value == null) return null;
        if (value instanceof Tag tag) return tag;
        if (value instanceof Boolean b) return ByteTag.valueOf(b);
        if (value instanceof Integer i) return IntTag.valueOf(i);
        if (value instanceof Long l) return LongTag.valueOf(l);
        if (value instanceof Double d) return DoubleTag.valueOf(d);
        if (value instanceof Float f) return FloatTag.valueOf(f);
        if (value instanceof String s) return StringTag.valueOf(s);
        if (value instanceof Byte b) return ByteTag.valueOf(b);
        if (value instanceof Short s) return ShortTag.valueOf(s);
        if (value instanceof byte[] bytes) return new ByteArrayTag(bytes);
        if (value instanceof int[] ints) return new IntArrayTag(ints);
        if (value instanceof long[] longs) return new LongArrayTag(longs);
        if (value instanceof Map<?, ?> map) return toCompoundTag(map);
        if (value instanceof List<?> list) return toListTag(list);
        return StringTag.valueOf(String.valueOf(value));
    }

    /**
     * Map を {@link CompoundTag} に変換します。
     */
    @NotNull
    public static CompoundTag toCompoundTag(@NotNull Map<?, ?> map) {
        CompoundTag tag = new CompoundTag();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null) {
                putValue(tag, String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return tag;
    }

    /**
     * List を {@link ListTag} に変換します。
     */
    @NotNull
    public static ListTag toListTag(@NotNull List<?> list) {
        ListTag tag = new ListTag();
        for (Object item : list) {
            Tag t = toTag(item);
            if (t != null) {
                tag.add(t);
            }
        }
        return tag;
    }

    /**
     * NBT {@link Tag} を Java オブジェクト（プリミティブ wrapper、Map、List）に復元します。
     */
    @Nullable
    public static Object fromTag(@Nullable Tag tag) {
        if (tag == null) return null;
        if (tag instanceof ByteTag byteTag) {
            // boolean または byte
            return byteTag.getAsByte();
        } else if (tag instanceof ShortTag shortTag) {
            return shortTag.getAsShort();
        } else if (tag instanceof IntTag intTag) {
            return intTag.getAsInt();
        } else if (tag instanceof LongTag longTag) {
            return longTag.getAsLong();
        } else if (tag instanceof FloatTag floatTag) {
            return floatTag.getAsFloat();
        } else if (tag instanceof DoubleTag doubleTag) {
            return doubleTag.getAsDouble();
        } else if (tag instanceof StringTag stringTag) {
            return stringTag.getAsString();
        } else if (tag instanceof ByteArrayTag byteArrayTag) {
            return byteArrayTag.getAsByteArray();
        } else if (tag instanceof IntArrayTag intArrayTag) {
            return intArrayTag.getAsIntArray();
        } else if (tag instanceof LongArrayTag longArrayTag) {
            return longArrayTag.getAsLongArray();
        } else if (tag instanceof CompoundTag compoundTag) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (String key : compoundTag.getAllKeys()) {
                map.put(key, fromTag(compoundTag.get(key)));
            }
            return map;
        } else if (tag instanceof ListTag listTag) {
            List<Object> list = new ArrayList<>(listTag.size());
            for (int i = 0; i < listTag.size(); i++) {
                list.add(fromTag(listTag.get(i)));
            }
            return list;
        }
        return tag.getAsString();
    }

    // ------------------------------------------------------------------
    // パケット上の番号付きの値（OpenUiS2CPacket / StateDeltaS2CPacket で共通）
    //
    // 値は CompoundTag で包まず、VarInt 番号 + NBT の型 1 byte + 中身 を並べる。
    // Compound で包むとキーごとに名前の文字列と型が付き、末尾にも終端が付くため、
    // 単一更新では値より包みの方が大きくなっていた。
    // ------------------------------------------------------------------

    /** 受信側のキー辞書をすべて捨てる（切断時。{@link ReceivedKeyTables#clear()}）。 */
    public static void clearReceivedKeyTables() {
        ReceivedKeyTables.clear();
    }

    /** 削除されたキーの一覧を入れる予約キー（クライアントの適用側と共通）。 */
    static final String REMOVED_KEY = "__removed";

    /** 1 パケットで受け付ける件数の上限。壊れた長さで巨大な配列を作らないため。 */
    static final int MAX_COUNT = 1 << 16;

    /** キー名の最大長（文字数）。 */
    static final int MAX_KEY_LENGTH = 32767;

    /** 1 パケットぶんの NBT の上限（{@code FriendlyByteBuf#readNbt} と同じ 2MiB）。 */
    static final long NBT_QUOTA = 2097152L;

    /** {@code values} の全キー（{@link #REMOVED_KEY} を除く）を番号付きで書く。 */
    static void writeEntries(FriendlyByteBuf buf, KeyTable keys, CompoundTag values) {
        List<String> entryKeys = new ArrayList<>(values.size());
        for (String key : values.getAllKeys()) {
            if (!REMOVED_KEY.equals(key)) entryKeys.add(key);
        }
        buf.writeVarInt(entryKeys.size());
        for (String key : entryKeys) {
            buf.writeVarInt(requireIndex(keys, key));
            writeTag(buf, values.get(key));
        }
    }

    /**
     * 番号付きの値を読み、辞書で名前に戻して {@code out} に入れる。
     * 辞書に無い番号の値は読み飛ばす（中身は読まないと次の値の位置がずれる）。
     */
    static void readEntries(FriendlyByteBuf buf, KeyTable keys, CompoundTag out) {
        int count = readCount(buf);
        NbtAccounter accounter = NbtAccounter.create(NBT_QUOTA);
        for (int i = 0; i < count; i++) {
            int index = buf.readVarInt();
            Tag tag = readTag(buf, accounter);
            String name = keys.nameAt(index);
            if (name != null) out.put(name, tag);
        }
    }

    static int requireIndex(KeyTable keys, String key) {
        int index = keys.indexOf(key);
        if (index < 0) {
            throw new EncoderException("Headless State のキーが辞書にありません: " + key);
        }
        return index;
    }

    static int readCount(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_COUNT) {
            throw new DecoderException("Headless State パケットの件数が不正です: " + count);
        }
        return count;
    }

    static void writeNames(FriendlyByteBuf buf, List<String> names) {
        buf.writeVarInt(names.size());
        for (String name : names) {
            buf.writeUtf(name, MAX_KEY_LENGTH);
        }
    }

    static List<String> readNames(FriendlyByteBuf buf) {
        int count = readCount(buf);
        List<String> names = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            names.add(buf.readUtf(MAX_KEY_LENGTH));
        }
        return names;
    }

    private static void writeTag(FriendlyByteBuf buf, Tag tag) {
        try {
            NbtIo.writeAnyTag(tag, new ByteBufOutputStream(buf));
        } catch (IOException e) {
            throw new EncoderException("Headless State の値を書けませんでした", e);
        }
    }

    private static Tag readTag(FriendlyByteBuf buf, NbtAccounter accounter) {
        try {
            return NbtIo.readAnyTag(new ByteBufInputStream(buf), accounter);
        } catch (IOException e) {
            throw new DecoderException("Headless State の値を読めませんでした", e);
        }
    }

    /**
     * Headless State のキー辞書（キー名 ⇔ 番号）。
     *
     * <p>更新パケットはキー名を毎回文字列で送らず、この辞書の番号（VarInt）で送る。辞書は {@code UIState} ごとに 1 つで、
     * 追記だけを行い番号を使い回さない（消したキーも番号は残す）。そのため一度送った番号の意味は変わらない。</p>
     *
     * <p>不変オブジェクトで、追記は新しい辞書を返す。パケットは作った時点の辞書をそのまま持ち、
     * netty のスレッドで後からエンコードされても、その間に増えたキーの影響を受けない。</p>
     */
    public static final class KeyTable {

        public static final KeyTable EMPTY = new KeyTable(List.of(), Map.of());

        private final List<String> names;
        private final Map<String, Integer> indices;

        private KeyTable(List<String> names, Map<String, Integer> indices) {
            this.names = names;
            this.indices = indices;
        }

        /** 名前を番号順に並べた辞書を作る（受信側の復元用）。 */
        @NotNull
        public static KeyTable of(@NotNull List<String> names) {
            KeyTable table = EMPTY;
            for (String name : names) {
                table = table.with(name);
            }
            return table;
        }

        /** キーの番号。辞書に無ければ -1。 */
        public int indexOf(@NotNull String key) {
            Integer index = indices.get(key);
            return index != null ? index : -1;
        }

        /** 番号のキー名。範囲外なら null。 */
        public String nameAt(int index) {
            return index >= 0 && index < names.size() ? names.get(index) : null;
        }

        public int size() {
            return names.size();
        }

        /** 番号順のキー名（変更不可）。 */
        @NotNull
        public List<String> names() {
            return names;
        }

        /**
         * キーを辞書に加えた辞書を返す。既にあれば自身を返す。
         * 追記はキーが初めて使われたときだけなので、毎回の複製は問題にならない。
         */
        @NotNull
        public KeyTable with(@NotNull String key) {
            Objects.requireNonNull(key, "key cannot be null");
            if (indices.containsKey(key)) return this;
            List<String> newNames = new ArrayList<>(names.size() + 1);
            newNames.addAll(names);
            newNames.add(key);
            Map<String, Integer> newIndices = new HashMap<>(indices);
            newIndices.put(key, names.size());
            return new KeyTable(Collections.unmodifiableList(newNames), Collections.unmodifiableMap(newIndices));
        }
    }

    /**
     * 受信側（クライアント）が持つ、Headless State の番号 → インスタンス ID とキー辞書の対応。
     *
     * <p>{@link OpenUiS2CPacket} のデコードで登録し、{@link StateDeltaS2CPacket} のデコードで番号を名前に戻す。
     * デコードは 1 接続につき netty の 1 スレッドで送信順に走るので、開く → 差分の順序はここでも保たれる。
     * 統合サーバー（メモリ接続）ではパケットがエンコードされずに渡るため、ここは使われない。</p>
     *
     * <p>client パッケージに置かないのは、パケットのデコード（network パッケージ）から引くため。
     * 中身は文字列と番号だけで、クライアント専用のクラスは参照しない。</p>
     */
    public static final class ReceivedKeyTables {

        record Entry(String instanceId, KeyTable keys) {}

        private static final Map<Integer, Entry> TABLES = new ConcurrentHashMap<>();

        private ReceivedKeyTables() {}

        /** 画面を開いたときの登録。同じインスタンス ID の古い番号（作り直された状態）は捨てる。 */
        static void open(int wireId, String instanceId, KeyTable keys) {
            TABLES.values().removeIf(entry -> entry.instanceId().equals(instanceId));
            TABLES.put(wireId, new Entry(instanceId, keys));
        }

        @Nullable
        static Entry get(int wireId) {
            return TABLES.get(wireId);
        }

        /**
         * 差分で届いたキー定義（番号 {@code from} から連続する名前）を辞書に足す。
         * 開いた時点で既に知っている番号は飛ばす。
         *
         * @return 足した後の項目。番号を知らなければ null
         */
        @Nullable
        static Entry define(int wireId, int from, List<String> names) {
            Entry entry = TABLES.get(wireId);
            if (entry == null) return null;
            KeyTable keys = entry.keys();
            for (int i = 0; i < names.size(); i++) {
                int index = from + i;
                if (index < keys.size()) continue;
                if (index > keys.size()) {
                    // 送信側は定義を必ず連続で送るので、ここに来るのは取りこぼしたとき
                    EnderFrameLib.LOGGER.warn("Headless State {} のキー定義が飛んでいます（{} 番が来たが辞書は {} 件）",
                            entry.instanceId(), index, keys.size());
                    break;
                }
                keys = keys.with(names.get(i));
            }
            Entry updated = new Entry(entry.instanceId(), keys);
            TABLES.put(wireId, updated);
            return updated;
        }

        static void drop(int wireId) {
            TABLES.remove(wireId);
        }

        /** 切断時にすべて捨てる。 */
        public static void clear() {
            TABLES.clear();
        }

        /** 登録数（テスト用）。 */
        public static int size() {
            return TABLES.size();
        }
    }
}
