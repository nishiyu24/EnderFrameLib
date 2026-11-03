package com.nishiyu.enderframelib.core.raster;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * 依存のない PNG エンコーダ（8 ビット RGBA、フィルタなし）。AWT / ImageIO を使わないので、描画バックエンドの
 * 画素の書き出しを、描画 API やデスクトップ用のライブラリから切り離せる。
 */
public final class PngEncoder {
    private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    private PngEncoder() {}

    /** {@code pixmap}（事前乗算）を非乗算の RGBA の PNG にする。 */
    public static byte[] encode(Pixmap pixmap) throws IOException {
        return encode(pixmap.width(), pixmap.height(), pixmap.toStraightArgb());
    }

    /** @param argb 非乗算の ARGB（行優先、左上原点）。 */
    public static byte[] encode(int width, int height, int[] argb) throws IOException {
        if (width <= 0 || height <= 0 || argb.length < width * height) throw new IllegalArgumentException("size " + width + "x" + height);
        byte[] raw = new byte[height * (1 + width * 4)];
        int o = 0;
        for (int y = 0; y < height; y++) {
            raw[o++] = 0; // フィルタ: なし
            for (int x = 0; x < width; x++) {
                int p = argb[y * width + x];
                raw[o++] = (byte) (p >> 16);
                raw[o++] = (byte) (p >> 8);
                raw[o++] = (byte) p;
                raw[o++] = (byte) (p >>> 24);
            }
        }
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
        deflater.setInput(raw);
        deflater.finish();
        ByteArrayOutputStream z = new ByteArrayOutputStream(raw.length / 2 + 64);
        byte[] buf = new byte[16384];
        while (!deflater.finished()) z.write(buf, 0, deflater.deflate(buf));
        deflater.end();

        ByteArrayOutputStream out = new ByteArrayOutputStream(z.size() + 128);
        out.write(SIGNATURE);
        byte[] ihdr = new byte[13];
        putInt(ihdr, 0, width);
        putInt(ihdr, 4, height);
        ihdr[8] = 8;  // ビット深度
        ihdr[9] = 6;  // RGBA
        chunk(out, "IHDR", ihdr);
        chunk(out, "IDAT", z.toByteArray());
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    /** PNG として書き出す（親のフォルダは作る）。 */
    public static void write(Path file, Pixmap pixmap) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.write(file, encode(pixmap));
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) throws IOException {
        byte[] len = new byte[4];
        putInt(len, 0, data.length);
        out.write(len);
        byte[] t = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        out.write(t);
        out.write(data);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data);
        byte[] c = new byte[4];
        putInt(c, 0, (int) crc.getValue());
        out.write(c);
    }

    private static void putInt(byte[] b, int at, int v) {
        b[at] = (byte) (v >>> 24);
        b[at + 1] = (byte) (v >>> 16);
        b[at + 2] = (byte) (v >>> 8);
        b[at + 3] = (byte) v;
    }
}
