/*
 * Modern UI. Licensed under LGPL-3.0-or-later.
 */
package icyllis.modernui.mc;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import icyllis.arc3d.vulkan.VulkanBackendContext;
import icyllis.arc3d.vulkan.VulkanImage;
import icyllis.arc3d.vulkan.VulkanMemoryAllocator;
import icyllis.modernui.core.VulkanManager;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageCopy;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;

import static org.lwjgl.vulkan.VK12.*;

/** Bridge to Minecraft 26.2's native Vulkan backend. All calls run on the render thread. */
public final class Blaze3DVulkanIntegration {
    private Blaze3DVulkanIntegration() {
    }

    public static boolean isActive() {
        return RenderSystem.getDevice().backend instanceof VulkanDevice;
    }

    private static VulkanDevice device() {
        return (VulkanDevice) RenderSystem.getDevice().backend;
    }

    public static VulkanBackendContext wrapContext(VulkanDevice device) {
        var context = new VulkanBackendContext();
        context.mDevice = device.vkDevice();
        context.mPhysicalDevice = context.mDevice.getPhysicalDevice();
        context.mInstance = context.mPhysicalDevice.getInstance();
        context.mQueue = device.graphicsQueue().vkQueue();
        context.mGraphicsQueueIndex = device.graphicsQueue().queueFamilyIndex();
        context.mMaxAPIVersion = VK_API_VERSION_1_2;

        // Only advertise features enabled by VulkanBackend, never all supported features.
        var manager = VulkanManager.get();
        var features = VkPhysicalDeviceFeatures2.calloc().sType$Default();
        features.features().multiDrawIndirect(true).fillModeNonSolid(true).samplerAnisotropy(true);
        manager.setPhysicalDeviceFeatures2(features);
        context.mDeviceFeatures2 = features;
        // Minecraft owns the VMA allocator and device; Arc3D must not destroy either.
        context.mMemoryAllocator = new VulkanMemoryAllocator(device.vma(), true);
        manager.setMemoryAllocator(context.mMemoryAllocator);
        return context;
    }

    public static void beforeArc3DSubmit() {
        // Flush pending Minecraft work before Arc3D reuses its offscreen image.
        // Both engines submit on the same graphics queue from this thread.
        device().createCommandEncoder().submit();
    }

    public static GpuTexture createLayerTexture(VulkanImage source) {
        if (source.getVulkanDesc().mVkFormat != VK_FORMAT_R8G8B8A8_UNORM ||
                source.getVulkanDesc().getSampleCount() != 1 ||
                (source.getVulkanDesc().mImageUsageFlags & VK_IMAGE_USAGE_TRANSFER_SRC_BIT) == 0) {
            throw new IllegalStateException("Unsupported ModernUI Vulkan surface: " + source.getVulkanDesc());
        }
        return RenderSystem.getDevice().createTexture("ModernUI Vulkan UI layer",
                GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
                GpuFormat.RGBA8_UNORM, source.getWidth(), source.getHeight(), 1, 1);
    }

    public static void copyLayer(VulkanImage source, GpuTexture destination) {
        var device = device();
        var encoder = device.createCommandEncoder();
        VkCommandBuffer commands = encoder.allocateAndBeginTransientCommandBuffer();
        int layout = source.getVulkanMutableState().getImageLayout();
        try (var stack = MemoryStack.stackPush()) {
            // Blaze3D keeps its textures in GENERAL. Restore Arc3D's tracked layout
            // after the copy so its next recording can reuse the same image.
            barrier(commands, stack, source.vkImage(), layout, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
            barrier(commands, stack, ((VulkanGpuTexture) destination).vkImage(),
                    VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL);
            var copy = VkImageCopy.calloc(1, stack);
            copy.srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            copy.dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            copy.extent().set(source.getWidth(), source.getHeight(), 1);
            vkCmdCopyImage(commands, source.vkImage(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    ((VulkanGpuTexture) destination).vkImage(), VK_IMAGE_LAYOUT_GENERAL, copy);
            barrier(commands, stack, source.vkImage(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, layout);
            barrier(commands, stack, ((VulkanGpuTexture) destination).vkImage(),
                    VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL);
        }
        int result = vkEndCommandBuffer(commands);
        if (result != VK_SUCCESS) {
            throw new IllegalStateException("Failed to end ModernUI Vulkan copy: " + result);
        }
        encoder.execute(commands);
        // Keep the source alive through the copy, using Minecraft's completion queue.
        source.refCommandBuffer();
        encoder.queueForDestroy(source::unrefCommandBuffer);
        // Submit before Arc3D can render into the source again, even within this frame.
        encoder.submit();
    }

    private static void barrier(VkCommandBuffer commands, MemoryStack stack, long image,
                                int oldLayout, int newLayout) {
        var barrier = VkImageMemoryBarrier.calloc(1, stack).sType$Default()
                .srcAccessMask(VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT)
                .dstAccessMask(VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT)
                .oldLayout(oldLayout).newLayout(newLayout)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(image);
        barrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
        vkCmdPipelineBarrier(commands, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                0, null, null, barrier);
    }
}
