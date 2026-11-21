package com.nishiyu.enderframelib.core.paint.vulkan;

import java.lang.reflect.Field;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.Configuration;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.EXTDebugUtils;
import org.lwjgl.vulkan.KHRSurface;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.VK;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCallbackDataEXT;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCallbackEXT;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCreateInfoEXT;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkDeviceQueueCreateInfo;
import org.lwjgl.vulkan.VkExtensionProperties;
import org.lwjgl.vulkan.VkFormatProperties;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkLayerProperties;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceLimits;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkQueueFamilyProperties;

/**
 * Vulkan 1.1 インスタンス・物理/論理デバイス・グラフィックスキューの管理（G3）。
 *
 * <p>必須拡張なしでヘッドレス（窓・GLFW 不要）に初期化でき、検証レイヤ（{@code VK_LAYER_KHRONOS_validation}）が
 * 利用可能かつ有効化された場合は {@code VK_EXT_debug_utils} でエラー／警告件数を捕捉する。</p>
 */
public final class VkContext implements AutoCloseable {
    static {
        if (Configuration.STACK_SIZE.get() == null) {
            Configuration.STACK_SIZE.set(512);
        }
    }

    private static final String VALIDATION_LAYER = "VK_LAYER_KHRONOS_validation";
    private static final String PORTABILITY_ENUMERATION_EXT = "VK_KHR_portability_enumeration";
    private static final int VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR = 0x00000001;

    final VkInstance instance;
    final VkPhysicalDevice physicalDevice;
    final org.lwjgl.vulkan.VkDevice device;
    final VkQueue graphicsQueue;
    final int graphicsQueueFamily;
    final VkPhysicalDeviceMemoryProperties memoryProperties;
    final VkPhysicalDeviceProperties deviceProperties;
    final boolean swapchainEnabled;
    final boolean validationActive;
    final int depthFormat;

    private final long debugMessenger;
    private final VkDebugUtilsMessengerCallbackEXT debugCallback;
    private final AtomicInteger validationErrorCount = new AtomicInteger();
    private final List<String> validationMessages = new CopyOnWriteArrayList<>();
    private boolean closed;
    /** false: ホストから借りたハンドル（{@link #adopt}）。{@link #close} は破棄しない。 */
    private final boolean ownsHandles;

    /** ホストが作った Vulkan のハンドルを借りる（A14）。インスタンス・デバイスはホストのもので、{@link #close} は破棄しない。 */
    private VkContext(long instanceHandle, long physicalDeviceHandle, long deviceHandle, int queueFamily, long queueHandle) {
        VK.getFunctionProvider();
        this.ownsHandles = false;
        VkPhysicalDeviceMemoryProperties memProps = null;
        VkPhysicalDeviceProperties devProps = null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // LWJGL のラッパーは「有効な拡張」の一覧から関数表を作る。ホストの一覧は分からないので、1.1 の中核関数だけを使う前提にする
            VkApplicationInfo appInfo = VkApplicationInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_APPLICATION_INFO)
                    .apiVersion(VK11.VK_API_VERSION_1_1);
            VkInstanceCreateInfo ici = VkInstanceCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
                    .pApplicationInfo(appInfo);
            VkInstance inst = withLargeStack(() -> new VkInstance(instanceHandle, ici));
            VkPhysicalDevice phys = new VkPhysicalDevice(physicalDeviceHandle, inst);
            VkDeviceCreateInfo dci = VkDeviceCreateInfo.calloc(stack).sType(VK10.VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO);
            org.lwjgl.vulkan.VkDevice dev = withLargeStack(() -> new org.lwjgl.vulkan.VkDevice(deviceHandle, phys, dci));

            devProps = VkPhysicalDeviceProperties.calloc();
            VK10.vkGetPhysicalDeviceProperties(phys, devProps);
            if (devProps.apiVersion() < VK11.VK_API_VERSION_1_1) {
                throw new IllegalArgumentException("ホストの Vulkan デバイスは 1.1 以上が必要（apiVersion=0x"
                        + Integer.toHexString(devProps.apiVersion()) + "）");
            }
            memProps = VkPhysicalDeviceMemoryProperties.calloc();
            VK10.vkGetPhysicalDeviceMemoryProperties(phys, memProps);

            IntBuffer n = stack.mallocInt(1);
            VK10.vkGetPhysicalDeviceQueueFamilyProperties(phys, n, null);
            if (queueFamily >= n.get(0)) {
                throw new IllegalArgumentException("queueFamily=" + queueFamily + " は存在しない（" + n.get(0) + " 個）");
            }
            VkQueueFamilyProperties.Buffer qfs = VkQueueFamilyProperties.calloc(n.get(0), stack);
            VK10.vkGetPhysicalDeviceQueueFamilyProperties(phys, n, qfs);
            if ((qfs.get(queueFamily).queueFlags() & VK10.VK_QUEUE_GRAPHICS_BIT) == 0) {
                throw new IllegalArgumentException("queueFamily=" + queueFamily + " はグラフィックス非対応");
            }

            this.instance = inst;
            this.physicalDevice = phys;
            this.device = dev;
            this.graphicsQueue = new VkQueue(queueHandle, dev);
            this.graphicsQueueFamily = queueFamily;
            this.memoryProperties = memProps;
            this.deviceProperties = devProps;
            this.swapchainEnabled = false;
            this.validationActive = false;
            this.depthFormat = pickDepthFormat(stack, phys);
            this.debugMessenger = VK10.VK_NULL_HANDLE;
            this.debugCallback = null;
        } catch (Throwable t) {
            if (memProps != null) memProps.free();
            if (devProps != null) devProps.free();
            throw t;
        }
    }

    /**
     * ホストが作った {@code VkInstance}・{@code VkPhysicalDevice}・{@code VkDevice}・グラフィックスキューの上にコンテキストを作る。
     * ハンドルはホストの所有で、{@link #close} は破棄しない。Vulkan 1.1 以上・グラフィックス対応のキューファミリが必要。
     *
     * @throws IllegalArgumentException 1.1 未満・キューファミリがグラフィックス非対応のとき
     */
    public static VkContext adopt(long instance, long physicalDevice, long device, int queueFamily, long queue) {
        return new VkContext(instance, physicalDevice, device, queueFamily, queue);
    }

    /** ホストへ渡す・テストが比べるためのネイティブハンドル。 */
    public long instanceHandle() { return instance.address(); }
    public long physicalDeviceHandle() { return physicalDevice.address(); }
    public long deviceHandle() { return device.address(); }
    public long queueHandle() { return graphicsQueue.address(); }
    public int queueFamily() { return graphicsQueueFamily; }

    private VkContext(boolean requestValidation, Collection<String> instanceExtensions, boolean enableSwapchain) {
        VK.getFunctionProvider(); // ライブラリロードを明示確認
        boolean hasValidationLayer = false;
        boolean hasDebugUtils = false;
        boolean hasPortabilityEnum = false;
        this.ownsHandles = true;
        VkInstance inst = null;
        long messenger = VK10.VK_NULL_HANDLE;
        VkDebugUtilsMessengerCallbackEXT callback = null;
        org.lwjgl.vulkan.VkDevice logDev = null;
        VkPhysicalDeviceMemoryProperties memProps = null;
        VkPhysicalDeviceProperties devProps = null;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            Set<String> availLayers = availableLayers(stack);
            Set<String> availExts = availableInstanceExtensions(stack);

            List<String> enabledLayers = new ArrayList<>();
            if (requestValidation && availLayers.contains(VALIDATION_LAYER)) {
                enabledLayers.add(VALIDATION_LAYER);
                hasValidationLayer = true;
            }
            List<String> enabledExts = new ArrayList<>();
            if (instanceExtensions != null) {
                for (String ext : instanceExtensions) {
                    if (!enabledExts.contains(ext)) enabledExts.add(ext);
                }
            }
            if (hasValidationLayer && availExts.contains(EXTDebugUtils.VK_EXT_DEBUG_UTILS_EXTENSION_NAME)) {
                if (!enabledExts.contains(EXTDebugUtils.VK_EXT_DEBUG_UTILS_EXTENSION_NAME)) {
                    enabledExts.add(EXTDebugUtils.VK_EXT_DEBUG_UTILS_EXTENSION_NAME);
                }
                hasDebugUtils = true;
            }
            if (availExts.contains(PORTABILITY_ENUMERATION_EXT)) {
                if (!enabledExts.contains(PORTABILITY_ENUMERATION_EXT)) enabledExts.add(PORTABILITY_ENUMERATION_EXT);
                hasPortabilityEnum = true;
            }

            VkApplicationInfo appInfo = VkApplicationInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_APPLICATION_INFO)
                    .pApplicationName(stack.UTF8("EnderFrameLib"))
                    .applicationVersion(VK10.VK_MAKE_VERSION(1, 0, 0))
                    .pEngineName(stack.UTF8("EnderFrame"))
                    .engineVersion(VK10.VK_MAKE_VERSION(1, 0, 0))
                    .apiVersion(VK11.VK_API_VERSION_1_1);

            PointerBuffer ppLayers = toPointerBuffer(stack, enabledLayers);
            PointerBuffer ppExts = toPointerBuffer(stack, enabledExts);

            VkInstanceCreateInfo ci = VkInstanceCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
                    .pApplicationInfo(appInfo)
                    .ppEnabledLayerNames(ppLayers)
                    .ppEnabledExtensionNames(ppExts);
            if (hasPortabilityEnum) {
                ci.flags(VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR);
            }

            if (hasDebugUtils) {
                callback = VkDebugUtilsMessengerCallbackEXT.create((severity, types, pData, pUserData) -> {
                    if ((types & (EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT
                            | EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT)) == 0) {
                        return VK10.VK_FALSE;
                    }
                    VkDebugUtilsMessengerCallbackDataEXT data = VkDebugUtilsMessengerCallbackDataEXT.create(pData);
                    String msg = data.pMessageString();
                    if ((severity & (EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT
                            | EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT)) != 0) {
                        validationErrorCount.incrementAndGet();
                        if (validationMessages.size() < 64) {
                            validationMessages.add(msg);
                        }
                    }
                    return VK10.VK_FALSE;
                });
                VkDebugUtilsMessengerCreateInfoEXT dbgCi = VkDebugUtilsMessengerCreateInfoEXT.calloc(stack)
                        .sType(EXTDebugUtils.VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT)
                        .messageSeverity(EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT
                                | EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT)
                        .messageType(EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT
                                | EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT)
                        .pfnUserCallback(callback);
                ci.pNext(dbgCi.address());
            }

            PointerBuffer pInst = stack.mallocPointer(1);
            vkCheck(VK10.vkCreateInstance(ci, null, pInst), "vkCreateInstance");
            final long instHandle = pInst.get(0);
            // LWJGL VkInstance.<init> はスレッドの MemoryStack 上へ全拡張列を積むため、64KB 既定スタック溢れを防ぐ
            inst = withLargeStack(() -> new VkInstance(instHandle, ci));

            if (hasDebugUtils) {
                VkDebugUtilsMessengerCreateInfoEXT dbgCi = VkDebugUtilsMessengerCreateInfoEXT.calloc(stack)
                        .sType(EXTDebugUtils.VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT)
                        .messageSeverity(EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT
                                | EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT)
                        .messageType(EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT
                                | EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT)
                        .pfnUserCallback(callback);
                LongBuffer pMessenger = stack.mallocLong(1);
                vkCheck(EXTDebugUtils.vkCreateDebugUtilsMessengerEXT(inst, dbgCi, null, pMessenger),
                        "vkCreateDebugUtilsMessengerEXT");
                messenger = pMessenger.get(0);
            }

            PhysicalPick pick = pickPhysicalDevice(stack, inst, enableSwapchain);
            this.physicalDevice = pick.device;
            this.graphicsQueueFamily = pick.graphicsFamily;

            devProps = VkPhysicalDeviceProperties.calloc();
            VK10.vkGetPhysicalDeviceProperties(physicalDevice, devProps);
            memProps = VkPhysicalDeviceMemoryProperties.calloc();
            VK10.vkGetPhysicalDeviceMemoryProperties(physicalDevice, memProps);

            VkDeviceQueueCreateInfo.Buffer queueCi = VkDeviceQueueCreateInfo.calloc(1, stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
                    .queueFamilyIndex(graphicsQueueFamily)
                    .pQueuePriorities(stack.floats(1.0f));

            List<String> devExts = new ArrayList<>();
            if (enableSwapchain) {
                devExts.add(KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME);
            }
            if (deviceHasExtension(stack, physicalDevice, "VK_KHR_portability_subset")) {
                devExts.add("VK_KHR_portability_subset");
            }

            VkDeviceCreateInfo devCi = VkDeviceCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
                    .pQueueCreateInfos(queueCi)
                    .ppEnabledExtensionNames(toPointerBuffer(stack, devExts));

            PointerBuffer pDevice = stack.mallocPointer(1);
            vkCheck(VK10.vkCreateDevice(physicalDevice, devCi, null, pDevice), "vkCreateDevice");
            final long devHandle = pDevice.get(0);
            final VkPhysicalDevice physDev = physicalDevice;
            logDev = withLargeStack(() -> new org.lwjgl.vulkan.VkDevice(devHandle, physDev, devCi));

            PointerBuffer pQueue = stack.mallocPointer(1);
            VK10.vkGetDeviceQueue(logDev, graphicsQueueFamily, 0, pQueue);
            this.graphicsQueue = new VkQueue(pQueue.get(0), logDev);

            this.instance = inst;
            this.device = logDev;
            this.memoryProperties = memProps;
            this.deviceProperties = devProps;
            this.debugMessenger = messenger;
            this.debugCallback = callback;
            this.swapchainEnabled = enableSwapchain;
            this.validationActive = hasValidationLayer;
            this.depthFormat = pickDepthFormat(stack, physicalDevice);
        } catch (Throwable t) {
            if (logDev != null) VK10.vkDestroyDevice(logDev, null);
            if (memProps != null) memProps.free();
            if (devProps != null) devProps.free();
            if (inst != null && messenger != VK10.VK_NULL_HANDLE) {
                EXTDebugUtils.vkDestroyDebugUtilsMessengerEXT(inst, messenger, null);
            }
            if (callback != null) callback.free();
            if (inst != null) VK10.vkDestroyInstance(inst, null);
            throw t;
        }
    }

    /** 窓・GLFW 不要のヘッドレス Vulkan 1.1 コンテキストを作る。 */
    public static VkContext createHeadless(boolean validation) {
        return new VkContext(validation, List.of(), false);
    }

    /** スワップチェーン拡張（{@code VK_KHR_swapchain}）と指定インスタンス拡張付きで作る。 */
    public static VkContext createForSurface(boolean validation, Collection<String> instanceExtensions) {
        return new VkContext(validation, instanceExtensions, true);
    }

    public int validationErrorCount() {
        return validationErrorCount.get();
    }

    /**
     * 選んだデバイスが CPU 上のソフトウェア実装（Mesa の lavapipe など）か。GPU が無い環境では、これしか選べないことがある。
     * 試験の側が、ソフトウェア実装を GPU の比較から外すのに使う（WSL の lavapipe は backdrop-filter のぼかしのパイプラインを作るところで落ちる。T2b）。
     */
    public boolean isCpuDevice() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(stack);
            VK10.vkGetPhysicalDeviceProperties(physicalDevice, props);
            return props.deviceType() == VK10.VK_PHYSICAL_DEVICE_TYPE_CPU;
        }
    }

    public List<String> validationMessages() {
        return List.copyOf(validationMessages);
    }

    public boolean supportsPresent(long surface) {
        if (!swapchainEnabled || surface == VK10.VK_NULL_HANDLE) return false;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer supported = stack.mallocInt(1);
            int err = KHRSurface.vkGetPhysicalDeviceSurfaceSupportKHR(physicalDevice, graphicsQueueFamily, surface, supported);
            return err == VK10.VK_SUCCESS && supported.get(0) == VK10.VK_TRUE;
        }
    }

    VkPhysicalDeviceLimits limits() {
        return deviceProperties.limits();
    }

    int findMemoryType(int typeFilter, int requiredProps, int preferredProps) {
        int fallback = -1;
        int count = memoryProperties.memoryTypeCount();
        for (int i = 0; i < count; i++) {
            if ((typeFilter & (1 << i)) == 0) continue;
            int flags = memoryProperties.memoryTypes(i).propertyFlags();
            if ((flags & (requiredProps | preferredProps)) == (requiredProps | preferredProps)) {
                return i;
            }
            if (fallback < 0 && (flags & requiredProps) == requiredProps) {
                fallback = i;
            }
        }
        if (fallback < 0) {
            throw new IllegalStateException("適合する Vulkan メモリ型が無い: props=0x" + Integer.toHexString(requiredProps));
        }
        return fallback;
    }

    private record PhysicalPick(VkPhysicalDevice device, int graphicsFamily) {}

    private static PhysicalPick pickPhysicalDevice(MemoryStack stack, VkInstance inst, boolean requireSwapchain) {
        IntBuffer countBuf = stack.mallocInt(1);
        vkCheck(VK10.vkEnumeratePhysicalDevices(inst, countBuf, null), "vkEnumeratePhysicalDevices");
        int count = countBuf.get(0);
        if (count == 0) throw new IllegalStateException("Vulkan 1.1 対応 GPU が見つからない");
        PointerBuffer pDevices = stack.mallocPointer(count);
        vkCheck(VK10.vkEnumeratePhysicalDevices(inst, countBuf, pDevices), "vkEnumeratePhysicalDevices");

        VkPhysicalDevice best = null;
        int bestFamily = -1;
        int bestScore = -1;
        VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(stack);
        for (int i = 0; i < count; i++) {
            VkPhysicalDevice pd = new VkPhysicalDevice(pDevices.get(i), inst);
            VK10.vkGetPhysicalDeviceProperties(pd, props);
            if (props.apiVersion() < VK11.VK_API_VERSION_1_1) continue;
            if (requireSwapchain && !deviceHasExtension(stack, pd, KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME)) continue;
            int fam = findGraphicsQueueFamily(stack, pd);
            if (fam < 0) continue;
            int score = switch (props.deviceType()) {
                case VK10.VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU -> 400;
                case VK10.VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU -> 300;
                case VK10.VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU -> 200;
                case VK10.VK_PHYSICAL_DEVICE_TYPE_CPU -> 100;
                default -> 50;
            };
            if (score > bestScore) {
                bestScore = score;
                best = pd;
                bestFamily = fam;
            }
        }
        if (best == null) throw new IllegalStateException("Vulkan 1.1 グラフィックスキューを持つ GPU が無い");
        return new PhysicalPick(best, bestFamily);
    }

    private static int findGraphicsQueueFamily(MemoryStack stack, VkPhysicalDevice pd) {
        IntBuffer countBuf = stack.mallocInt(1);
        VK10.vkGetPhysicalDeviceQueueFamilyProperties(pd, countBuf, null);
        int count = countBuf.get(0);
        VkQueueFamilyProperties.Buffer qfs = VkQueueFamilyProperties.calloc(count, stack);
        VK10.vkGetPhysicalDeviceQueueFamilyProperties(pd, countBuf, qfs);
        for (int i = 0; i < count; i++) {
            if ((qfs.get(i).queueFlags() & VK10.VK_QUEUE_GRAPHICS_BIT) != 0) return i;
        }
        return -1;
    }

    private static int pickDepthFormat(MemoryStack stack, VkPhysicalDevice pd) {
        int[] candidates = {
                VK10.VK_FORMAT_D24_UNORM_S8_UINT,
                VK10.VK_FORMAT_X8_D24_UNORM_PACK32,
                VK10.VK_FORMAT_D32_SFLOAT
        };
        VkFormatProperties fp = VkFormatProperties.calloc(stack);
        for (int fmt : candidates) {
            VK10.vkGetPhysicalDeviceFormatProperties(pd, fmt, fp);
            if ((fp.optimalTilingFeatures() & VK10.VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT) != 0) {
                return fmt;
            }
        }
        throw new IllegalStateException("対応する深度テクスチャ形式が無い");
    }

    private static Set<String> availableLayers(MemoryStack stack) {
        IntBuffer countBuf = stack.mallocInt(1);
        vkCheck(VK10.vkEnumerateInstanceLayerProperties(countBuf, null), "vkEnumerateInstanceLayerProperties");
        int n = countBuf.get(0);
        Set<String> set = new HashSet<>(n * 2);
        if (n > 0) {
            try (VkLayerProperties.Buffer buf = VkLayerProperties.calloc(n)) {
                vkCheck(VK10.vkEnumerateInstanceLayerProperties(countBuf, buf), "vkEnumerateInstanceLayerProperties");
                for (int i = 0; i < n; i++) set.add(buf.get(i).layerNameString());
            }
        }
        return set;
    }

    private static Set<String> availableInstanceExtensions(MemoryStack stack) {
        IntBuffer countBuf = stack.mallocInt(1);
        vkCheck(VK10.vkEnumerateInstanceExtensionProperties((String) null, countBuf, null),
                "vkEnumerateInstanceExtensionProperties");
        int n = countBuf.get(0);
        Set<String> set = new HashSet<>(n * 2);
        if (n > 0) {
            try (VkExtensionProperties.Buffer buf = VkExtensionProperties.calloc(n)) {
                vkCheck(VK10.vkEnumerateInstanceExtensionProperties((String) null, countBuf, buf),
                        "vkEnumerateInstanceExtensionProperties");
                for (int i = 0; i < n; i++) set.add(buf.get(i).extensionNameString());
            }
        }
        return set;
    }

    private static boolean deviceHasExtension(MemoryStack stack, VkPhysicalDevice pd, String ext) {
        IntBuffer countBuf = stack.mallocInt(1);
        vkCheck(VK10.vkEnumerateDeviceExtensionProperties(pd, (String) null, countBuf, null),
                "vkEnumerateDeviceExtensionProperties");
        int n = countBuf.get(0);
        if (n <= 0) return false;
        try (VkExtensionProperties.Buffer buf = VkExtensionProperties.calloc(n)) {
            vkCheck(VK10.vkEnumerateDeviceExtensionProperties(pd, (String) null, countBuf, buf),
                    "vkEnumerateDeviceExtensionProperties");
            for (int i = 0; i < n; i++) {
                if (ext.equals(buf.get(i).extensionNameString())) return true;
            }
        }
        return false;
    }

    private static PointerBuffer toPointerBuffer(MemoryStack stack, List<String> strings) {
        if (strings.isEmpty()) return null;
        PointerBuffer pb = stack.mallocPointer(strings.size());
        for (int i = 0; i < strings.size(); i++) {
            pb.put(i, stack.UTF8(strings.get(i)));
        }
        return pb;
    }

    @SuppressWarnings("unchecked")
    private static <T> T withLargeStack(Supplier<T> action) {
        if (MemoryStack.stackGet().getSize() >= 512 * 1024) {
            return action.get();
        }
        try {
            Field tlsField = MemoryStack.class.getDeclaredField("TLS");
            tlsField.setAccessible(true);
            ThreadLocal<MemoryStack> tls = (ThreadLocal<MemoryStack>) tlsField.get(null);
            MemoryStack prev = tls.get();
            MemoryStack large = MemoryStack.create(512 * 1024);
            tls.set(large);
            try {
                return action.get();
            } finally {
                tls.set(prev);
            }
        } catch (ReflectiveOperationException e) {
            return action.get();
        }
    }

    static void vkCheck(int err, String op) {
        if (err != VK10.VK_SUCCESS) {
            throw new IllegalStateException("Vulkan エラー (" + op + "): VkResult=" + err);
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        memoryProperties.free();
        deviceProperties.free();
        if (!ownsHandles) return; // ホストのデバイス・インスタンスは壊さない（待ちも呼び出し側の VkDevice.close が済ませている）
        VK10.vkDeviceWaitIdle(device);
        VK10.vkDestroyDevice(device, null);
        if (debugMessenger != VK10.VK_NULL_HANDLE) {
            EXTDebugUtils.vkDestroyDebugUtilsMessengerEXT(instance, debugMessenger, null);
        }
        if (debugCallback != null) {
            debugCallback.free();
        }
        VK10.vkDestroyInstance(instance, null);
    }
}
