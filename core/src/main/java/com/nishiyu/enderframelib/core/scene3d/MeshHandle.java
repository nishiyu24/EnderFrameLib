package com.nishiyu.enderframelib.core.scene3d;

/**
 * ジオメトリプールに登録された 3D メッシュのハンドル。
 */
public record MeshHandle(
        int meshId,
        int firstVertex,
        int vertexCount,
        int firstIndex,
        int indexCount,
        float minX,
        float minY,
        float minZ,
        float maxX,
        float maxY,
        float maxZ) {
}
