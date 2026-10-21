package me.cortex.voxy.client.core.backend.vk;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanQueue;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.TrackedObject;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;

public class VkVoxyDevice extends TrackedObject {
    private static VkVoxyDevice INSTANCE;

    private final VulkanDevice renderPearlDevice;
    private final VkDevice vkDevice;
    private final VkPhysicalDevice physicalDevice;
    private final long vma;
    private final VulkanQueue graphicsQueue;
    private final VulkanQueue computeQueue;
    private final VkShaderCompiler compiler;
    private final long commandPool;
    private final boolean depthBounds;
    private boolean closed = false;

    public VkVoxyDevice(VulkanDevice device) {
        this.renderPearlDevice = device;
        this.vkDevice = device.vkDevice();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            java.nio.IntBuffer pCount = stack.callocInt(1);
            VK12.vkEnumeratePhysicalDevices(device.instance().vkInstance(), pCount, null);
            PointerBuffer pDevices = stack.callocPointer(pCount.get(0));
            VK12.vkEnumeratePhysicalDevices(device.instance().vkInstance(), pCount, pDevices);
            this.physicalDevice = new VkPhysicalDevice(pDevices.get(0), device.instance().vkInstance());
        }
        this.vma = device.vma();
        this.graphicsQueue = device.graphicsQueue();
        this.computeQueue = device.computeQueue();
        this.compiler = new VkShaderCompiler();
        this.depthBounds = hasFeatureSet(device, VkVoxyFeatures.DEPTH_BOUNDS);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandPoolCreateInfo poolInfo = VkCommandPoolCreateInfo.calloc(stack).sType$Default();
            poolInfo.queueFamilyIndex(this.graphicsQueue.queueFamilyIndex());
            poolInfo.flags(VK12.VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT);

            LongBuffer pPool = stack.callocLong(1);
            int res = VK12.vkCreateCommandPool(this.vkDevice, poolInfo, null, pPool);
            if (res != VK12.VK_SUCCESS) {
                throw new RuntimeException("vkCreateCommandPool failed: " + res);
            }
            this.commandPool = pPool.get(0);
        }

        INSTANCE = this;
        Logger.info("VkVoxyDevice initialized successfully (depthBounds=" + this.depthBounds + ")");
    }

    private static boolean hasFeatureSet(VulkanDevice device, com.mojang.renderpearl.backend.vulkan.init.FeatureSet set) {
        try {
            return ((me.cortex.voxy.client.mixin.minecraft.AccessorVulkanDevice) (Object) device).voxy$getEnabledFeatures().contains(set);
        } catch (Throwable t) {
            Logger.warn("Could not query enabled vulkan features: " + t);
            return false;
        }
    }

    public boolean depthBoundsSupported() {
        return this.depthBounds;
    }

    public static VkVoxyDevice getInstance() {
        if (INSTANCE == null) {
            var dev = RenderSystem.getDevice();
            if (dev instanceof me.cortex.voxy.client.mixin.minecraft.AccessorFrontendGpuDevice accessor) {
                if (accessor.voxy$getBackend() instanceof VulkanDevice vkDev) {
                    INSTANCE = new VkVoxyDevice(vkDev);
                    return INSTANCE;
                }
            }
            throw new IllegalStateException("Active GpuDevice is not a VulkanDevice");
        }
        return INSTANCE;
    }

    public static boolean isVulkanActive() {
        try {
            var dev = RenderSystem.getDevice();
            if (dev != null && dev.getDeviceInfo() != null) {
                return "Vulkan".equalsIgnoreCase(dev.getDeviceInfo().backendName());
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    public VulkanDevice renderPearlDevice() {
        return this.renderPearlDevice;
    }

    public VkDevice vkDevice() {
        return this.vkDevice;
    }

    public VkPhysicalDevice physicalDevice() {
        return this.vkDevice.getPhysicalDevice();
    }

    public long geometryBufferSize() {
        var override = System.getProperty("voxy.geometryBufferSizeOverrideMB", "");
        if (!override.isEmpty()) {
            return Long.parseLong(override)*1024L*1024L;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var maintenance3 = VkPhysicalDeviceMaintenance3Properties.calloc(stack).sType$Default();
            var props = VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(maintenance3.address());
            VK12.vkGetPhysicalDeviceProperties2(this.physicalDevice(), props);
            long maxRange = Integer.toUnsignedLong(props.properties().limits().maxStorageBufferRange());
            long maxAlloc = maintenance3.maxMemoryAllocationSize();
            long capacity = (1L<<32)-1024;
            if (maxRange > 0) capacity = Math.min(capacity, maxRange);
            if (maxAlloc > 0) capacity = Math.min(capacity, maxAlloc);

            var memProps = VkPhysicalDeviceMemoryProperties.calloc(stack);
            VK12.vkGetPhysicalDeviceMemoryProperties(this.physicalDevice(), memProps);
            long deviceLocal = 0;
            for (int i = 0; i < memProps.memoryHeapCount(); i++) {
                var heap = memProps.memoryHeaps(i);
                if ((heap.flags() & VK12.VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) != 0) {
                    deviceLocal = Math.max(deviceLocal, heap.size());
                }
            }
            if (deviceLocal != 0) {
                capacity = Math.min(capacity, Math.max(deviceLocal - (long)(2.25*1024*1024*1024), (long)(deviceLocal*0.4)));
            }
            Logger.info("Vulkan geometry buffer limits: maxStorageBufferRange=" + (maxRange>>20) + "mb, maxMemoryAllocationSize="
                    + (maxAlloc>>20) + "mb, device local heap=" + (deviceLocal>>20) + "mb");
            return Math.max(512L*1024*1024, capacity)&~7L;
        }
    }

    public long vma() {
        return this.vma;
    }

    public VulkanQueue graphicsQueue() {
        return this.graphicsQueue;
    }

    public VulkanQueue computeQueue() {
        return this.computeQueue;
    }

    public VkShaderCompiler compiler() {
        return this.compiler;
    }

    public long commandPool() {
        return this.commandPool;
    }

    public synchronized VkCommandBuffer allocateCommandBuffer() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc(stack).sType$Default();
            allocInfo.commandPool(this.commandPool);
            allocInfo.level(VK12.VK_COMMAND_BUFFER_LEVEL_PRIMARY);
            allocInfo.commandBufferCount(1);

            PointerBuffer pCmd = stack.callocPointer(1);
            int res = VK12.vkAllocateCommandBuffers(this.vkDevice, allocInfo, pCmd);
            if (res != VK12.VK_SUCCESS) {
                throw new RuntimeException("vkAllocateCommandBuffers failed: " + res);
            }
            return new VkCommandBuffer(pCmd.get(0), this.vkDevice);
        }
    }

    public synchronized void freeCommandBuffer(VkCommandBuffer cmd) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pCmd = stack.pointers(cmd);
            VK12.vkFreeCommandBuffers(this.vkDevice, this.commandPool, pCmd);
        }
    }

    public void beginSingleTimeCommands(VkCommandBuffer cmd) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack).sType$Default();
            beginInfo.flags(VK12.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            VK12.vkBeginCommandBuffer(cmd, beginInfo);
        }
    }

    public synchronized void endAndSubmitSingleTimeCommands(VkCommandBuffer cmd, VulkanQueue queue) {
        VK12.vkEndCommandBuffer(cmd);
        try (var sub = queue.beginSubmit()) {
            sub.executeCommands(cmd);
        }
        queue.waitIdle();
    }

    public void waitIdle() {
        this.graphicsQueue.waitIdle();
        if (this.computeQueue != this.graphicsQueue) {
            this.computeQueue.waitIdle();
        }
    }

    public static void cmdBeginRendering(VkCommandBuffer cmd, VkRenderingInfo info) {
        if (cmd.getCapabilities().vkCmdBeginRendering != 0) {
            VK13.vkCmdBeginRendering(cmd, info);
        } else {
            KHRDynamicRendering.vkCmdBeginRenderingKHR(cmd, info);
        }
    }

    public static void cmdEndRendering(VkCommandBuffer cmd) {
        if (cmd.getCapabilities().vkCmdEndRendering != 0) {
            VK13.vkCmdEndRendering(cmd);
        } else {
            KHRDynamicRendering.vkCmdEndRenderingKHR(cmd);
        }
    }

    @Override
    public void free() {
        super.free0();
        if (!this.closed) {
            this.closed = true;
            this.graphicsQueue.waitIdle();
            if (this.computeQueue != this.graphicsQueue) {
                this.computeQueue.waitIdle();
            }
            VK12.vkDestroyCommandPool(this.vkDevice, this.commandPool, null);
            this.compiler.free();
            if (INSTANCE == this) {
                INSTANCE = null;
            }
        }
    }
}
