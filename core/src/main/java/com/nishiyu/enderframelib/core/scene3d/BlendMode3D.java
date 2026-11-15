package com.nishiyu.enderframelib.core.scene3d;

/**
 * 3D マテリアルのブレンドモード。
 */
public enum BlendMode3D {
    OPAQUE(0),
    MASK(1),
    TRANSPARENT(2);

    private final int code;

    BlendMode3D(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }
}
