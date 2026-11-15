package com.nishiyu.enderframelib.core.scene3d;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 1 フレーム分のフラスタムカリング・ソート・バッチ化の結果を保持するアップロード計画。
 */
public final class Scene3DUploadPlan {
    private final List<DrawBatch3D> batches = new ArrayList<>();
    private final List<DrawBatch3D> batchesView = Collections.unmodifiableList(batches);
    private int[] sortedNodes = new int[256];
    private int instanceCount;
    private int culledCount;
    private int totalRenderableCount;

    void reset(int requiredCapacity) {
        batches.clear();
        if (sortedNodes.length < requiredCapacity) {
            sortedNodes = new int[Math.max(requiredCapacity, sortedNodes.length * 2)];
        }
        instanceCount = 0;
        culledCount = 0;
        totalRenderableCount = 0;
    }

    void setCounts(int instanceCount, int culledCount, int totalRenderableCount) {
        this.instanceCount = instanceCount;
        this.culledCount = culledCount;
        this.totalRenderableCount = totalRenderableCount;
    }

    int[] sortedNodesArray() {
        return sortedNodes;
    }

    void addBatch(DrawBatch3D batch) {
        batches.add(batch);
    }

    public List<DrawBatch3D> batches() {
        return batchesView;
    }

    public int instanceCount() {
        return instanceCount;
    }

    public int culledCount() {
        return culledCount;
    }

    public int totalRenderableCount() {
        return totalRenderableCount;
    }

    public int batchCount() {
        return batches.size();
    }
}
