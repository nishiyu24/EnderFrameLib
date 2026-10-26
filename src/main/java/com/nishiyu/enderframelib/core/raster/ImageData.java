package com.nishiyu.enderframelib.core.raster;

/** Canvas 2D の {@code ImageData}。{@code data} は非乗算の RGBA（1 画素 4 バイト、行優先）。 */
public final class ImageData {
    private final int width;
    private final int height;
    private final byte[] data;

    public ImageData(int width, int height) {
        if (width <= 0 || height <= 0 || (long) width * height > 64L * 1024 * 1024 / 4) {
            throw new IllegalArgumentException("IndexSizeError: " + width + "x" + height);
        }
        this.width = width;
        this.height = height;
        this.data = new byte[width * height * 4];
    }

    public ImageData(int width, int height, byte[] data) {
        if (width <= 0 || height <= 0 || data.length != width * height * 4) {
            throw new IllegalArgumentException("InvalidStateError: data length " + data.length);
        }
        this.width = width;
        this.height = height;
        this.data = data;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public byte[] data() {
        return data;
    }

    /** 画素の非乗算 ARGB。 */
    public int argbAt(int x, int y) {
        int i = (y * width + x) * 4;
        return ((data[i + 3] & 0xFF) << 24) | ((data[i] & 0xFF) << 16) | ((data[i + 1] & 0xFF) << 8) | (data[i + 2] & 0xFF);
    }

    public void setArgb(int x, int y, int argb) {
        int i = (y * width + x) * 4;
        data[i] = (byte) (argb >> 16);
        data[i + 1] = (byte) (argb >> 8);
        data[i + 2] = (byte) argb;
        data[i + 3] = (byte) (argb >>> 24);
    }
}
