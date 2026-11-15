package com.nishiyu.enderframelib.core.paint.vulkan;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.BufferDesc;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.BufferUsage;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.FilterMode;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.LoadOp;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.PipelineDesc;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.TextureDesc;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.TextureFormat;
import com.nishiyu.enderframelib.core.kernel.gpu.shader.ViewFormat;

import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuBindings;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuBuffer;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuCaps;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuDevice;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuFrame;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuPass;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuPipeline;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuTexture;
import com.nishiyu.enderframelib.core.kernel.gpu.shader.ShaderProgram;
import com.nishiyu.enderframelib.core.kernel.gpu.shader.ShaderPrograms;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkBufferViewCreateInfo;
import org.lwjgl.vulkan.VkClearAttachment;
import org.lwjgl.vulkan.VkClearRect;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkExtent3D;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkFramebufferCreateInfo;
import org.lwjgl.vulkan.VkImageCopy;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageResolve;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPhysicalDeviceLimits;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderPassBeginInfo;
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import org.lwjgl.vulkan.VkSubmitInfo;
import org.lwjgl.vulkan.VkViewport;

/**
 * {@link GpuDevice} の Vulkan 1.1 実装（G3）。
 *
 * <ul>
 *   <li><b>座標規約</b>: RHI の左上原点・事前乗算アルファを Vulkan の左上原点（{@code y=0, height=+H},
 *       {@link GpuCaps#bottomUpFramebuffer()} = {@code false}, {@link GpuCaps#zeroToOneDepth()} = {@code true}）へそのまま対応。</li>
 *   <li><b>永続 3 面リング</b>: {@link BufferUsage#RECORD}/{@code VERTEX}/{@code INDEX} とフレーム単位の動的 UBO アリーナは
 *       3 面リング＋{@code VkFence} で同期し、{@code glUniform*} やフレーム内キュー待ちを排除する。</li>
 * </ul>
 */
public final class VkDevice implements GpuDevice {
    public static final int DEFAULT_RING = 3;
    private static final long FENCE_TIMEOUT_NS = 10_000_000_000L;
    private static final int INITIAL_UBO_BYTES = 256 * 1024;
    private static final int INITIAL_UPLOAD_BYTES = 4 * 1024 * 1024;
    private static final AtomicLong RESOURCE_UID = new AtomicLong(1L);

    private final VkContext ctx;
    private final boolean ownsContext;
    private final GpuCaps caps;
    private final VkPipelines pipelines;
    private final int ringSize;
    private final long minUboAlign;
    private final long samplerNearest;
    private final long samplerLinear;

    private final long[] commandPools;
    private final VkCommandBuffer[] commandBuffers;
    private final long[] fences;
    private final long[] fenceSubmitSeq;
    private final boolean[] fenceSubmitted;
    private long submitSeq;
    private long completedSeq;

    // 動的 UBO リング（フレームごと）
    private final MappedBuffer[] uboArenas;
    private final int[] uboCursors;

    // Upload ステージングリング（フレームごと）
    private final MappedBuffer[] uploadArenas;
    private final int[] uploadCursors;

    // Readback 用ステージング
    private MappedBuffer readbackStaging;

    // タイムスタンプ計測
    private final long timestampPool;
    private final boolean[] timestampWritten;
    private final float timestampPeriodNs;
    private boolean timingEnabled;
    private double lastGpuMillis = -1.0;

    private final List<Buffer> allBuffers = new ArrayList<>();
    private final List<Texture> allTextures = new ArrayList<>();
    private final ArrayDeque<Texture> pendingInits = new ArrayDeque<>();
    private final List<RetiredTask> retiredTasks = new ArrayList<>();

    private int currentSeg;
    private boolean inFrame;
    private boolean closed;

    private record RetiredTask(long retireAfterSeq, Runnable destroy) {}

    private static final class MappedBuffer {
        final long buffer;
        final long memory;
        final int capacity;
        final ByteBuffer map;

        MappedBuffer(long buffer, long memory, int capacity, ByteBuffer map) {
            this.buffer = buffer;
            this.memory = memory;
            this.capacity = capacity;
            this.map = map;
        }

        void destroy(org.lwjgl.vulkan.VkDevice dev) {
            if (map != null) VK10.vkUnmapMemory(dev, memory);
            if (buffer != VK10.VK_NULL_HANDLE) VK10.vkDestroyBuffer(dev, buffer, null);
            if (memory != VK10.VK_NULL_HANDLE) VK10.vkFreeMemory(dev, memory, null);
        }
    }

    /** 検証レイヤ無効のヘッドレス {@link VkDevice} を作る。 */
    public static VkDevice createHeadless() {
        return createHeadless(false);
    }

    /** 検証レイヤの有効化を指定してヘッドレス {@link VkDevice} を作る。 */
    public static VkDevice createHeadless(boolean validation) {
        return new VkDevice(VkContext.createHeadless(validation), true, DEFAULT_RING);
    }

    public VkDevice(VkContext ctx, boolean ownsContext) {
        this(ctx, ownsContext, DEFAULT_RING);
    }

    public VkDevice(VkContext ctx, boolean ownsContext, int ringSize) {
        if (ringSize < 1) throw new IllegalArgumentException("ringSize: " + ringSize);
        this.ctx = ctx;
        this.ownsContext = ownsContext;
        this.ringSize = ringSize;
        this.pipelines = new VkPipelines(ctx);

        VkPhysicalDeviceLimits lim = ctx.limits();
        this.minUboAlign = Math.max(16L, lim.minUniformBufferOffsetAlignment());
        int colorMask = lim.framebufferColorSampleCounts() & lim.framebufferDepthSampleCounts();
        int maxSamples = 1;
        for (int s : new int[]{4, 2}) {
            if ((colorMask & VkPipelines.sampleCountFlag(s)) != 0) {
                maxSamples = s;
                break;
            }
        }
        boolean tsSupported = lim.timestampComputeAndGraphics() && lim.timestampPeriod() > 0f;
        this.caps = new GpuCaps(
                true, true, true, tsSupported,
                lim.maxImageDimension2D(), maxSamples > 1, maxSamples,
                false, true);
        this.timestampPeriodNs = lim.timestampPeriod();

        this.samplerNearest = createSampler(VK10.VK_FILTER_NEAREST);
        this.samplerLinear = createSampler(VK10.VK_FILTER_LINEAR);
        this.pipelines.trackSampler(samplerNearest);
        this.pipelines.trackSampler(samplerLinear);

        this.commandPools = new long[ringSize];
        this.commandBuffers = new VkCommandBuffer[ringSize];
        this.fences = new long[ringSize];
        this.fenceSubmitSeq = new long[ringSize];
        this.fenceSubmitted = new boolean[ringSize];
        this.uboArenas = new MappedBuffer[ringSize];
        this.uboCursors = new int[ringSize];
        this.uploadArenas = new MappedBuffer[ringSize];
        this.uploadCursors = new int[ringSize];
        this.timestampWritten = new boolean[ringSize];

        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer pLong = stack.mallocLong(1);
            PointerBuffer pCmd = stack.mallocPointer(1);
            for (int i = 0; i < ringSize; i++) {
                VkCommandPoolCreateInfo cpCi = VkCommandPoolCreateInfo.calloc(stack)
                        .sType(VK10.VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                        .queueFamilyIndex(ctx.graphicsQueueFamily)
                        .flags(VK10.VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT);
                VkContext.vkCheck(VK10.vkCreateCommandPool(ctx.device, cpCi, null, pLong), "vkCreateCommandPool");
                commandPools[i] = pLong.get(0);

                VkCommandBufferAllocateInfo cbAi = VkCommandBufferAllocateInfo.calloc(stack)
                        .sType(VK10.VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                        .commandPool(commandPools[i])
                        .level(VK10.VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                        .commandBufferCount(1);
                VkContext.vkCheck(VK10.vkAllocateCommandBuffers(ctx.device, cbAi, pCmd), "vkAllocateCommandBuffers");
                commandBuffers[i] = new VkCommandBuffer(pCmd.get(0), ctx.device);

                VkFenceCreateInfo fCi = VkFenceCreateInfo.calloc(stack)
                        .sType(VK10.VK_STRUCTURE_TYPE_FENCE_CREATE_INFO)
                        .flags(VK10.VK_FENCE_CREATE_SIGNALED_BIT);
                VkContext.vkCheck(VK10.vkCreateFence(ctx.device, fCi, null, pLong), "vkCreateFence");
                fences[i] = pLong.get(0);

                uboArenas[i] = createMappedBuffer(INITIAL_UBO_BYTES,
                        VK10.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
                uploadArenas[i] = createMappedBuffer(INITIAL_UPLOAD_BYTES,
                        VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT, 0);
            }

            if (tsSupported) {
                VkQueryPoolCreateInfo qpCi = VkQueryPoolCreateInfo.calloc(stack)
                        .sType(VK10.VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO)
                        .queryType(VK10.VK_QUERY_TYPE_TIMESTAMP)
                        .queryCount(ringSize * 2);
                VkContext.vkCheck(VK10.vkCreateQueryPool(ctx.device, qpCi, null, pLong), "vkCreateQueryPool");
                this.timestampPool = pLong.get(0);
            } else {
                this.timestampPool = VK10.VK_NULL_HANDLE;
            }
        }
    }

    public VkContext context() {
        return ctx;
    }

    public int validationErrorCount() {
        return ctx.validationErrorCount();
    }

    public void setTimingEnabled(boolean enabled) {
        this.timingEnabled = enabled && caps.timestampQuery();
    }

    public boolean isTimingEnabled() {
        return timingEnabled;
    }

    public double lastGpuMillis() {
        return lastGpuMillis;
    }

    public void waitIdle() {
        if (closed) return;
        VK10.vkDeviceWaitIdle(ctx.device);
        completedSeq = submitSeq;
        for (int s = 0; s < ringSize; s++) {
            fenceSubmitted[s] = false;
            harvestTimestamp(s);
        }
        drainRetired();
    }

    private void harvestTimestamp(int seg) {
        if (!timestampWritten[seg] || timestampPool == VK10.VK_NULL_HANDLE) return;
        timestampWritten[seg] = false;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer vals = stack.mallocLong(2);
            int err = VK10.vkGetQueryPoolResults(
                    ctx.device, timestampPool, seg * 2, 2, vals, 8,
                    VK10.VK_QUERY_RESULT_64_BIT);
            if (err == VK10.VK_SUCCESS) {
                long delta = vals.get(1) - vals.get(0);
                if (delta >= 0) {
                    lastGpuMillis = (delta * (double) timestampPeriodNs) / 1_000_000.0;
                }
            }
        }
    }

    @Override
    public GpuCaps caps() {
        return caps;
    }

    @Override
    public long maxBufferBytes() {
        long elems = Integer.toUnsignedLong(ctx.limits().maxTexelBufferElements());
        return Math.min(64L * 1024 * 1024, elems * 2L);
    }

    @Override
    public GpuBuffer createBuffer(BufferDesc desc) {
        if (closed) throw new IllegalStateException("closed");
        Buffer b = new Buffer(desc);
        allBuffers.add(b);
        return b;
    }

    @Override
    public GpuTexture createTexture(TextureDesc desc) {
        if (closed) throw new IllegalStateException("closed");
        if (desc.width() > caps.maxTextureSize() || desc.height() > caps.maxTextureSize()) {
            throw new IllegalArgumentException("texture too large: " + desc);
        }
        int samples = desc.samples() <= 1 ? 1 : Math.min(desc.samples(), caps.maxSamples());
        if (samples > 1 && !desc.renderTarget()) {
            throw new IllegalArgumentException("MSAA texture must be a renderTarget: " + desc);
        }
        Texture t = new Texture(desc.width(), desc.height(), desc.format(), samples, desc.renderTarget(), desc.filter());
        allTextures.add(t);
        pendingInits.add(t);
        return t;
    }

    @Override
    public GpuTexture wrapExternal(long nativeHandle, int width, int height) {
        if (closed) throw new IllegalStateException("closed");
        if (nativeHandle == VK10.VK_NULL_HANDLE || width <= 0 || height <= 0) {
            throw new IllegalArgumentException("invalid external texture: handle=" + nativeHandle + " " + width + "x" + height);
        }
        return new ExternalTexture(nativeHandle, width, height);
    }

    @Override
    public GpuPipeline createPipeline(PipelineDesc desc) {
        if (closed) throw new IllegalStateException("closed");
        return new PipelineImpl(desc, ShaderPrograms.byId(desc.shaderId()));
    }

    @Override
    public long nativeHandle(GpuTexture texture) {
        if (texture instanceof ExternalTexture ext) return ext.handle;
        return ((Texture) texture).image;
    }

    @Override
    public GpuFrame beginFrame() {
        if (closed) throw new IllegalStateException("closed");
        if (inFrame) throw new IllegalStateException("フレームが開いたまま");
        inFrame = true;

        int seg = currentSeg;
        if (fenceSubmitted[seg]) {
            VkContext.vkCheck(VK10.vkWaitForFences(ctx.device, fences[seg], true, FENCE_TIMEOUT_NS), "vkWaitForFences");
            fenceSubmitted[seg] = false;
            completedSeq = Math.max(completedSeq, fenceSubmitSeq[seg]);
            harvestTimestamp(seg);
        }
        drainRetired();

        VK10.vkResetCommandPool(ctx.device, commandPools[seg], 0);
        uboCursors[seg] = 0;
        uploadCursors[seg] = 0;

        VkCommandBuffer cmd = commandBuffers[seg];
        beginCommandBuffer(cmd);
        if (timingEnabled && timestampPool != VK10.VK_NULL_HANDLE) {
            VK10.vkCmdResetQueryPool(cmd, timestampPool, seg * 2, 2);
            VK10.vkCmdWriteTimestamp(cmd, VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, timestampPool, seg * 2);
            timestampWritten[seg] = true;
        }
        flushPendingInits(cmd);
        return new FrameImpl(seg, cmd);
    }

    /** 現在開いているフレーム外から同期的にスワップチェーンへ転送する補助（{@link VkSwapchain} 用）。 */
    void blitTextureToSwapchain(Texture srcTex, long swapchainImage, int srcW, int srcH,
                                int dstW, int dstH, long waitSem, long signalSem, long fence) {
        // 呼び出し時点でフレームが閉じているため、作業用コマンドを実行できる
        int seg = currentSeg;
        if (fenceSubmitted[seg]) {
            VkContext.vkCheck(VK10.vkWaitForFences(ctx.device, fences[seg], true, FENCE_TIMEOUT_NS), "vkWaitForFences");
            fenceSubmitted[seg] = false;
            completedSeq = Math.max(completedSeq, fenceSubmitSeq[seg]);
        }
        drainRetired();
        VK10.vkResetCommandPool(ctx.device, commandPools[seg], 0);
        VkCommandBuffer cmd = commandBuffers[seg];
        beginCommandBuffer(cmd);
        transitionLayout(cmd, srcTex.image, VK10.VK_IMAGE_ASPECT_COLOR_BIT,
                srcTex.layout, VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK10.VK_ACCESS_MEMORY_WRITE_BIT | VK10.VK_ACCESS_SHADER_READ_BIT, VK10.VK_ACCESS_TRANSFER_READ_BIT);
        transitionLayout(cmd, swapchainImage, VK10.VK_IMAGE_ASPECT_COLOR_BIT,
                VK10.VK_IMAGE_LAYOUT_UNDEFINED, VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                0, VK10.VK_ACCESS_TRANSFER_WRITE_BIT);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            org.lwjgl.vulkan.VkImageBlit.Buffer blit = org.lwjgl.vulkan.VkImageBlit.calloc(1, stack);
            blit.get(0).srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            blit.get(0).srcOffsets(0).set(0, 0, 0);
            blit.get(0).srcOffsets(1).set(srcW, srcH, 1);
            blit.get(0).dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            blit.get(0).dstOffsets(0).set(0, 0, 0);
            blit.get(0).dstOffsets(1).set(dstW, dstH, 1);
            VK10.vkCmdBlitImage(cmd, srcTex.image, VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    swapchainImage, VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, blit, VK10.VK_FILTER_NEAREST);
        }

        transitionLayout(cmd, srcTex.image, VK10.VK_IMAGE_ASPECT_COLOR_BIT,
                VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK10.VK_PIPELINE_STAGE_TRANSFER_BIT, VK10.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                VK10.VK_ACCESS_TRANSFER_READ_BIT, VK10.VK_ACCESS_SHADER_READ_BIT);
        srcTex.layout = VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        transitionLayout(cmd, swapchainImage, VK10.VK_IMAGE_ASPECT_COLOR_BIT,
                VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, org.lwjgl.vulkan.KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                VK10.VK_PIPELINE_STAGE_TRANSFER_BIT, VK10.VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                VK10.VK_ACCESS_TRANSFER_WRITE_BIT, 0);

        VkContext.vkCheck(VK10.vkEndCommandBuffer(cmd), "vkEndCommandBuffer(swapchain)");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSubmitInfo si = VkSubmitInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_SUBMIT_INFO)
                    .waitSemaphoreCount(1)
                    .pWaitSemaphores(stack.longs(waitSem))
                    .pWaitDstStageMask(stack.ints(VK10.VK_PIPELINE_STAGE_TRANSFER_BIT))
                    .pCommandBuffers(stack.pointers(cmd))
                    .pSignalSemaphores(stack.longs(signalSem));
            VK10.vkResetFences(ctx.device, fence);
            VkContext.vkCheck(VK10.vkQueueSubmit(ctx.graphicsQueue, si, fence), "vkQueueSubmit(swapchain)");
        }
    }

    private void beginCommandBuffer(VkCommandBuffer cmd) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBufferBeginInfo bi = VkCommandBufferBeginInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                    .flags(VK10.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            VkContext.vkCheck(VK10.vkBeginCommandBuffer(cmd, bi), "vkBeginCommandBuffer");
        }
    }

    private void flushPendingInits(VkCommandBuffer cmd) {
        while (!pendingInits.isEmpty()) {
            Texture t = pendingInits.poll();
            if (t.closed || t.layout != VK10.VK_IMAGE_LAYOUT_UNDEFINED) continue;
            if (t.format == TextureFormat.DEPTH24) {
                transitionLayout(cmd, t.image, depthAspectMask(t.vkFormat),
                        VK10.VK_IMAGE_LAYOUT_UNDEFINED, VK10.VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,
                        VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                        VK10.VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK10.VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT,
                        0, VK10.VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK10.VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);
                t.layout = VK10.VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL;
            } else if (t.samples > 1) {
                transitionLayout(cmd, t.image, VK10.VK_IMAGE_ASPECT_COLOR_BIT,
                        VK10.VK_IMAGE_LAYOUT_UNDEFINED, VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                        VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                        0, VK10.VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK10.VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);
                t.layout = VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
            } else {
                transitionLayout(cmd, t.image, VK10.VK_IMAGE_ASPECT_COLOR_BIT,
                        VK10.VK_IMAGE_LAYOUT_UNDEFINED, VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                        VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK10.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                        0, VK10.VK_ACCESS_SHADER_READ_BIT);
                t.layout = VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
            }
        }
    }

    private void retireOrDestroy(Runnable destroy) {
        if (completedSeq >= submitSeq && !inFrame) {
            destroy.run();
        } else {
            retiredTasks.add(new RetiredTask(submitSeq, destroy));
        }
    }

    private void drainRetired() {
        Iterator<RetiredTask> it = retiredTasks.iterator();
        while (it.hasNext()) {
            RetiredTask r = it.next();
            if (completedSeq >= r.retireAfterSeq) {
                r.destroy.run();
                it.remove();
            }
        }
    }

    private void waitAllFrames() {
        VK10.vkDeviceWaitIdle(ctx.device);
        Arrays.fill(fenceSubmitted, false);
        completedSeq = submitSeq;
        drainRetired();
    }

    private long createSampler(int vkFilter) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSamplerCreateInfo ci = VkSamplerCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO)
                    .magFilter(vkFilter)
                    .minFilter(vkFilter)
                    .mipmapMode(VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeV(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .anisotropyEnable(false)
                    .maxAnisotropy(1.0f)
                    .compareEnable(false)
                    .minLod(0.0f)
                    .maxLod(0.0f)
                    .unnormalizedCoordinates(false);
            LongBuffer pSampler = stack.mallocLong(1);
            VkContext.vkCheck(VK10.vkCreateSampler(ctx.device, ci, null, pSampler), "vkCreateSampler");
            return pSampler.get(0);
        }
    }

    private MappedBuffer createMappedBuffer(int sizeBytes, int usageFlags, int preferredMemProps) {
        int cap = Math.max(64, (sizeBytes + 15) & ~15);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo bCi = VkBufferCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                    .size(cap)
                    .usage(usageFlags)
                    .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE);
            LongBuffer pBuf = stack.mallocLong(1);
            VkContext.vkCheck(VK10.vkCreateBuffer(ctx.device, bCi, null, pBuf), "vkCreateBuffer");
            long buf = pBuf.get(0);

            VkMemoryRequirements req = VkMemoryRequirements.calloc(stack);
            VK10.vkGetBufferMemoryRequirements(ctx.device, buf, req);
            int memType = ctx.findMemoryType(req.memoryTypeBits(),
                    VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                    preferredMemProps);
            VkMemoryAllocateInfo ma = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(req.size())
                    .memoryTypeIndex(memType);
            LongBuffer pMem = stack.mallocLong(1);
            VkContext.vkCheck(VK10.vkAllocateMemory(ctx.device, ma, null, pMem), "vkAllocateMemory(buffer)");
            long mem = pMem.get(0);
            VkContext.vkCheck(VK10.vkBindBufferMemory(ctx.device, buf, mem, 0L), "vkBindBufferMemory");

            PointerBuffer ppData = stack.mallocPointer(1);
            VkContext.vkCheck(VK10.vkMapMemory(ctx.device, mem, 0L, cap, 0, ppData), "vkMapMemory");
            ByteBuffer map = MemoryUtil.memByteBuffer(ppData.get(0), cap).order(ByteOrder.nativeOrder());
            return new MappedBuffer(buf, mem, cap, map);
        }
    }

    private MappedBuffer ensureUboCapacity(int seg, int neededBytes) {
        int cursor = (int) ((uboCursors[seg] + minUboAlign - 1) & ~(minUboAlign - 1));
        MappedBuffer cur = uboArenas[seg];
        if (cursor + neededBytes <= cur.capacity) {
            return cur;
        }
        int newCap = Math.max(cur.capacity * 2, cursor + neededBytes);
        MappedBuffer next = createMappedBuffer(newCap,
                VK10.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        MappedBuffer old = cur;
        retireOrDestroy(() -> old.destroy(ctx.device));
        pipelines.invalidateCache();
        uboArenas[seg] = next;
        return next;
    }

    private int allocUploadSlice(int seg, int bytes) {
        int curOff = (uploadCursors[seg] + 63) & ~63;
        MappedBuffer cur = uploadArenas[seg];
        if (curOff + bytes > cur.capacity) {
            int newCap = Math.max(cur.capacity * 2, curOff + bytes);
            MappedBuffer next = createMappedBuffer(newCap, VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT, 0);
            MappedBuffer old = cur;
            retireOrDestroy(() -> old.destroy(ctx.device));
            uploadArenas[seg] = next;
        }
        uploadCursors[seg] = curOff + bytes;
        return curOff;
    }

    private MappedBuffer ensureReadbackCapacity(int bytes) {
        if (readbackStaging != null && readbackStaging.capacity >= bytes) {
            return readbackStaging;
        }
        int cap = Math.max(65536, bytes);
        if (readbackStaging != null) {
            MappedBuffer old = readbackStaging;
            retireOrDestroy(() -> old.destroy(ctx.device));
        }
        readbackStaging = createMappedBuffer(cap, VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT, 0);
        return readbackStaging;
    }

    private static int depthAspectMask(int vkFormat) {
        boolean hasStencil = vkFormat == VK10.VK_FORMAT_D24_UNORM_S8_UINT
                || vkFormat == VK10.VK_FORMAT_D32_SFLOAT_S8_UINT
                || vkFormat == VK10.VK_FORMAT_D16_UNORM_S8_UINT;
        return hasStencil ? (VK10.VK_IMAGE_ASPECT_DEPTH_BIT | VK10.VK_IMAGE_ASPECT_STENCIL_BIT) : VK10.VK_IMAGE_ASPECT_DEPTH_BIT;
    }

    private static void transitionLayout(VkCommandBuffer cmd, long image, int aspectMask,
                                         int oldLayout, int newLayout,
                                         int srcStage, int dstStage, int srcAccess, int dstAccess) {
        if (oldLayout == newLayout) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack);
            barrier.get(0)
                    .sType(VK10.VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                    .oldLayout(oldLayout)
                    .newLayout(newLayout)
                    .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .image(image)
                    .srcAccessMask(srcAccess)
                    .dstAccessMask(dstAccess);
            barrier.get(0).subresourceRange()
                    .aspectMask(aspectMask)
                    .baseMipLevel(0).levelCount(1)
                    .baseArrayLayer(0).layerCount(1);
            VK10.vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, null, null, barrier);
        }
    }

    private static void checkRegion(Texture t, int x, int y, int w, int h) {
        if (x < 0 || y < 0 || w < 0 || h < 0 || x + w > t.w || y + h > t.h) {
            throw new IllegalArgumentException("領域がテクスチャの外: " + x + "," + y + " " + w + "x" + h + " in " + t.w + "x" + t.h);
        }
    }

    // ------------------------------------------------------------------ Pipeline

    private static final class ExternalTexture implements GpuTexture {
        final long handle;
        final int w;
        final int h;

        ExternalTexture(long handle, int w, int h) {
            this.handle = handle;
            this.w = w;
            this.h = h;
        }

        @Override public int width() { return w; }
        @Override public int height() { return h; }
        @Override public TextureFormat format() { return TextureFormat.RGBA8; }
        @Override public void close() { /* 呼び出し元所有の外部ハンドルのため破棄しない */ }
    }

    private final class PipelineImpl implements GpuPipeline {
        final PipelineDesc desc;
        final ShaderProgram shader;

        PipelineImpl(PipelineDesc desc, ShaderProgram shader) {
            this.desc = desc;
            this.shader = shader;
        }

        @Override
        public PipelineDesc desc() {
            return desc;
        }

        @Override
        public void close() {
            // パイプライン実体は VkPipelines がデバイス破棄まで保持して共有する
        }
    }

    // ------------------------------------------------------------------ Buffer (3 面リング)

    final class Buffer implements GpuBuffer {
        final long uid = RESOURCE_UID.getAndIncrement();
        final BufferDesc desc;
        final ByteBuffer shadow;
        final MappedBuffer[] segs;
        final int[] dirtyLo;
        final int[] dirtyHi;
        final long[] floatViews;
        final long[] intViews;
        final long[] halfViews;
        final long[] rgbaFloatViews;
        final long[] rgbaIntViews;
        boolean explicitDirty;
        boolean closed;

        Buffer(BufferDesc desc) {
            if (desc.size() > Integer.MAX_VALUE - 64) throw new IllegalArgumentException("size: " + desc.size());
            this.desc = desc;
            int bytes = (int) desc.size();
            ByteBuffer raw = MemoryUtil.memAlloc(bytes + 64);
            int off = (64 - raw.alignmentOffset(0, 64)) & 63;
            this.shadow = raw.slice(off, bytes).order(ByteOrder.nativeOrder());
            MemoryUtil.memSet(this.shadow, 0);
            this.shadowHolder = raw;

            int usageFlags = switch (desc.usage()) {
                case RECORD -> VK10.VK_BUFFER_USAGE_UNIFORM_TEXEL_BUFFER_BIT | VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
                case UNIFORM -> VK10.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT;
                case VERTEX -> VK10.VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;
                case INDEX -> VK10.VK_BUFFER_USAGE_INDEX_BUFFER_BIT;
                case STAGING -> VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
            };

            this.segs = new MappedBuffer[ringSize];
            for (int i = 0; i < ringSize; i++) {
                this.segs[i] = createMappedBuffer(bytes, usageFlags, VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
                MemoryUtil.memSet(this.segs[i].map, 0);
            }
            this.dirtyLo = new int[ringSize];
            this.dirtyHi = new int[ringSize];
            Arrays.fill(this.dirtyLo, Integer.MAX_VALUE);
            this.floatViews = new long[ringSize];
            this.intViews = new long[ringSize];
            this.halfViews = new long[ringSize];
            this.rgbaFloatViews = new long[ringSize];
            this.rgbaIntViews = new long[ringSize];
        }

        private final ByteBuffer shadowHolder;

        @Override public long size() { return desc.size(); }
        @Override public BufferUsage usage() { return desc.usage(); }
        @Override public ByteBuffer map() { return shadow; }

        @Override
        public void markDirty(long offset, long length) {
            if (offset < 0 || length < 0 || offset + length > desc.size()) {
                throw new IllegalArgumentException("dirty range out of bounds: " + offset + " + " + length + " > " + desc.size());
            }
            explicitDirty = true;
            if (length == 0) return;
            int lo = (int) offset;
            int hi = (int) (offset + length);
            for (int s = 0; s < ringSize; s++) {
                if (lo < dirtyLo[s]) dirtyLo[s] = lo;
                if (hi > dirtyHi[s]) dirtyHi[s] = hi;
            }
        }

        void flushSegment(int seg) {
            if (!explicitDirty) {
                segs[seg].map.put(0, shadow, 0, (int) desc.size());
                return;
            }
            int lo = dirtyLo[seg], hi = dirtyHi[seg];
            if (hi <= lo) return;
            segs[seg].map.put(lo, shadow, lo, hi - lo);
            dirtyLo[seg] = Integer.MAX_VALUE;
            dirtyHi[seg] = 0;
        }

        long bufferView(ViewFormat fmt, int seg) {
            long[] cache = switch (fmt) {
                case FLOAT32 -> floatViews;
                case INT32 -> intViews;
                case HALF16 -> halfViews;
                case RGBA_FLOAT32 -> rgbaFloatViews;
                case RGBA_INT32 -> rgbaIntViews;
            };
            if (cache[seg] != VK10.VK_NULL_HANDLE) return cache[seg];
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkBufferViewCreateInfo ci = VkBufferViewCreateInfo.calloc(stack)
                        .sType(VK10.VK_STRUCTURE_TYPE_BUFFER_VIEW_CREATE_INFO)
                        .buffer(segs[seg].buffer)
                        .format(VkPipelines.viewFormat(fmt))
                        .offset(0L)
                        .range(VK10.VK_WHOLE_SIZE);
                LongBuffer pView = stack.mallocLong(1);
                VkContext.vkCheck(VK10.vkCreateBufferView(ctx.device, ci, null, pView), "vkCreateBufferView");
                cache[seg] = pView.get(0);
                return cache[seg];
            }
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            MappedBuffer[] sCopy = segs.clone();
            long[] fv = floatViews.clone(), iv = intViews.clone(), hv = halfViews.clone();
            long[] rfv = rgbaFloatViews.clone(), riv = rgbaIntViews.clone();
            retireOrDestroy(() -> {
                for (int i = 0; i < ringSize; i++) {
                    if (fv[i] != 0L) VK10.vkDestroyBufferView(ctx.device, fv[i], null);
                    if (iv[i] != 0L) VK10.vkDestroyBufferView(ctx.device, iv[i], null);
                    if (hv[i] != 0L) VK10.vkDestroyBufferView(ctx.device, hv[i], null);
                    if (rfv[i] != 0L) VK10.vkDestroyBufferView(ctx.device, rfv[i], null);
                    if (riv[i] != 0L) VK10.vkDestroyBufferView(ctx.device, riv[i], null);
                    if (sCopy[i] != null) sCopy[i].destroy(ctx.device);
                }
            });
            MemoryUtil.memFree(shadowHolder);
        }
    }

    // ------------------------------------------------------------------ Texture

    final class Texture implements GpuTexture {
        final long uid = RESOURCE_UID.getAndIncrement();
        final int w, h;
        final TextureFormat format;
        final int vkFormat;
        final int samples;
        final boolean renderTarget;
        final FilterMode filter;
        final long image;
        final long memory;
        final long imageView;
        int layout = VK10.VK_IMAGE_LAYOUT_UNDEFINED;
        private long fboNoDepth = VK10.VK_NULL_HANDLE;
        private long fboWithDepth = VK10.VK_NULL_HANDLE;
        private long attachedDepthView = VK10.VK_NULL_HANDLE;
        boolean closed;

        Texture(int w, int h, TextureFormat format, int samples, boolean renderTarget, FilterMode filter) {
            this.w = w;
            this.h = h;
            this.format = format;
            this.vkFormat = VkPipelines.vkFormat(format, ctx.depthFormat);
            this.samples = samples;
            this.renderTarget = renderTarget;
            this.filter = filter;

            int usage;
            int aspect;
            if (format == TextureFormat.DEPTH24) {
                usage = VK10.VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT;
                aspect = VK10.VK_IMAGE_ASPECT_DEPTH_BIT;
            } else if (samples > 1) {
                usage = VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
                aspect = VK10.VK_IMAGE_ASPECT_COLOR_BIT;
            } else {
                usage = VK10.VK_IMAGE_USAGE_SAMPLED_BIT | VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
                if (renderTarget) usage |= VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
                aspect = VK10.VK_IMAGE_ASPECT_COLOR_BIT;
            }

            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkImageCreateInfo iCi = VkImageCreateInfo.calloc(stack)
                        .sType(VK10.VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                        .imageType(VK10.VK_IMAGE_TYPE_2D)
                        .format(vkFormat)
                        .samples(VkPipelines.sampleCountFlag(samples))
                        .tiling(VK10.VK_IMAGE_TILING_OPTIMAL)
                        .usage(usage)
                        .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE)
                        .mipLevels(1)
                        .arrayLayers(1)
                        .initialLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED);
                iCi.extent().set(w, h, 1);

                LongBuffer pImg = stack.mallocLong(1);
                VkContext.vkCheck(VK10.vkCreateImage(ctx.device, iCi, null, pImg), "vkCreateImage");
                this.image = pImg.get(0);

                VkMemoryRequirements req = VkMemoryRequirements.calloc(stack);
                VK10.vkGetImageMemoryRequirements(ctx.device, image, req);
                int memType = ctx.findMemoryType(req.memoryTypeBits(), VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, 0);
                VkMemoryAllocateInfo ma = VkMemoryAllocateInfo.calloc(stack)
                        .sType(VK10.VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                        .allocationSize(req.size())
                        .memoryTypeIndex(memType);
                LongBuffer pMem = stack.mallocLong(1);
                VkContext.vkCheck(VK10.vkAllocateMemory(ctx.device, ma, null, pMem), "vkAllocateMemory(image)");
                this.memory = pMem.get(0);
                VkContext.vkCheck(VK10.vkBindImageMemory(ctx.device, image, memory, 0L), "vkBindImageMemory");

                VkImageViewCreateInfo ivCi = VkImageViewCreateInfo.calloc(stack)
                        .sType(VK10.VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                        .image(image)
                        .viewType(VK10.VK_IMAGE_VIEW_TYPE_2D)
                        .format(vkFormat);
                ivCi.subresourceRange().aspectMask(aspect).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
                LongBuffer pIv = stack.mallocLong(1);
                VkContext.vkCheck(VK10.vkCreateImageView(ctx.device, ivCi, null, pIv), "vkCreateImageView");
                this.imageView = pIv.get(0);
            }
        }

        long framebuffer(Texture depth) {
            if (depth == null) {
                if (fboNoDepth == VK10.VK_NULL_HANDLE) {
                    fboNoDepth = createFramebuffer(VK10.VK_NULL_HANDLE, 0);
                }
                return fboNoDepth;
            }
            if (fboWithDepth == VK10.VK_NULL_HANDLE || attachedDepthView != depth.imageView) {
                if (fboWithDepth != VK10.VK_NULL_HANDLE) {
                    long old = fboWithDepth;
                    retireOrDestroy(() -> VK10.vkDestroyFramebuffer(ctx.device, old, null));
                }
                fboWithDepth = createFramebuffer(depth.imageView, depth.vkFormat);
                attachedDepthView = depth.imageView;
            }
            return fboWithDepth;
        }

        private long createFramebuffer(long depthView, int depthFmt) {
            long rp = pipelines.renderPass(vkFormat, depthFmt, samples, LoadOp.LOAD);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                LongBuffer atts = depthView == VK10.VK_NULL_HANDLE
                        ? stack.longs(imageView)
                        : stack.longs(imageView, depthView);
                VkFramebufferCreateInfo fbCi = VkFramebufferCreateInfo.calloc(stack)
                        .sType(VK10.VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO)
                        .renderPass(rp)
                        .pAttachments(atts)
                        .width(w)
                        .height(h)
                        .layers(1);
                LongBuffer pFb = stack.mallocLong(1);
                VkContext.vkCheck(VK10.vkCreateFramebuffer(ctx.device, fbCi, null, pFb), "vkCreateFramebuffer");
                return pFb.get(0);
            }
        }

        void contentOk() {
            if ((format != TextureFormat.RGBA8 && format != TextureFormat.R8) || samples > 1) {
                throw new UnsupportedOperationException("内容を読み書きできない形式: " + format);
            }
        }

        /** コピー・MSAA の解決は形式を問わない画素の移動なので、内容を読み書きできない RGBA16F（Bloom の HDR）も通す。 */
        void copyableOk() {
            if (format != TextureFormat.RGBA8 && format != TextureFormat.R8 && format != TextureFormat.RGBA16F) {
                throw new UnsupportedOperationException("内容を読み書きできない形式: " + format);
            }
        }

        @Override public int width() { return w; }
        @Override public int height() { return h; }
        @Override public TextureFormat format() { return format; }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            long fb0 = fboNoDepth;
            long fb1 = fboWithDepth;
            fboNoDepth = VK10.VK_NULL_HANDLE;
            fboWithDepth = VK10.VK_NULL_HANDLE;
            retireOrDestroy(() -> {
                if (fb0 != VK10.VK_NULL_HANDLE) VK10.vkDestroyFramebuffer(ctx.device, fb0, null);
                if (fb1 != VK10.VK_NULL_HANDLE) VK10.vkDestroyFramebuffer(ctx.device, fb1, null);
                VK10.vkDestroyImageView(ctx.device, imageView, null);
                VK10.vkDestroyImage(ctx.device, image, null);
                VK10.vkFreeMemory(ctx.device, memory, null);
            });
        }
    }

    // ------------------------------------------------------------------ Frame

    private final class FrameImpl implements GpuFrame {
        final int seg;
        VkCommandBuffer cmd;
        boolean passOpen;
        boolean ended;

        FrameImpl(int seg, VkCommandBuffer cmd) {
            this.seg = seg;
            this.cmd = cmd;
        }

        private void live() {
            if (ended) throw new IllegalStateException("提出済みのフレーム");
            if (passOpen) throw new IllegalStateException("パスが開いたまま");
        }

        @Override
        public GpuPass beginPass(GpuTexture target, LoadOp load, int clearArgb, int[] scissor) {
            return beginPass(target, null, load, clearArgb, 1.0f, scissor);
        }

        @Override
        public GpuPass beginPass(GpuTexture colorTarget, GpuTexture depthTarget, LoadOp load,
                                 int clearArgb, float clearDepth, int[] scissor) {
            live();
            flushPendingInits(cmd);
            Texture t = (Texture) colorTarget;
            Texture dt = (Texture) depthTarget;
            if (dt != null && (dt.format != TextureFormat.DEPTH24 || dt.w != t.w || dt.h != t.h || dt.samples != t.samples)) {
                throw new IllegalArgumentException("深度ターゲットが一致しない");
            }
            int[] sc = scissor == null ? new int[]{0, 0, t.w, t.h} : scissor;
            checkRegion(t, sc[0], sc[1], sc[2], sc[3]);
            boolean fullTargetClear = load == LoadOp.CLEAR && sc[0] == 0 && sc[1] == 0 && sc[2] == t.w && sc[3] == t.h;

            transitionLayout(cmd, t.image, VK10.VK_IMAGE_ASPECT_COLOR_BIT,
                    t.layout, VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                    VK10.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK10.VK_PIPELINE_STAGE_TRANSFER_BIT | VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                    VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                    VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_TRANSFER_WRITE_BIT | VK10.VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
                    VK10.VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK10.VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);
            t.layout = VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;

            if (dt != null && dt.layout != VK10.VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL) {
                transitionLayout(cmd, dt.image, depthAspectMask(dt.vkFormat),
                        dt.layout, VK10.VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,
                        VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                        VK10.VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK10.VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT,
                        0, VK10.VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK10.VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);
                dt.layout = VK10.VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL;
            }

            LoadOp rpLoad = fullTargetClear ? LoadOp.CLEAR : LoadOp.LOAD;
            int depthFmt = dt == null ? 0 : dt.vkFormat;
            long rp = pipelines.renderPass(t.vkFormat, depthFmt, t.samples, rpLoad);
            long fb = t.framebuffer(dt);

            float cr = ((clearArgb >> 16) & 0xFF) / 255f;
            float cg = ((clearArgb >> 8) & 0xFF) / 255f;
            float cb = (clearArgb & 0xFF) / 255f;
            float ca = ((clearArgb >>> 24) & 0xFF) / 255f;

            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkRenderPassBeginInfo rpBi = VkRenderPassBeginInfo.calloc(stack)
                        .sType(VK10.VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO)
                        .renderPass(rp)
                        .framebuffer(fb);
                rpBi.renderArea().offset().set(0, 0);
                rpBi.renderArea().extent().set(t.w, t.h);

                if (fullTargetClear) {
                    org.lwjgl.vulkan.VkClearValue.Buffer clears = org.lwjgl.vulkan.VkClearValue.calloc(dt != null ? 2 : 1, stack);
                    clears.get(0).color().float32(0, cr).float32(1, cg).float32(2, cb).float32(3, ca);
                    if (dt != null) clears.get(1).depthStencil().set(clearDepth, 0);
                    rpBi.pClearValues(clears);
                }
                VK10.vkCmdBeginRenderPass(cmd, rpBi, VK10.VK_SUBPASS_CONTENTS_INLINE);

                if (load == LoadOp.CLEAR && !fullTargetClear && sc[2] > 0 && sc[3] > 0) {
                    int nClears = dt != null ? 2 : 1;
                    VkClearAttachment.Buffer atts = VkClearAttachment.calloc(nClears, stack);
                    atts.get(0).aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).colorAttachment(0);
                    atts.get(0).clearValue().color().float32(0, cr).float32(1, cg).float32(2, cb).float32(3, ca);
                    if (dt != null) {
                        atts.get(1).aspectMask(VK10.VK_IMAGE_ASPECT_DEPTH_BIT);
                        atts.get(1).clearValue().depthStencil().set(clearDepth, 0);
                    }
                    VkClearRect.Buffer cRect = VkClearRect.calloc(1, stack);
                    cRect.get(0).rect().offset().set(sc[0], sc[1]);
                    cRect.get(0).rect().extent().set(sc[2], sc[3]);
                    cRect.get(0).baseArrayLayer(0).layerCount(1);
                    VK10.vkCmdClearAttachments(cmd, atts, cRect);
                }

                VkViewport.Buffer vp = VkViewport.calloc(1, stack);
                vp.get(0).set(0f, 0f, t.w, t.h, 0f, 1f);
                VK10.vkCmdSetViewport(cmd, 0, vp);

                VkRect2D.Buffer scRect = VkRect2D.calloc(1, stack);
                scRect.get(0).offset().set(sc[0], sc[1]);
                scRect.get(0).extent().set(sc[2], sc[3]);
                VK10.vkCmdSetScissor(cmd, 0, scRect);
            }

            passOpen = true;
            return new PassImpl(this, t, dt);
        }

        @Override
        public void upload(GpuTexture dst, int x, int y, int w, int h, ByteBuffer pixels) {
            live();
            flushPendingInits(cmd);
            Texture t = (Texture) dst;
            t.contentOk();
            checkRegion(t, x, y, w, h);
            int bytes = w * h * t.format.bytesPerPixel;
            if (pixels.remaining() < bytes) {
                throw new IllegalArgumentException("画素が足りない: " + pixels.remaining() + " < " + bytes);
            }
            if (w == 0 || h == 0) return;

            int srcOff = allocUploadSlice(seg, bytes);
            ByteBuffer srcDup = pixels.duplicate();
            srcDup.limit(srcDup.position() + bytes);
            uploadArenas[seg].map.put(srcOff, srcDup, srcDup.position(), bytes);

            transitionLayout(cmd, t.image, VK10.VK_IMAGE_ASPECT_COLOR_BIT,
                    t.layout, VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    VK10.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK10.VK_PIPELINE_STAGE_TRANSFER_BIT | VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                    VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK10.VK_ACCESS_TRANSFER_WRITE_BIT);

            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
                region.get(0)
                        .bufferOffset(srcOff)
                        .bufferRowLength(0)
                        .bufferImageHeight(0);
                region.get(0).imageSubresource()
                        .aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
                region.get(0).imageOffset().set(x, y, 0);
                region.get(0).imageExtent().set(w, h, 1);
                VK10.vkCmdCopyBufferToImage(cmd, uploadArenas[seg].buffer, t.image,
                        VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
            }

            transitionLayout(cmd, t.image, VK10.VK_IMAGE_ASPECT_COLOR_BIT,
                    VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK10.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK10.VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_TRANSFER_READ_BIT);
            t.layout = VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        }

        @Override
        public void copy(GpuTexture src, GpuTexture dst, int[] region) {
            live();
            flushPendingInits(cmd);
            Texture s = (Texture) src, d = (Texture) dst;
            s.copyableOk();
            if (s.format != d.format) throw new IllegalArgumentException("形式が違う: " + s.format + " / " + d.format);
            checkRegion(s, region[0], region[1], region[2], region[3]);
            checkRegion(d, region[0], region[1], region[2], region[3]);
            if (region[2] == 0 || region[3] == 0) return;

            transitionLayout(cmd, s.image, VK10.VK_IMAGE_ASPECT_COLOR_BIT,
                    s.layout, VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK10.VK_ACCESS_MEMORY_WRITE_BIT | VK10.VK_ACCESS_SHADER_READ_BIT, VK10.VK_ACCESS_TRANSFER_READ_BIT);
            transitionLayout(cmd, d.image, VK10.VK_IMAGE_ASPECT_COLOR_BIT,
                    d.layout, VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_TRANSFER_WRITE_BIT, VK10.VK_ACCESS_TRANSFER_WRITE_BIT);

            try (MemoryStack stack = MemoryStack.stackPush()) {
                if (s.samples > 1 && d.samples == 1) {
                    VkImageResolve.Buffer res = VkImageResolve.calloc(1, stack);
                    res.get(0).srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
                    res.get(0).srcOffset().set(region[0], region[1], 0);
                    res.get(0).dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
                    res.get(0).dstOffset().set(region[0], region[1], 0);
                    res.get(0).extent().set(region[2], region[3], 1);
                    VK10.vkCmdResolveImage(cmd, s.image, VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                            d.image, VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, res);
                } else {
                    VkImageCopy.Buffer cp = VkImageCopy.calloc(1, stack);
                    cp.get(0).srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
                    cp.get(0).srcOffset().set(region[0], region[1], 0);
                    cp.get(0).dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
                    cp.get(0).dstOffset().set(region[0], region[1], 0);
                    cp.get(0).extent().set(region[2], region[3], 1);
                    VK10.vkCmdCopyImage(cmd, s.image, VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                            d.image, VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, cp);
                }
            }

            if (s.samples > 1) {
                transitionLayout(cmd, s.image, VK10.VK_IMAGE_ASPECT_COLOR_BIT,
                        VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                        VK10.VK_PIPELINE_STAGE_TRANSFER_BIT, VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                        VK10.VK_ACCESS_TRANSFER_READ_BIT, VK10.VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);
                s.layout = VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
            } else {
                transitionLayout(cmd, s.image, VK10.VK_IMAGE_ASPECT_COLOR_BIT,
                        VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                        VK10.VK_PIPELINE_STAGE_TRANSFER_BIT, VK10.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                        VK10.VK_ACCESS_TRANSFER_READ_BIT, VK10.VK_ACCESS_SHADER_READ_BIT);
                s.layout = VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
            }
            transitionLayout(cmd, d.image, VK10.VK_IMAGE_ASPECT_COLOR_BIT,
                    VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    VK10.VK_PIPELINE_STAGE_TRANSFER_BIT, VK10.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK10.VK_ACCESS_TRANSFER_WRITE_BIT, VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_TRANSFER_READ_BIT);
            d.layout = VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        }

        @Override
        public void readback(GpuTexture src, int[] region, ByteBuffer dst) {
            live();
            flushPendingInits(cmd);
            Texture s = (Texture) src;
            s.contentOk();
            checkRegion(s, region[0], region[1], region[2], region[3]);
            int bytes = region[2] * region[3] * s.format.bytesPerPixel;
            if (dst.remaining() < bytes) {
                throw new IllegalArgumentException("読み出し先の容量不足: " + dst.remaining() + " < " + bytes);
            }
            if (bytes == 0) return;

            MappedBuffer rb = ensureReadbackCapacity(bytes);
            transitionLayout(cmd, s.image, VK10.VK_IMAGE_ASPECT_COLOR_BIT,
                    s.layout, VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK10.VK_ACCESS_MEMORY_WRITE_BIT | VK10.VK_ACCESS_SHADER_READ_BIT, VK10.VK_ACCESS_TRANSFER_READ_BIT);

            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkBufferImageCopy.Buffer cp = VkBufferImageCopy.calloc(1, stack);
                cp.get(0).bufferOffset(0L).bufferRowLength(0).bufferImageHeight(0);
                cp.get(0).imageSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
                cp.get(0).imageOffset().set(region[0], region[1], 0);
                cp.get(0).imageExtent().set(region[2], region[3], 1);
                VK10.vkCmdCopyImageToBuffer(cmd, s.image, VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, rb.buffer, cp);
            }

            transitionLayout(cmd, s.image, VK10.VK_IMAGE_ASPECT_COLOR_BIT,
                    VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    VK10.VK_PIPELINE_STAGE_TRANSFER_BIT, VK10.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                    VK10.VK_ACCESS_TRANSFER_READ_BIT, VK10.VK_ACCESS_SHADER_READ_BIT);
            s.layout = VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;

            // ここまでのコマンドを提出して完了を待ち、同フレームで後続命令を続けられるよう再開する
            submitAndWaitFence(seg, cmd);
            int dstPos = dst.position();
            dst.put(dstPos, rb.map, 0, bytes);
            dst.position(dstPos + bytes);

            VK10.vkResetCommandPool(ctx.device, commandPools[seg], 0);
            uboCursors[seg] = 0;
            uploadCursors[seg] = 0;
            beginCommandBuffer(cmd);
        }

        private void submitAndWaitFence(int s, VkCommandBuffer c) {
            VkContext.vkCheck(VK10.vkEndCommandBuffer(c), "vkEndCommandBuffer");
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkSubmitInfo si = VkSubmitInfo.calloc(stack)
                        .sType(VK10.VK_STRUCTURE_TYPE_SUBMIT_INFO)
                        .pCommandBuffers(stack.pointers(c));
                VK10.vkResetFences(ctx.device, fences[s]);
                VkContext.vkCheck(VK10.vkQueueSubmit(ctx.graphicsQueue, si, fences[s]), "vkQueueSubmit");
            }
            submitSeq++;
            VkContext.vkCheck(VK10.vkWaitForFences(ctx.device, fences[s], true, FENCE_TIMEOUT_NS), "vkWaitForFences");
            completedSeq = Math.max(completedSeq, submitSeq);
            drainRetired();
        }

        @Override
        public void end() {
            live();
            if (timestampWritten[seg] && timestampPool != VK10.VK_NULL_HANDLE) {
                VK10.vkCmdWriteTimestamp(cmd, VK10.VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, timestampPool, seg * 2 + 1);
            }
            VkContext.vkCheck(VK10.vkEndCommandBuffer(cmd), "vkEndCommandBuffer");
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkSubmitInfo si = VkSubmitInfo.calloc(stack)
                        .sType(VK10.VK_STRUCTURE_TYPE_SUBMIT_INFO)
                        .pCommandBuffers(stack.pointers(cmd));
                VK10.vkResetFences(ctx.device, fences[seg]);
                VkContext.vkCheck(VK10.vkQueueSubmit(ctx.graphicsQueue, si, fences[seg]), "vkQueueSubmit");
            }
            submitSeq++;
            fenceSubmitSeq[seg] = submitSeq;
            fenceSubmitted[seg] = true;
            currentSeg = (seg + 1) % ringSize;
            ended = true;
            inFrame = false;
        }
    }

    // ------------------------------------------------------------------ Pass

    private final class PassImpl implements GpuPass {
        final FrameImpl frame;
        final Texture colorTarget;
        final Texture depthTarget;
        final Texture[] boundTexturesScratch = new Texture[4];
        final long[] boundSamplersScratch = new long[4];
        final Buffer[] boundBuffersScratch = new Buffer[8];
        PipelineImpl boundPipeline;
        boolean bound;
        boolean done;

        PassImpl(FrameImpl frame, Texture colorTarget, Texture depthTarget) {
            this.frame = frame;
            this.colorTarget = colorTarget;
            this.depthTarget = depthTarget;
        }

        @Override
        public void bind(GpuPipeline pipeline, GpuBindings bindings) {
            if (done) throw new IllegalStateException("終えたパス");
            PipelineImpl pl = (PipelineImpl) pipeline;
            ShaderProgram sh = pl.shader;
            if (bindings.textures().length < sh.samplers().size()) {
                throw new IllegalArgumentException("テクスチャが足りない: " + sh.id());
            }
            if (bindings.records().length < sh.recordBuffers()) {
                throw new IllegalArgumentException("記録列のバッファが足りない: " + sh.id());
            }
            int activeUbo = sh.uniformBytes();
            if (activeUbo > 0 && (bindings.uniforms() == null || bindings.uniforms().remaining() < activeUbo)) {
                throw new IllegalArgumentException("定数が足りない: " + sh.id() + " は " + activeUbo + " バイト");
            }

            int seg = frame.seg;
            for (int i = 0; i < bindings.records().length; i++) {
                if (bindings.records()[i] instanceof Buffer b) {
                    b.flushSegment(seg);
                }
            }

            int blockBytes = sh.uniformBlockBytes();
            int uboOffset = 0;
            MappedBuffer uboArena = uboArenas[seg];
            if (blockBytes > 0) {
                uboArena = ensureUboCapacity(seg, blockBytes);
                int alignedCur = (int) ((uboCursors[seg] + minUboAlign - 1) & ~(minUboAlign - 1));
                uboOffset = alignedCur;
                ByteBuffer src = bindings.uniforms().duplicate();
                src.limit(src.position() + activeUbo);
                uboArena.map.put(uboOffset, src, src.position(), activeUbo);
                for (int p = activeUbo; p < blockBytes; p++) {
                    uboArena.map.put(uboOffset + p, (byte) 0);
                }
                uboCursors[seg] = (int) ((uboOffset + blockBytes + minUboAlign - 1) & ~(minUboAlign - 1));
            }

            int nt = sh.samplers().size();
            for (int i = 0; i < nt; i++) {
                Texture tx = (Texture) bindings.textures()[i];
                boundTexturesScratch[i] = tx;
                boundSamplersScratch[i] = tx.filter == FilterMode.LINEAR ? samplerLinear : samplerNearest;
            }
            int nb = sh.recordBuffers();
            for (int i = 0; i < nb; i++) {
                boundBuffersScratch[i] = (Buffer) bindings.records()[i];
            }

            long vkPl = pipelines.graphicsPipeline(pl.desc, sh, colorTarget.vkFormat,
                    depthTarget == null ? 0 : depthTarget.vkFormat, colorTarget.samples);
            long plLayout = pipelines.pipelineLayout(sh);
            long descSet = pipelines.descriptorSet(sh, seg, uboArena.buffer, blockBytes,
                    boundTexturesScratch, boundSamplersScratch, boundBuffersScratch, VkDevice.this::waitAllFrames);

            VK10.vkCmdBindPipeline(frame.cmd, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, vkPl);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                LongBuffer pSet = stack.longs(descSet);
                if (blockBytes > 0) {
                    VK10.vkCmdBindDescriptorSets(frame.cmd, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS,
                            plLayout, 0, pSet, stack.ints(uboOffset));
                } else {
                    VK10.vkCmdBindDescriptorSets(frame.cmd, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS,
                            plLayout, 0, pSet, null);
                }
            }
            this.boundPipeline = pl;
            this.bound = true;
        }

        @Override
        public void bindMesh(GpuBuffer vertices, GpuBuffer indices) {
            if (done) throw new IllegalStateException("終えたパス");
            if (!bound) throw new IllegalStateException("パイプラインを結んでいない");
            if (vertices.usage() != BufferUsage.VERTEX || indices.usage() != BufferUsage.INDEX) {
                throw new IllegalArgumentException("頂点・インデックスバッファの種別が違う");
            }
            Buffer vb = (Buffer) vertices;
            Buffer ib = (Buffer) indices;
            int seg = frame.seg;
            vb.flushSegment(seg);
            ib.flushSegment(seg);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VK10.vkCmdBindVertexBuffers(frame.cmd, 0, stack.longs(vb.segs[seg].buffer), stack.longs(0L));
                VK10.vkCmdBindIndexBuffer(frame.cmd, ib.segs[seg].buffer, 0L, VK10.VK_INDEX_TYPE_UINT32);
            }
        }

        @Override
        public void drawInstanced(int vertexCount, int instanceCount, int baseInstance) {
            if (done) throw new IllegalStateException("終えたパス");
            if (!bound) throw new IllegalStateException("パイプラインを結んでいない");
            if (vertexCount <= 0 || instanceCount <= 0) return;
            VK10.vkCmdDraw(frame.cmd, vertexCount, instanceCount, 0, baseInstance);
        }

        @Override
        public void drawIndexedInstanced(int indexCount, long indexOffsetBytes, int instanceCount, int baseVertex, int baseInstance) {
            if (done) throw new IllegalStateException("終えたパス");
            if (!bound) throw new IllegalStateException("パイプラインを結んでいない");
            if (indexCount <= 0 || instanceCount <= 0) return;
            VK10.vkCmdDrawIndexed(frame.cmd, indexCount, instanceCount, (int) (indexOffsetBytes >>> 2), baseVertex, baseInstance);
        }

        @Override
        public void end() {
            if (done) return;
            VK10.vkCmdEndRenderPass(frame.cmd);
            transitionLayout(frame.cmd, colorTarget.image, VK10.VK_IMAGE_ASPECT_COLOR_BIT,
                    VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                    VK10.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK10.VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
                    VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_TRANSFER_READ_BIT);
            colorTarget.layout = VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
            done = true;
            frame.passOpen = false;
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        waitAllFrames();

        for (Texture t : new ArrayList<>(allTextures)) t.close();
        allTextures.clear();
        for (Buffer b : new ArrayList<>(allBuffers)) b.close();
        allBuffers.clear();
        drainRetired();

        for (int i = 0; i < ringSize; i++) {
            uboArenas[i].destroy(ctx.device);
            uploadArenas[i].destroy(ctx.device);
            VK10.vkDestroyFence(ctx.device, fences[i], null);
            VK10.vkDestroyCommandPool(ctx.device, commandPools[i], null);
        }
        if (readbackStaging != null) {
            readbackStaging.destroy(ctx.device);
            readbackStaging = null;
        }
        if (timestampPool != VK10.VK_NULL_HANDLE) {
            VK10.vkDestroyQueryPool(ctx.device, timestampPool, null);
        }
        pipelines.close();
        if (ownsContext) {
            ctx.close();
        }
    }
}
