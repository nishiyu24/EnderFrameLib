package com.nishiyu.enderframelib.core.paint.vulkan;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.CullMode;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.LoadOp;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.PipelineDesc;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.TextureFormat;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.VertexAttr;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.VertexLayout;
import com.nishiyu.enderframelib.core.kernel.gpu.shader.RecordView;
import com.nishiyu.enderframelib.core.kernel.gpu.shader.ViewFormat;

import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuDevice;
import com.nishiyu.enderframelib.core.kernel.gpu.shader.ShaderProgram;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkAttachmentDescription;
import org.lwjgl.vulkan.VkAttachmentReference;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.lwjgl.vulkan.VkPipelineColorBlendAttachmentState;
import org.lwjgl.vulkan.VkPipelineColorBlendStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDepthStencilStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDynamicStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineInputAssemblyStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineMultisampleStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRasterizationStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPipelineVertexInputStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineViewportStateCreateInfo;
import org.lwjgl.vulkan.VkRenderPassCreateInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkSubpassDependency;
import org.lwjgl.vulkan.VkSubpassDescription;
import org.lwjgl.vulkan.VkVertexInputAttributeDescription;
import org.lwjgl.vulkan.VkVertexInputBindingDescription;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

/**
 * {@link VkDevice} 向けの SPIR-V モジュール・ディスクリプタセットレイアウト/プール・レンダーパス・グラフィックスパイプライン管理。
 */
final class VkPipelines implements AutoCloseable {
    private static final String SPIRV_DIR = "com/nishiyu/enderframelib/core/kernel/gpu/shader/spirv/";
    private static final int MAX_CACHED_SETS = 4096;

    record RenderPassKey(int colorFormat, int depthFormat, int samples, LoadOp loadOp) {}
    record PipelineKey(PipelineDesc desc, int colorFormat, int depthFormat, int samples) {}

    private static final class ShaderLayout {
        final long setLayout;
        final long pipelineLayout;
        final long vertModule;
        final long fragModule;

        ShaderLayout(long setLayout, long pipelineLayout, long vertModule, long fragModule) {
            this.setLayout = setLayout;
            this.pipelineLayout = pipelineLayout;
            this.vertModule = vertModule;
            this.fragModule = fragModule;
        }
    }

    private static final class DescKey {
        String shaderId;
        int seg;
        int nTex;
        int nBuf;
        final long[] texIds = new long[4];
        final long[] bufIds = new long[8];
        int hash;

        void set(String shaderId, int seg, long[] tIds, int nt, long[] bIds, int nb) {
            this.shaderId = shaderId;
            this.seg = seg;
            this.nTex = nt;
            this.nBuf = nb;
            System.arraycopy(tIds, 0, texIds, 0, nt);
            System.arraycopy(bIds, 0, bufIds, 0, nb);
            int h = shaderId.hashCode() * 31 + seg;
            for (int i = 0; i < nt; i++) h = h * 31 + Long.hashCode(texIds[i]);
            for (int i = 0; i < nb; i++) h = h * 31 + Long.hashCode(bufIds[i]);
            this.hash = h;
        }

        DescKey copy() {
            DescKey c = new DescKey();
            c.set(shaderId, seg, texIds, nTex, bufIds, nBuf);
            return c;
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof DescKey o)) return false;
            if (hash != o.hash || seg != o.seg || nTex != o.nTex || nBuf != o.nBuf) return false;
            if (!shaderId.equals(o.shaderId)) return false;
            for (int i = 0; i < nTex; i++) if (texIds[i] != o.texIds[i]) return false;
            for (int i = 0; i < nBuf; i++) if (bufIds[i] != o.bufIds[i]) return false;
            return true;
        }
    }

    private final VkContext ctx;
    private final Map<String, ShaderLayout> shaderLayouts = new HashMap<>();
    private final Map<RenderPassKey, Long> renderPasses = new HashMap<>();
    private final Map<PipelineKey, Long> pipelines = new HashMap<>();
    private final List<Long> descriptorPools = new ArrayList<>();
    private final Map<DescKey, Long> descriptorSets = new HashMap<>();
    private final DescKey probeKey = new DescKey();
    private final long[] scratchTexIds = new long[4];
    private final long[] scratchBufIds = new long[8];
    private final List<Long> samplers = new ArrayList<>();

    private long activePool = VK10.VK_NULL_HANDLE;
    private boolean closed;

    VkPipelines(VkContext ctx) {
        this.ctx = ctx;
    }

    void trackSampler(long sampler) {
        samplers.add(sampler);
    }

    long pipelineLayout(ShaderProgram shader) {
        return getOrCreateShaderLayout(shader).pipelineLayout;
    }

    long renderPass(int colorFormat, int depthFormat, int samples, LoadOp loadOp) {
        RenderPassKey key = new RenderPassKey(colorFormat, depthFormat, samples, loadOp);
        Long existing = renderPasses.get(key);
        if (existing != null) return existing;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            boolean hasDepth = depthFormat != 0;
            int attCount = hasDepth ? 2 : 1;
            int vkSamples = sampleCountFlag(samples);
            int vkLoadOp = loadOp == LoadOp.CLEAR ? VK10.VK_ATTACHMENT_LOAD_OP_CLEAR : VK10.VK_ATTACHMENT_LOAD_OP_LOAD;

            VkAttachmentDescription.Buffer atts = VkAttachmentDescription.calloc(attCount, stack);
            atts.get(0)
                    .format(colorFormat)
                    .samples(vkSamples)
                    .loadOp(vkLoadOp)
                    .storeOp(VK10.VK_ATTACHMENT_STORE_OP_STORE)
                    .stencilLoadOp(VK10.VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                    .stencilStoreOp(VK10.VK_ATTACHMENT_STORE_OP_DONT_CARE)
                    .initialLayout(VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                    .finalLayout(VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);

            VkAttachmentReference.Buffer colorRef = VkAttachmentReference.calloc(1, stack)
                    .attachment(0)
                    .layout(VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);

            VkAttachmentReference depthRef = null;
            if (hasDepth) {
                atts.get(1)
                        .format(depthFormat)
                        .samples(vkSamples)
                        .loadOp(vkLoadOp)
                        .storeOp(VK10.VK_ATTACHMENT_STORE_OP_STORE)
                        .stencilLoadOp(VK10.VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                        .stencilStoreOp(VK10.VK_ATTACHMENT_STORE_OP_DONT_CARE)
                        .initialLayout(VK10.VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
                        .finalLayout(VK10.VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
                depthRef = VkAttachmentReference.calloc(stack)
                        .attachment(1)
                        .layout(VK10.VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
            }

            VkSubpassDescription.Buffer subpass = VkSubpassDescription.calloc(1, stack)
                    .pipelineBindPoint(VK10.VK_PIPELINE_BIND_POINT_GRAPHICS)
                    .colorAttachmentCount(1)
                    .pColorAttachments(colorRef)
                    .pDepthStencilAttachment(depthRef);

            VkSubpassDependency.Buffer deps = VkSubpassDependency.calloc(1, stack);
            deps.get(0)
                    .srcSubpass(VK10.VK_SUBPASS_EXTERNAL)
                    .dstSubpass(0)
                    .srcStageMask(VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT
                            | (hasDepth ? VK10.VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK10.VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT : 0))
                    .dstStageMask(VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT
                            | (hasDepth ? VK10.VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK10.VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT : 0))
                    .srcAccessMask(0)
                    .dstAccessMask(VK10.VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK10.VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT
                            | (hasDepth ? VK10.VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK10.VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT : 0));

            VkRenderPassCreateInfo rpCi = VkRenderPassCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO)
                    .pAttachments(atts)
                    .pSubpasses(subpass)
                    .pDependencies(deps);

            LongBuffer pRp = stack.mallocLong(1);
            VkContext.vkCheck(VK10.vkCreateRenderPass(ctx.device, rpCi, null, pRp), "vkCreateRenderPass");
            long rp = pRp.get(0);
            renderPasses.put(key, rp);
            return rp;
        }
    }

    long graphicsPipeline(PipelineDesc desc, ShaderProgram shader,
                          int colorFormat, int depthFormat, int samples) {
        PipelineKey key = new PipelineKey(desc, colorFormat, depthFormat, samples);
        Long existing = pipelines.get(key);
        if (existing != null) return existing;

        ShaderLayout sl = getOrCreateShaderLayout(shader);
        long compatRp = renderPass(colorFormat, depthFormat, samples, LoadOp.LOAD);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
            stages.get(0)
                    .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK10.VK_SHADER_STAGE_VERTEX_BIT)
                    .module(sl.vertModule)
                    .pName(stack.UTF8("main"));
            stages.get(1)
                    .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK10.VK_SHADER_STAGE_FRAGMENT_BIT)
                    .module(sl.fragModule)
                    .pName(stack.UTF8("main"));

            VertexLayout vl = desc.vertexLayout();
            VertexAttr[] attrs = vl.attrs();
            VkPipelineVertexInputStateCreateInfo vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO);
            if (attrs.length > 0) {
                VkVertexInputBindingDescription.Buffer bindings = VkVertexInputBindingDescription.calloc(1, stack);
                bindings.get(0)
                        .binding(0)
                        .stride(vl.strideBytes())
                        .inputRate(VK10.VK_VERTEX_INPUT_RATE_VERTEX);
                VkVertexInputAttributeDescription.Buffer attrDescs = VkVertexInputAttributeDescription.calloc(attrs.length, stack);
                for (int i = 0; i < attrs.length; i++) {
                    VertexAttr a = attrs[i];
                    attrDescs.get(i)
                            .binding(0)
                            .location(a.location())
                            .format(vertexAttrFormat(a.components()))
                            .offset(a.offsetBytes());
                }
                vertexInput.pVertexBindingDescriptions(bindings).pVertexAttributeDescriptions(attrDescs);
            }

            int topology = attrs.length == 0
                    ? VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP
                    : VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
            VkPipelineInputAssemblyStateCreateInfo inputAsm = VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO)
                    .topology(topology)
                    .primitiveRestartEnable(false);

            VkPipelineViewportStateCreateInfo viewportState = VkPipelineViewportStateCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO)
                    .viewportCount(1)
                    .scissorCount(1);

            VkPipelineRasterizationStateCreateInfo raster = VkPipelineRasterizationStateCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO)
                    .depthClampEnable(false)
                    .rasterizerDiscardEnable(false)
                    .polygonMode(VK10.VK_POLYGON_MODE_FILL)
                    .lineWidth(1.0f)
                    .cullMode(desc.cullMode() == CullMode.BACK ? VK10.VK_CULL_MODE_BACK_BIT : VK10.VK_CULL_MODE_NONE)
                    .frontFace(VK10.VK_FRONT_FACE_COUNTER_CLOCKWISE)
                    .depthBiasEnable(false);

            VkPipelineMultisampleStateCreateInfo msaa = VkPipelineMultisampleStateCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO)
                    .sampleShadingEnable(false)
                    .rasterizationSamples(sampleCountFlag(samples));

            VkPipelineDepthStencilStateCreateInfo depth = VkPipelineDepthStencilStateCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO)
                    .depthTestEnable(desc.depthTest() && depthFormat != 0)
                    .depthWriteEnable(desc.depthWrite() && depthFormat != 0)
                    .depthCompareOp(VK10.VK_COMPARE_OP_LESS)
                    .depthBoundsTestEnable(false)
                    .stencilTestEnable(false);

            VkPipelineColorBlendAttachmentState.Buffer blendAtt = VkPipelineColorBlendAttachmentState.calloc(1, stack);
            blendAtt.get(0)
                    .colorWriteMask(VK10.VK_COLOR_COMPONENT_R_BIT | VK10.VK_COLOR_COMPONENT_G_BIT
                            | VK10.VK_COLOR_COMPONENT_B_BIT | VK10.VK_COLOR_COMPONENT_A_BIT)
                    .blendEnable(desc.premultipliedBlend())
                    .srcColorBlendFactor(VK10.VK_BLEND_FACTOR_ONE)
                    .dstColorBlendFactor(VK10.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                    .colorBlendOp(VK10.VK_BLEND_OP_ADD)
                    .srcAlphaBlendFactor(VK10.VK_BLEND_FACTOR_ONE)
                    .dstAlphaBlendFactor(VK10.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                    .alphaBlendOp(VK10.VK_BLEND_OP_ADD);

            VkPipelineColorBlendStateCreateInfo colorBlend = VkPipelineColorBlendStateCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO)
                    .logicOpEnable(false)
                    .pAttachments(blendAtt);

            IntBuffer dynStates = stack.ints(VK10.VK_DYNAMIC_STATE_VIEWPORT, VK10.VK_DYNAMIC_STATE_SCISSOR);
            VkPipelineDynamicStateCreateInfo dynamicState = VkPipelineDynamicStateCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO)
                    .pDynamicStates(dynStates);

            VkGraphicsPipelineCreateInfo.Buffer gpCi = VkGraphicsPipelineCreateInfo.calloc(1, stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO)
                    .pStages(stages)
                    .pVertexInputState(vertexInput)
                    .pInputAssemblyState(inputAsm)
                    .pViewportState(viewportState)
                    .pRasterizationState(raster)
                    .pMultisampleState(msaa)
                    .pDepthStencilState(depth)
                    .pColorBlendState(colorBlend)
                    .pDynamicState(dynamicState)
                    .layout(sl.pipelineLayout)
                    .renderPass(compatRp)
                    .subpass(0);

            LongBuffer pPl = stack.mallocLong(1);
            VkContext.vkCheck(VK10.vkCreateGraphicsPipelines(ctx.device, VK10.VK_NULL_HANDLE, gpCi, null, pPl),
                    "vkCreateGraphicsPipelines");
            long pl = pPl.get(0);
            pipelines.put(key, pl);
            return pl;
        }
    }

    long descriptorSet(ShaderProgram shader, int seg, long uboBuffer, int uboBlockBytes,
                       VkDevice.Texture[] textures, long[] samps, VkDevice.Buffer[] records, Runnable flushIdleOnReset) {
        int nt = shader.samplers().size();
        int nb = shader.recordBuffers();
        for (int i = 0; i < nt; i++) scratchTexIds[i] = textures[i].uid;
        for (int i = 0; i < nb; i++) scratchBufIds[i] = records[i].uid;
        probeKey.set(shader.id(), seg, scratchTexIds, nt, scratchBufIds, nb);
        Long cached = descriptorSets.get(probeKey);
        if (cached != null) return cached;

        if (descriptorSets.size() >= MAX_CACHED_SETS) {
            flushIdleOnReset.run();
            for (long p : descriptorPools) VK10.vkResetDescriptorPool(ctx.device, p, 0);
            descriptorSets.clear();
            activePool = descriptorPools.isEmpty() ? VK10.VK_NULL_HANDLE : descriptorPools.get(0);
        }

        ShaderLayout sl = getOrCreateShaderLayout(shader);
        long set = allocateSet(sl.setLayout);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            boolean hasUbo = uboBlockBytes > 0;
            int writeCount = (hasUbo ? 1 : 0) + nt + shader.records().size();
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(writeCount, stack);
            int wi = 0;
            if (hasUbo) {
                VkDescriptorBufferInfo.Buffer bi = VkDescriptorBufferInfo.calloc(1, stack);
                bi.get(0).buffer(uboBuffer).offset(0).range(uboBlockBytes);
                writes.get(wi++)
                        .sType(VK10.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                        .dstSet(set)
                        .dstBinding(0)
                        .descriptorCount(1)
                        .descriptorType(VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC)
                        .pBufferInfo(bi);
            }
            for (int i = 0; i < nt; i++) {
                VkDescriptorImageInfo.Buffer ii = VkDescriptorImageInfo.calloc(1, stack);
                ii.get(0)
                        .imageLayout(VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                        .imageView(textures[i].imageView)
                        .sampler(samps[i]);
                writes.get(wi++)
                        .sType(VK10.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                        .dstSet(set)
                        .dstBinding(shader.firstTextureUnit() + i)
                        .descriptorCount(1)
                        .descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                        .pImageInfo(ii);
            }
            for (RecordView rv : shader.records()) {
                VkDevice.Buffer b = records[rv.bufferIndex()];
                long view = b.bufferView(rv.format(), seg);
                LongBuffer pView = stack.longs(view);
                writes.get(wi++)
                        .sType(VK10.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                        .dstSet(set)
                        .dstBinding(rv.unit())
                        .descriptorCount(1)
                        .descriptorType(VK10.VK_DESCRIPTOR_TYPE_UNIFORM_TEXEL_BUFFER)
                        .pTexelBufferView(pView);
            }
            VK10.vkUpdateDescriptorSets(ctx.device, writes, null);
        }

        descriptorSets.put(probeKey.copy(), set);
        return set;
    }

    void invalidateCache() {
        descriptorSets.clear();
        for (long p : descriptorPools) VK10.vkResetDescriptorPool(ctx.device, p, 0);
    }

    private long allocateSet(long setLayout) {
        if (activePool == VK10.VK_NULL_HANDLE) {
            activePool = createPool();
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer pSet = stack.mallocLong(1);
            VkDescriptorSetAllocateInfo ai = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                    .descriptorPool(activePool)
                    .pSetLayouts(stack.longs(setLayout));
            int err = VK10.vkAllocateDescriptorSets(ctx.device, ai, pSet);
            if (err == VK10.VK_ERROR_FRAGMENTED_POOL || err == org.lwjgl.vulkan.VK11.VK_ERROR_OUT_OF_POOL_MEMORY) {
                activePool = createPool();
                ai.descriptorPool(activePool);
                err = VK10.vkAllocateDescriptorSets(ctx.device, ai, pSet);
            }
            VkContext.vkCheck(err, "vkAllocateDescriptorSets");
            return pSet.get(0);
        }
    }

    private long createPool() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(3, stack);
            sizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC).descriptorCount(512);
            sizes.get(1).type(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1536);
            sizes.get(2).type(VK10.VK_DESCRIPTOR_TYPE_UNIFORM_TEXEL_BUFFER).descriptorCount(4096);
            VkDescriptorPoolCreateInfo ci = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                    .maxSets(512)
                    .pPoolSizes(sizes);
            LongBuffer pPool = stack.mallocLong(1);
            VkContext.vkCheck(VK10.vkCreateDescriptorPool(ctx.device, ci, null, pPool), "vkCreateDescriptorPool");
            long pool = pPool.get(0);
            descriptorPools.add(pool);
            return pool;
        }
    }

    private ShaderLayout getOrCreateShaderLayout(ShaderProgram shader) {
        ShaderLayout sl = shaderLayouts.get(shader.id());
        if (sl != null) return sl;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            boolean hasUbo = shader.uniformBlockBytes() > 0;
            int nt = shader.samplers().size();
            int nr = shader.records().size();
            int nBindings = (hasUbo ? 1 : 0) + nt + nr;

            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(nBindings, stack);
            int bi = 0;
            if (hasUbo) {
                bindings.get(bi++)
                        .binding(0)
                        .descriptorType(VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC)
                        .descriptorCount(1)
                        .stageFlags(VK10.VK_SHADER_STAGE_VERTEX_BIT | VK10.VK_SHADER_STAGE_FRAGMENT_BIT);
            }
            for (int i = 0; i < nt; i++) {
                bindings.get(bi++)
                        .binding(shader.firstTextureUnit() + i)
                        .descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                        .descriptorCount(1)
                        .stageFlags(VK10.VK_SHADER_STAGE_FRAGMENT_BIT);
            }
            for (RecordView rv : shader.records()) {
                bindings.get(bi++)
                        .binding(rv.unit())
                        .descriptorType(VK10.VK_DESCRIPTOR_TYPE_UNIFORM_TEXEL_BUFFER)
                        .descriptorCount(1)
                        .stageFlags(VK10.VK_SHADER_STAGE_VERTEX_BIT | VK10.VK_SHADER_STAGE_FRAGMENT_BIT);
            }

            VkDescriptorSetLayoutCreateInfo dslCi = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                    .pBindings(bindings);
            LongBuffer pDsl = stack.mallocLong(1);
            VkContext.vkCheck(VK10.vkCreateDescriptorSetLayout(ctx.device, dslCi, null, pDsl), "vkCreateDescriptorSetLayout");
            long dsl = pDsl.get(0);

            VkPipelineLayoutCreateInfo plCi = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                    .pSetLayouts(stack.longs(dsl));
            LongBuffer pPl = stack.mallocLong(1);
            VkContext.vkCheck(VK10.vkCreatePipelineLayout(ctx.device, plCi, null, pPl), "vkCreatePipelineLayout");
            long pl = pPl.get(0);

            long vertMod = loadShaderModule(shader.id() + ".vert.spv");
            long fragMod = loadShaderModule(shader.id() + ".frag.spv");

            sl = new ShaderLayout(dsl, pl, vertMod, fragMod);
            shaderLayouts.put(shader.id(), sl);
            return sl;
        }
    }

    private long loadShaderModule(String fileName) {
        String path = SPIRV_DIR + fileName;
        byte[] bytes;
        try (InputStream in = VkPipelines.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("SPIR-V リソースが見つからない: " + path);
            bytes = in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("SPIR-V 読込失敗: " + path, e);
        }
        ByteBuffer nativeBuf = MemoryUtil.memAlloc(bytes.length);
        try {
            nativeBuf.put(bytes).flip();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkShaderModuleCreateInfo ci = VkShaderModuleCreateInfo.calloc(stack)
                        .sType(VK10.VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
                        .pCode(nativeBuf);
                LongBuffer pMod = stack.mallocLong(1);
                VkContext.vkCheck(VK10.vkCreateShaderModule(ctx.device, ci, null, pMod), "vkCreateShaderModule");
                return pMod.get(0);
            }
        } finally {
            MemoryUtil.memFree(nativeBuf);
        }
    }

    static int vkFormat(TextureFormat fmt, int depthFormat) {
        return switch (fmt) {
            case RGBA8 -> VK10.VK_FORMAT_R8G8B8A8_UNORM;
            case R8 -> VK10.VK_FORMAT_R8_UNORM;
            case RGBA16F -> VK10.VK_FORMAT_R16G16B16A16_SFLOAT;
            case DEPTH24 -> depthFormat;
        };
    }

    static int viewFormat(ViewFormat fmt) {
        return switch (fmt) {
            case FLOAT32 -> VK10.VK_FORMAT_R32_SFLOAT;
            case INT32 -> VK10.VK_FORMAT_R32_SINT;
            case HALF16 -> VK10.VK_FORMAT_R16_SFLOAT;
            case RGBA_FLOAT32 -> VK10.VK_FORMAT_R32G32B32A32_SFLOAT;
            case RGBA_INT32 -> VK10.VK_FORMAT_R32G32B32A32_SINT;
        };
    }

    static int sampleCountFlag(int samples) {
        if (samples <= 1) return VK10.VK_SAMPLE_COUNT_1_BIT;
        if (samples <= 2) return VK10.VK_SAMPLE_COUNT_2_BIT;
        if (samples <= 4) return VK10.VK_SAMPLE_COUNT_4_BIT;
        if (samples <= 8) return VK10.VK_SAMPLE_COUNT_8_BIT;
        return VK10.VK_SAMPLE_COUNT_1_BIT;
    }

    private static int vertexAttrFormat(int components) {
        return switch (components) {
            case 1 -> VK10.VK_FORMAT_R32_SFLOAT;
            case 2 -> VK10.VK_FORMAT_R32G32_SFLOAT;
            case 3 -> VK10.VK_FORMAT_R32G32B32_SFLOAT;
            case 4 -> VK10.VK_FORMAT_R32G32B32A32_SFLOAT;
            default -> throw new IllegalArgumentException("components: " + components);
        };
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        descriptorSets.clear();
        for (long p : descriptorPools) VK10.vkDestroyDescriptorPool(ctx.device, p, null);
        descriptorPools.clear();
        for (long pl : pipelines.values()) VK10.vkDestroyPipeline(ctx.device, pl, null);
        pipelines.clear();
        for (long rp : renderPasses.values()) VK10.vkDestroyRenderPass(ctx.device, rp, null);
        renderPasses.clear();
        for (ShaderLayout sl : shaderLayouts.values()) {
            VK10.vkDestroyPipelineLayout(ctx.device, sl.pipelineLayout, null);
            VK10.vkDestroyDescriptorSetLayout(ctx.device, sl.setLayout, null);
            VK10.vkDestroyShaderModule(ctx.device, sl.vertModule, null);
            VK10.vkDestroyShaderModule(ctx.device, sl.fragModule, null);
        }
        shaderLayouts.clear();
        for (long s : samplers) VK10.vkDestroySampler(ctx.device, s, null);
        samplers.clear();
    }
}
