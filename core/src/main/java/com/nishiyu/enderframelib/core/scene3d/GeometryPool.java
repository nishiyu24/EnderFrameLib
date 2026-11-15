package com.nishiyu.enderframelib.core.scene3d;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * 48B 頂点（Pos3 + Norm3 + UV2 + Tangent4 = 12 floats）と 32-bit インデックスを単一の
 * 連続ダイレクトバッファに詰めるジオメトリプール。
 */
public final class GeometryPool {
    public static final int VERTEX_FLOATS = 12;
    public static final int VERTEX_STRIDE_BYTES = VERTEX_FLOATS * 4;
    public static final int INDEX_STRIDE_BYTES = 4;

    private final List<MeshHandle> meshes = new ArrayList<>();
    private ByteBuffer vertexBuffer = ByteBuffer.allocateDirect(4096 * VERTEX_STRIDE_BYTES).order(ByteOrder.nativeOrder());
    private ByteBuffer indexBuffer = ByteBuffer.allocateDirect(8192 * INDEX_STRIDE_BYTES).order(ByteOrder.nativeOrder());
    private int totalVertices;
    private int totalIndices;
    private long version = 1L;

    public int meshCount() { return meshes.size(); }
    public int totalVertices() { return totalVertices; }
    public int totalIndices() { return totalIndices; }
    public long version() { return version; }

    public MeshHandle mesh(int meshId) {
        return meshes.get(meshId);
    }

    public MeshHandle meshOrNull(int meshId) {
        if (meshId < 0 || meshId >= meshes.size()) return null;
        return meshes.get(meshId);
    }

    public ByteBuffer vertexBufferSlice() {
        return vertexBuffer.slice(0, totalVertices * VERTEX_STRIDE_BYTES).order(ByteOrder.nativeOrder());
    }

    public ByteBuffer indexBufferSlice() {
        return indexBuffer.slice(0, totalIndices * INDEX_STRIDE_BYTES).order(ByteOrder.nativeOrder());
    }

    public synchronized MeshHandle registerMesh(float[] vertices, int[] indices) {
        if (vertices.length % VERTEX_FLOATS != 0 || vertices.length == 0) {
            throw new IllegalArgumentException("vertices length must be positive multiple of " + VERTEX_FLOATS);
        }
        if (indices.length == 0) {
            throw new IllegalArgumentException("indices must not be empty");
        }
        int vCount = vertices.length / VERTEX_FLOATS;
        int iCount = indices.length;

        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
        for (int v = 0; v < vCount; v++) {
            int base = v * VERTEX_FLOATS;
            float x = vertices[base];
            float y = vertices[base + 1];
            float z = vertices[base + 2];
            if (x < minX) minX = x;
            if (x > maxX) maxX = x;
            if (y < minY) minY = y;
            if (y > maxY) maxY = y;
            if (z < minZ) minZ = z;
            if (z > maxZ) maxZ = z;
        }
        for (int idx : indices) {
            if (idx < 0 || idx >= vCount) {
                throw new IllegalArgumentException("Index out of vertex range [0," + vCount + "): " + idx);
            }
        }

        ensureVertexCapacity(totalVertices + vCount);
        ensureIndexCapacity(totalIndices + iCount);

        int vByteOff = totalVertices * VERTEX_STRIDE_BYTES;
        for (int i = 0; i < vertices.length; i++) {
            vertexBuffer.putFloat(vByteOff + i * 4, vertices[i]);
        }
        int iByteOff = totalIndices * INDEX_STRIDE_BYTES;
        for (int i = 0; i < indices.length; i++) {
            indexBuffer.putInt(iByteOff + i * 4, indices[i]);
        }

        int meshId = meshes.size();
        MeshHandle handle = new MeshHandle(
                meshId, totalVertices, vCount, totalIndices, iCount,
                minX, minY, minZ, maxX, maxY, maxZ);
        meshes.add(handle);
        totalVertices += vCount;
        totalIndices += iCount;
        version++;
        return handle;
    }

    public MeshHandle createCube(float size) {
        return createBox(size, size, size);
    }

    public MeshHandle createBox(float width, float height, float depth) {
        float hx = width * 0.5f;
        float hy = height * 0.5f;
        float hz = depth * 0.5f;
        float[] v = new float[24 * VERTEX_FLOATS];
        int[] idx = new int[36];
        int vc = 0;
        int ic = 0;

        // +Z (Front)
        vc = putQuad(v, vc,
                -hx, -hy, hz,  hx, -hy, hz,  hx, hy, hz, -hx, hy, hz,
                0, 0, 1, 1, 0, 0, 1);
        ic = putQuadIdx(idx, ic, 0);
        // -Z (Back)
        vc = putQuad(v, vc,
                hx, -hy, -hz, -hx, -hy, -hz, -hx, hy, -hz, hx, hy, -hz,
                0, 0, -1, -1, 0, 0, 1);
        ic = putQuadIdx(idx, ic, 4);
        // +X (Right)
        vc = putQuad(v, vc,
                hx, -hy, hz, hx, -hy, -hz, hx, hy, -hz, hx, hy, hz,
                1, 0, 0, 0, 0, -1, 1);
        ic = putQuadIdx(idx, ic, 8);
        // -X (Left)
        vc = putQuad(v, vc,
                -hx, -hy, -hz, -hx, -hy, hz, -hx, hy, hz, -hx, hy, -hz,
                -1, 0, 0, 0, 0, 1, 1);
        ic = putQuadIdx(idx, ic, 12);
        // +Y (Top)
        vc = putQuad(v, vc,
                -hx, hy, hz, hx, hy, hz, hx, hy, -hz, -hx, hy, -hz,
                0, 1, 0, 1, 0, 0, 1);
        ic = putQuadIdx(idx, ic, 16);
        // -Y (Bottom)
        putQuad(v, vc,
                -hx, -hy, -hz, hx, -hy, -hz, hx, -hy, hz, -hx, -hy, hz,
                0, -1, 0, 1, 0, 0, 1);
        putQuadIdx(idx, ic, 20);

        return registerMesh(v, idx);
    }

    public MeshHandle createPlane(float width, float depth) {
        float hx = width * 0.5f;
        float hz = depth * 0.5f;
        float[] v = new float[4 * VERTEX_FLOATS];
        putQuad(v, 0, -hx, 0, hz, hx, 0, hz, hx, 0, -hz, -hx, 0, -hz, 0, 1, 0, 1, 0, 0, 1);
        int[] idx = {0, 1, 2, 0, 2, 3};
        return registerMesh(v, idx);
    }

    public MeshHandle createSphere(float radius, int rings, int sectors) {
        int r = Math.max(4, rings);
        int s = Math.max(6, sectors);
        int vCount = (r + 1) * (s + 1);
        float[] v = new float[vCount * VERTEX_FLOATS];
        int vi = 0;
        for (int i = 0; i <= r; i++) {
            float vCoord = (float) i / r;
            double phi = vCoord * Math.PI;
            float sinPhi = (float) Math.sin(phi);
            float cosPhi = (float) Math.cos(phi);
            for (int j = 0; j <= s; j++) {
                float uCoord = (float) j / s;
                double theta = uCoord * Math.PI * 2.0;
                float sinTheta = (float) Math.sin(theta);
                float cosTheta = (float) Math.cos(theta);
                float nx = sinPhi * cosTheta;
                float ny = cosPhi;
                float nz = sinPhi * sinTheta;
                float tx = -sinTheta;
                float tz = cosTheta;
                vi = putVertex(v, vi, nx * radius, ny * radius, nz * radius,
                        nx, ny, nz, uCoord, vCoord, tx, 0.0f, tz, 1.0f);
            }
        }
        int[] idx = new int[r * s * 6];
        int ii = 0;
        for (int i = 0; i < r; i++) {
            for (int j = 0; j < s; j++) {
                int k1 = i * (s + 1) + j;
                int k2 = k1 + s + 1;
                idx[ii++] = k1;
                idx[ii++] = k2;
                idx[ii++] = k1 + 1;
                idx[ii++] = k1 + 1;
                idx[ii++] = k2;
                idx[ii++] = k2 + 1;
            }
        }
        return registerMesh(v, idx);
    }

    private static int putVertex(float[] dst, int off,
                                 float px, float py, float pz,
                                 float nx, float ny, float nz,
                                 float u, float v,
                                 float tx, float ty, float tz, float tw) {
        dst[off] = px; dst[off + 1] = py; dst[off + 2] = pz;
        dst[off + 3] = nx; dst[off + 4] = ny; dst[off + 5] = nz;
        dst[off + 6] = u; dst[off + 7] = v;
        dst[off + 8] = tx; dst[off + 9] = ty; dst[off + 10] = tz; dst[off + 11] = tw;
        return off + VERTEX_FLOATS;
    }

    private static int putQuad(float[] dst, int off,
                               float x0, float y0, float z0,
                               float x1, float y1, float z1,
                               float x2, float y2, float z2,
                               float x3, float y3, float z3,
                               float nx, float ny, float nz,
                               float tx, float ty, float tz, float tw) {
        off = putVertex(dst, off, x0, y0, z0, nx, ny, nz, 0f, 0f, tx, ty, tz, tw);
        off = putVertex(dst, off, x1, y1, z1, nx, ny, nz, 1f, 0f, tx, ty, tz, tw);
        off = putVertex(dst, off, x2, y2, z2, nx, ny, nz, 1f, 1f, tx, ty, tz, tw);
        return putVertex(dst, off, x3, y3, z3, nx, ny, nz, 0f, 1f, tx, ty, tz, tw);
    }

    private static int putQuadIdx(int[] dst, int off, int baseV) {
        dst[off] = baseV;
        dst[off + 1] = baseV + 1;
        dst[off + 2] = baseV + 2;
        dst[off + 3] = baseV;
        dst[off + 4] = baseV + 2;
        dst[off + 5] = baseV + 3;
        return off + 6;
    }

    private void ensureVertexCapacity(int requiredVertices) {
        int neededBytes = requiredVertices * VERTEX_STRIDE_BYTES;
        if (neededBytes <= vertexBuffer.capacity()) return;
        int newCap = Math.max(vertexBuffer.capacity() * 2, neededBytes);
        ByteBuffer next = ByteBuffer.allocateDirect(newCap).order(ByteOrder.nativeOrder());
        int usedBytes = totalVertices * VERTEX_STRIDE_BYTES;
        if (usedBytes > 0) {
            next.put(0, vertexBuffer, 0, usedBytes);
        }
        vertexBuffer = next;
    }

    private void ensureIndexCapacity(int requiredIndices) {
        int neededBytes = requiredIndices * INDEX_STRIDE_BYTES;
        if (neededBytes <= indexBuffer.capacity()) return;
        int newCap = Math.max(indexBuffer.capacity() * 2, neededBytes);
        ByteBuffer next = ByteBuffer.allocateDirect(newCap).order(ByteOrder.nativeOrder());
        int usedBytes = totalIndices * INDEX_STRIDE_BYTES;
        if (usedBytes > 0) {
            next.put(0, indexBuffer, 0, usedBytes);
        }
        indexBuffer = next;
    }
}
