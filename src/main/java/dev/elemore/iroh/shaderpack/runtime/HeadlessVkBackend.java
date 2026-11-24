package dev.elemore.iroh.shaderpack.runtime;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK13.*;

/**
 * A standalone headless Vulkan device for the offline replay (no window, no swapchain, no game, not Iroh's renderer).
 * SAFETY: it refuses every physical device that is not a CPU implementation (lavapipe / llvmpipe) unless explicitly
 * allowed, and it caps its own device memory ({@link #cap}, 1 GiB by default): a replay can never touch the owner's
 * GPU. The harness also restricts the Vulkan loader to the lavapipe ICD (VK_DRIVER_FILES).
 * <p>
 * One primary command buffer: setup commands (image creation, uploads) and the frame are recorded into it as they
 * come; {@link #endFrame} / {@link #readback} submit and wait. The draw code is {@link VkPackBackend}'s, shared with
 * the in-game backend.
 */
public final class HeadlessVkBackend extends VkPackBackend {

    private final VkInstance instance;
    private final VkPhysicalDevice phys;
    private final VkDevice device;
    private final VkQueue queue;
    private final long cmdPool, fence, descPool;
    private final VkCommandBuffer cmd;
    private final VkPhysicalDeviceMemoryProperties memProps;
    public final String deviceName;
    public final long cap;
    private long allocated;
    private boolean recording;
    private final List<HostBuf> frameBufs = new ArrayList<>();
    private final List<Runnable> later = new ArrayList<>();

    /**
     * V-7: frames in flight. 1 (default) = every frame is submitted and waited (setup work too). N > 1 = the in-game
     * contract of IrohVkBackend on VulkanMod's renderer: one command buffer, fence, descriptor pool and UBO ring per frame
     * slot; setup work made outside a frame (prepare's image creation) goes into the NEXT frame's buffer; endFrame submits
     * WITHOUT waiting; a slot's fence is waited only when the slot records again (frame N+1 is submitted while frame N
     * may still run). Deferred frees wait for the slot that used them. Sync validation then sees cross-frame hazards on
     * the one queue (it validates at submit time against work not yet known complete).
     */
    public final int inFlight;
    private VkCommandBuffer[] slotCmds;
    private long[] slotFences, slotPools;
    private boolean[] slotPending;
    private List<List<HostBuf>> slotBufs;
    private List<List<Runnable>> slotLater;
    private int slot, lastSubmitted = -1;

    private record Mem(long memory, long size) {}

    /** V-5: validation-layer ERROR messages of this device (only with {@code validation = true}); thread-safe */
    public final List<String> validationErrors = java.util.Collections.synchronizedList(new ArrayList<>());
    private long messenger;
    private VkDebugUtilsMessengerCallbackEXT messengerCb;

    public HeadlessVkBackend(boolean allowGpu, long capBytes) {
        this(allowGpu, capBytes, false);
    }

    /** {@code validation}: enable VK_LAYER_KHRONOS_validation on this instance and collect its errors in {@link #validationErrors} */
    public HeadlessVkBackend(boolean allowGpu, long capBytes, boolean validation) {
        this(allowGpu, capBytes, validation, 1);
    }

    /**
     * PACK-FULL: the device's feature set. ALL = every core feature the device offers (the replays' default: blind to a
     * feature the game does not enable); GAME = what DeviceManager enables (rc14 set + indirect bits + the pack's
     * {@link PackDeviceFeatures}); GAME_NO_PACK = the game set before PACK-FULL (negative control: no store features).
     */
    public enum Features { ALL, GAME, GAME_NO_PACK }

    /** -Diroh.vkval.stacks=true: each validation message carries the recording call stack (harness debugging) */
    static final boolean STACKS = Boolean.getBoolean("iroh.vkval.stacks");

    private boolean vertexStores = true, fragmentStores = true;

    @Override
    protected boolean vertexStoresEnabled() {
        return vertexStores;
    }

    @Override
    protected boolean fragmentStoresEnabled() {
        return fragmentStores;
    }

    private boolean independentBlend = true;

    @Override
    protected boolean independentBlendEnabled() {
        return independentBlend;
    }

    /** {@code inFlight}: frames in flight (V-7; 1 = submit and wait every frame) */
    public HeadlessVkBackend(boolean allowGpu, long capBytes, boolean validation, int inFlight) {
        this(allowGpu, capBytes, validation, inFlight, Features.ALL);
    }

    public HeadlessVkBackend(boolean allowGpu, long capBytes, boolean validation, int inFlight, Features features) {
        this.cap = capBytes;
        this.inFlight = Math.max(1, inFlight);
        try (MemoryStack s = stackPush()) {
            VkApplicationInfo app = VkApplicationInfo.calloc(s).sType$Default().pApplicationName(s.UTF8("iroh-pack-replay"))
                    .apiVersion(features == Features.ALL ? VK_API_VERSION_1_3 : VK_API_VERSION_1_2); // PACK-FULL review #4: the game's instance is 1.2
            VkInstanceCreateInfo ici = VkInstanceCreateInfo.calloc(s).sType$Default().pApplicationInfo(app);
            if (validation)
                ici.ppEnabledLayerNames(s.pointers(s.UTF8("VK_LAYER_KHRONOS_validation")))
                        .ppEnabledExtensionNames(s.pointers(s.UTF8(EXTDebugUtils.VK_EXT_DEBUG_UTILS_EXTENSION_NAME)));
            PointerBuffer pp = s.mallocPointer(1);
            check(vkCreateInstance(ici, null, pp), validation ? "vkCreateInstance (validation layer missing?)" : "vkCreateInstance");
            instance = new VkInstance(pp.get(0), ici);
            if (validation) {
                messengerCb = VkDebugUtilsMessengerCallbackEXT.create((sev, type, data, user) -> {
                    String msg = VkDebugUtilsMessengerCallbackDataEXT.create(data).pMessageString();
                    if (STACKS) // PACK-FULL harness debugging: where the offending command was recorded
                        msg += " @ " + java.util.Arrays.stream(Thread.currentThread().getStackTrace()).skip(2)
                                .filter(f -> f.getClassName().startsWith("dev.elemore")).limit(8).map(f -> f.getClassName().replaceAll(".*\\.", "") + "." + f.getMethodName() + ":" + f.getLineNumber())
                                .collect(java.util.stream.Collectors.joining(" < "));
                    validationErrors.add(msg);
                    return VK_FALSE;
                });
                LongBuffer ml = s.mallocLong(1);
                check(EXTDebugUtils.vkCreateDebugUtilsMessengerEXT(instance, VkDebugUtilsMessengerCreateInfoEXT.calloc(s).sType$Default()
                        .messageSeverity(EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT)
                        .messageType(EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT | EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT)
                        .pfnUserCallback(messengerCb), null, ml), "messenger");
                messenger = ml.get(0);
            }
            VkDevice dev = null;
            VkPhysicalDeviceMemoryProperties mp = null;
            try { // V-7 (review 698ba7c #4): every failure below releases the device, messenger, callback and instance
            IntBuffer n = s.mallocInt(1);
            vkEnumeratePhysicalDevices(instance, n, null);
            PointerBuffer devs = s.mallocPointer(n.get(0));
            vkEnumeratePhysicalDevices(instance, n, devs);
            VkPhysicalDevice chosen = null;
            String name = null;
            for (int i = 0; i < devs.capacity(); i++) {
                VkPhysicalDevice pd = new VkPhysicalDevice(devs.get(i), instance);
                VkPhysicalDeviceProperties pr = VkPhysicalDeviceProperties.calloc(s);
                vkGetPhysicalDeviceProperties(pd, pr);
                boolean cpu = pr.deviceType() == VK_PHYSICAL_DEVICE_TYPE_CPU;
                if (cpu || allowGpu) {
                    chosen = pd;
                    name = pr.deviceNameString();
                    if (cpu)
                        break;
                }
            }
            if (chosen == null)
                throw new IllegalStateException("no CPU Vulkan device (lavapipe) found; the replay never uses a GPU");
            phys = chosen;
            deviceName = name;
            memProps = mp = VkPhysicalDeviceMemoryProperties.malloc();
            vkGetPhysicalDeviceMemoryProperties(phys, memProps);
            vkGetPhysicalDeviceQueueFamilyProperties(phys, n, null);
            VkQueueFamilyProperties.Buffer qf = VkQueueFamilyProperties.calloc(n.get(0), s);
            vkGetPhysicalDeviceQueueFamilyProperties(phys, n, qf);
            int family = -1;
            for (int i = 0; i < qf.capacity(); i++)
                if ((qf.get(i).queueFlags() & (VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT)) == (VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT)) {
                    family = i;
                    break;
                }
            if (family < 0)
                throw new IllegalStateException("no graphics+compute queue family on " + name);
            // enable the core 1.0 features the device supports (the in-game device does the same for what packs need)
            VkPhysicalDeviceFeatures2 f2 = VkPhysicalDeviceFeatures2.calloc(s).sType$Default();
            vkGetPhysicalDeviceFeatures2(phys, f2);
            if (features != Features.ALL) { // PACK-FULL: DeviceManager's set (core bits; the 1.1/1.2 bits the pack does not use stay off)
                VkPhysicalDeviceFeatures a = f2.features(), e = VkPhysicalDeviceFeatures.calloc(s);
                e.samplerAnisotropy(a.samplerAnisotropy()).logicOp(a.logicOp()).wideLines(a.wideLines())
                        .multiDrawIndirect(a.multiDrawIndirect()).drawIndirectFirstInstance(a.drawIndirectFirstInstance());
                if (features == Features.GAME)
                    PackDeviceFeatures.apply(a, e);
                f2.features().set(e);
                // DeviceManager also enables shaderDrawParameters (IndirectDrawCaps, Vulkan 1.1 struct) where offered
                VkPhysicalDeviceVulkan11Features q11 = VkPhysicalDeviceVulkan11Features.calloc(s).sType$Default();
                VkPhysicalDeviceFeatures2 probe = VkPhysicalDeviceFeatures2.calloc(s).sType$Default().pNext(q11.address());
                vkGetPhysicalDeviceFeatures2(phys, probe);
                VkPhysicalDeviceVulkan11Features e11 = VkPhysicalDeviceVulkan11Features.calloc(s).sType$Default()
                        .shaderDrawParameters(q11.shaderDrawParameters() && a.multiDrawIndirect());
                f2.pNext(e11.address());
            }
            vertexStores = f2.features().vertexPipelineStoresAndAtomics();
            fragmentStores = f2.features().fragmentStoresAndAtomics();
            independentBlend = f2.features().independentBlend();
            VkDeviceQueueCreateInfo.Buffer qci = VkDeviceQueueCreateInfo.calloc(1, s).sType$Default().queueFamilyIndex(family)
                    .pQueuePriorities(s.floats(1f));
            VkDeviceCreateInfo dci = VkDeviceCreateInfo.calloc(s).sType$Default().pNext(f2.address()).pQueueCreateInfos(qci);
            check(vkCreateDevice(phys, dci, null, pp), "vkCreateDevice");
            device = dev = new VkDevice(pp.get(0), phys, dci);
            vkGetDeviceQueue(device, family, 0, pp);
            queue = new VkQueue(pp.get(0), device);
            LongBuffer lb = s.mallocLong(1);
            check(vkCreateCommandPool(device, VkCommandPoolCreateInfo.calloc(s).sType$Default()
                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT).queueFamilyIndex(family), null, lb), "pool");
            cmdPool = lb.get(0);
            VkCommandBufferAllocateInfo cai = VkCommandBufferAllocateInfo.calloc(s).sType$Default().commandPool(cmdPool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1);
            check(vkAllocateCommandBuffers(device, cai, pp), "cmd");
            cmd = new VkCommandBuffer(pp.get(0), device);
            check(vkCreateFence(device, VkFenceCreateInfo.calloc(s).sType$Default(), null, lb), "fence");
            fence = lb.get(0);
            descPool = createDescriptorPool(device);
            if (this.inFlight > 1) {
                int k = this.inFlight;
                slotCmds = new VkCommandBuffer[k];
                slotFences = new long[k];
                slotPools = new long[k];
                slotPending = new boolean[k];
                slotBufs = new ArrayList<>();
                slotLater = new ArrayList<>();
                PointerBuffer pc = s.mallocPointer(k);
                check(vkAllocateCommandBuffers(device, VkCommandBufferAllocateInfo.calloc(s).sType$Default().commandPool(cmdPool)
                        .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(k), pc), "slot cmds");
                for (int i = 0; i < k; i++) {
                    slotCmds[i] = new VkCommandBuffer(pc.get(i), device);
                    check(vkCreateFence(device, VkFenceCreateInfo.calloc(s).sType$Default(), null, lb), "slot fence");
                    slotFences[i] = lb.get(0);
                    slotPools[i] = createDescriptorPool(device);
                    slotBufs.add(new ArrayList<>());
                    slotLater.add(new ArrayList<>());
                }
            }
            } catch (RuntimeException | Error e) {
                if (dev != null)
                    vkDestroyDevice(dev, null); // children of a half-made device (lavapipe out of memory only) go with it
                if (mp != null)
                    mp.free();
                destroyInstanceObjects();
                throw e;
            }
        }
        initQuad();
    }

    /** messenger before the instance (a live child at vkDestroyInstance is a validation error), then the native callback */
    private void destroyInstanceObjects() {
        if (messenger != 0)
            EXTDebugUtils.vkDestroyDebugUtilsMessengerEXT(instance, messenger, null);
        messenger = 0;
        vkDestroyInstance(instance, null);
        if (messengerCb != null)
            messengerCb.free();
        messengerCb = null;
    }

    static long createDescriptorPool(VkDevice device) {
        try (MemoryStack s = stackPush()) {
            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(4, s);
            sizes.get(0).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(4096);
            sizes.get(1).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(16384);
            sizes.get(2).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(4096);
            sizes.get(3).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1024);
            LongBuffer lb = s.mallocLong(1);
            check(vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo.calloc(s).sType$Default().maxSets(1024).pPoolSizes(sizes),
                    null, lb), "descriptor pool");
            return lb.get(0);
        }
    }

    @Override
    public String name() {
        return "headless:" + deviceName;
    }

    @Override
    protected VkDevice device() {
        return device;
    }

    @Override
    protected VkPhysicalDevice phys() {
        return phys;
    }

    @Override
    protected long pipelineCache() {
        return VK_NULL_HANDLE;
    }

    @Override
    protected VkCommandBuffer cmd() {
        if (inFlight > 1) {
            if (!recording) {
                waitSlot(slot);
                try (MemoryStack s = stackPush()) {
                    vkResetCommandBuffer(slotCmds[slot], 0);
                    vkBeginCommandBuffer(slotCmds[slot], VkCommandBufferBeginInfo.calloc(s).sType$Default().flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT));
                }
                recording = true;
            }
            return slotCmds[slot];
        }
        if (!recording) {
            try (MemoryStack s = stackPush()) {
                vkResetCommandBuffer(cmd, 0);
                vkBeginCommandBuffer(cmd, VkCommandBufferBeginInfo.calloc(s).sType$Default().flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT));
            }
            recording = true;
        }
        return cmd;
    }

    @Override
    protected long descriptorPool() {
        return inFlight > 1 ? slotPools[slot] : descPool;
    }

    /** V-7: the slot's last submission has completed: its fence, then its deferred frees */
    private void waitSlot(int i) {
        if (!slotPending[i])
            return;
        int r = vkWaitForFences(device, slotFences[i], true, 120_000_000_000L);
        if (r != VK_SUCCESS)
            throw new IllegalStateException("replay frame did not finish in 120 s (VkResult " + r + ")");
        slotPending[i] = false;
        for (HostBuf b : slotBufs.get(i))
            freeHostBuffer(b);
        slotBufs.get(i).clear();
        List<Runnable> l = new ArrayList<>(slotLater.get(i));
        slotLater.get(i).clear();
        l.forEach(Runnable::run);
    }

    /** V-7: submit the slot being recorded, no wait; the next frame records into the next slot */
    private void submitSlot() {
        if (!recording)
            return;
        try (MemoryStack s = stackPush()) {
            vkEndCommandBuffer(slotCmds[slot]);
            recording = false;
            vkResetFences(device, slotFences[slot]);
            check(vkQueueSubmit(queue, VkSubmitInfo.calloc(s).sType$Default().pCommandBuffers(s.pointers(slotCmds[slot])), slotFences[slot]), "vkQueueSubmit");
        }
        slotPending[slot] = true;
        lastSubmitted = slot;
        slot = (slot + 1) % inFlight;
    }

    /** V-7: deferred work goes to the slot recording now, else the last submitted one (nothing in flight: run now) */
    private void deferred(Runnable r) {
        int i = recording ? slot : lastSubmitted;
        if (i >= 0 && (recording || slotPending[i]))
            slotLater.get(i).add(r);
        else
            r.run();
    }

    // ------------------------------------------------------------------------------------------------ memory

    private int memoryType(int bits, int flags) {
        for (int i = 0; i < memProps.memoryTypeCount(); i++)
            if ((bits & (1 << i)) != 0 && (memProps.memoryTypes(i).propertyFlags() & flags) == flags)
                return i;
        throw new IllegalStateException("no memory type for bits " + bits + " flags " + flags);
    }

    private long allocate(VkMemoryRequirements req, int flags, String what) {
        if (allocated + req.size() > cap)
            throw new IllegalStateException("replay device memory cap " + (cap >> 20) + " MiB reached allocating " + what);
        try (MemoryStack s = stackPush()) {
            LongBuffer lb = s.mallocLong(1);
            check(vkAllocateMemory(device, VkMemoryAllocateInfo.calloc(s).sType$Default().allocationSize(req.size())
                    .memoryTypeIndex(memoryType(req.memoryTypeBits(), flags)), null, lb), "vkAllocateMemory " + what);
            allocated += req.size();
            return lb.get(0);
        }
    }

    @Override
    protected Object[] allocImage(ImageDesc d, int fmt, int usage, boolean three) {
        try (MemoryStack s = stackPush()) {
            LongBuffer lb = s.mallocLong(1);
            VkImageCreateInfo ci = VkImageCreateInfo.calloc(s).sType$Default().imageType(three ? VK_IMAGE_TYPE_3D : VK_IMAGE_TYPE_2D)
                    .format(fmt).mipLevels(d.mips()).arrayLayers(1).samples(VK_SAMPLE_COUNT_1_BIT).tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(usage).sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            ci.extent().width(d.width()).height(d.height()).depth(d.depth());
            check(vkCreateImage(device, ci, null, lb), "vkCreateImage " + d.name());
            long image = lb.get(0);
            VkMemoryRequirements req = VkMemoryRequirements.calloc(s);
            vkGetImageMemoryRequirements(device, image, req);
            long mem = allocate(req, 0, d.name());
            vkBindImageMemory(device, image, mem, 0);
            return new Object[]{image, new Mem(mem, req.size())};
        }
    }

    @Override
    protected void releaseImage(long image, Object alloc, long[] views) {
        releaseLater(() -> {
            for (long v : views)
                vkDestroyImageView(device, v, null);
            vkDestroyImage(device, image, null);
            Mem m = (Mem) alloc;
            vkFreeMemory(device, m.memory(), null);
            allocated -= m.size();
        });
    }

    @Override
    protected void releaseLater(Runnable r) {
        if (inFlight > 1)
            deferred(r);
        else
            later.add(r);
    }

    @Override
    protected HostBuf hostBuffer(long size, int usage) {
        try (MemoryStack s = stackPush()) {
            LongBuffer lb = s.mallocLong(1);
            check(vkCreateBuffer(device, VkBufferCreateInfo.calloc(s).sType$Default().size(size).usage(usage)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE), null, lb), "vkCreateBuffer");
            long b = lb.get(0);
            VkMemoryRequirements req = VkMemoryRequirements.calloc(s);
            vkGetBufferMemoryRequirements(device, b, req);
            long mem = allocate(req, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, "buffer");
            vkBindBufferMemory(device, b, mem, 0);
            PointerBuffer pp = s.mallocPointer(1);
            vkMapMemory(device, mem, 0, size, 0, pp);
            return new HostBuf(b, new Mem(mem, req.size()), size, MemoryUtil.memByteBuffer(pp.get(0), (int) size));
        }
    }

    @Override
    protected void freeHostBuffer(HostBuf b) {
        Mem m = (Mem) b.alloc();
        vkUnmapMemory(device, m.memory());
        vkDestroyBuffer(device, b.buffer(), null);
        vkFreeMemory(device, m.memory(), null);
        allocated -= m.size();
    }

    @Override
    protected void freeHostBufferAfterFrame(HostBuf b) {
        if (inFlight > 1)
            deferred(() -> freeHostBuffer(b));
        else
            frameBufs.add(b);
    }

    // ------------------------------------------------------------------------------------------------ frame

    private void submitWait() {
        if (inFlight > 1) { // V-7: everything submitted and complete (readback, close)
            submitSlot();
            for (int i = 0; i < inFlight; i++)
                waitSlot(i);
            return;
        }
        if (!recording)
            return;
        try (MemoryStack s = stackPush()) {
            vkEndCommandBuffer(cmd);
            recording = false;
            vkResetFences(device, fence);
            check(vkQueueSubmit(queue, VkSubmitInfo.calloc(s).sType$Default().pCommandBuffers(s.pointers(cmd)), fence), "vkQueueSubmit");
            int r = vkWaitForFences(device, fence, true, 120_000_000_000L);
            if (r != VK_SUCCESS)
                throw new IllegalStateException("replay frame did not finish in 120 s (VkResult " + r + ")");
        }
        for (HostBuf b : frameBufs)
            freeHostBuffer(b);
        frameBufs.clear();
        for (Runnable r : later)
            r.run();
        later.clear();
    }

    /**
     * PACK-FULL harness: false = close() destroys only the backend's own frame objects, not the pack's (images,
     * pipelines, render passes, ...): what the pack owner did not release shows up as a validation leak report at
     * vkDestroyDevice, as in game when nothing released the pack at shutdown.
     */
    public boolean releaseOnClose = true;

    /** PACK-FULL harness: everything recorded is submitted and complete, every deferred release has run */
    public void submitAndDrain() {
        submitWait();
        if (inFlight == 1)
            return;
        for (int i = 0; i < inFlight; i++)
            waitSlot(i);
    }

    @Override
    public void beginFrame() {
        if (inFlight > 1) { // V-7: setup work stays in this frame's buffer (as in game); the slot's last use has completed
            cmd();
            vkResetDescriptorPool(device, slotPools[slot], 0);
            resetUboRing(slot);
            return;
        }
        submitWait(); // setup work first, so the descriptor pool reset below cannot hit in-flight sets
        vkResetDescriptorPool(device, descPool, 0);
        resetUboRing(0);
        cmd();
        // P3-8: per-pass GPU timestamps (lavapipe: RELATIVE costs only, a CPU rasteriser says nothing about a GPU)
        if (queryPool == 0)
            initQueries();
        if (queryPool != 0) {
            vkCmdResetQueryPool(cmd, queryPool, 0, MAXQ);
            qNames.clear();
        }
    }

    @Override
    public void endFrame() {
        if (inFlight > 1) {
            submitSlot();
            return;
        }
        submitWait();
        readQueries();
    }

    // ---- timestamps (P3-8)
    private static final int MAXQ = 256;
    private long queryPool;
    private double periodNs;
    private boolean noQueries;
    private final List<String> qNames = new ArrayList<>();
    /** pass -> {sum ms, samples} over all frames (relative costs) */
    public final java.util.Map<String, double[]> passTimes = new java.util.LinkedHashMap<>();

    private void initQueries() {
        if (noQueries)
            return;
        try (MemoryStack s = stackPush()) {
            VkPhysicalDeviceProperties pp = VkPhysicalDeviceProperties.calloc(s);
            vkGetPhysicalDeviceProperties(phys, pp);
            periodNs = pp.limits().timestampPeriod();
            java.nio.LongBuffer lb = s.mallocLong(1);
            if (periodNs <= 0 || !pp.limits().timestampComputeAndGraphics()
                    || vkCreateQueryPool(device, VkQueryPoolCreateInfo.calloc(s).sType$Default().queryType(VK_QUERY_TYPE_TIMESTAMP).queryCount(MAXQ), null, lb) != VK_SUCCESS) {
                noQueries = true;
                return;
            }
            queryPool = lb.get(0);
        }
    }

    @Override
    public void timeBegin(String pass) {
        if (queryPool == 0 || qNames.size() * 2 + 2 > MAXQ)
            return;
        vkCmdWriteTimestamp(cmd(), VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, queryPool, qNames.size() * 2);
        qNames.add(pass);
    }

    @Override
    public void timeEnd(String pass) {
        if (queryPool == 0 || qNames.isEmpty() || !qNames.get(qNames.size() - 1).equals(pass))
            return;
        vkCmdWriteTimestamp(cmd(), VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, queryPool, qNames.size() * 2 - 1);
    }

    private void readQueries() {
        if (queryPool == 0 || qNames.isEmpty())
            return;
        int n = qNames.size() * 2;
        try (MemoryStack s = stackPush()) {
            java.nio.LongBuffer res = s.mallocLong(n);
            if (vkGetQueryPoolResults(device, queryPool, 0, n, res, 8, VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WAIT_BIT) == VK_SUCCESS)
                for (int i = 0; i < qNames.size(); i++) {
                    long a = res.get(2 * i), b = res.get(2 * i + 1);
                    if (b >= a) {
                        double[] acc = passTimes.computeIfAbsent(qNames.get(i), k -> new double[2]);
                        acc[0] += (b - a) * periodNs / 1e6;
                        acc[1]++;
                    }
                }
        }
        qNames.clear();
    }

    /** "pass share%" table, most expensive first (relative to the summed pass time) */
    public String passTimeTable() {
        double total = 0;
        for (double[] v : passTimes.values())
            total += v[0] / Math.max(1, v[1]);
        StringBuilder sb = new StringBuilder();
        double t = total;
        passTimes.entrySet().stream().sorted((x, y) -> Double.compare(y.getValue()[0] / y.getValue()[1], x.getValue()[0] / x.getValue()[1]))
                .forEach(e -> sb.append(String.format("  %-34s %5.1f%%%n", e.getKey(), 100 * e.getValue()[0] / e.getValue()[1] / t)));
        return sb.toString();
    }

    @Override
    public float[] readback(int image) {
        submitWait();
        Img im = img(image);
        long texels = (long) im.d.width() * im.d.height() * im.d.depth();
        int bpt = TexelCodec.bytes(im.d.vkFormat());
        HostBuf st = hostBuffer(Math.max(16, texels * bpt), VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        try (MemoryStack s = stackPush()) {
            VkBufferImageCopy.Buffer r = VkBufferImageCopy.calloc(1, s);
            r.imageSubresource().aspectMask(im.aspect).mipLevel(0).baseArrayLayer(0).layerCount(1);
            r.imageExtent().width(im.d.width()).height(im.d.height()).depth(im.d.depth());
            vkCmdCopyImageToBuffer(cmd(), im.image, VK_IMAGE_LAYOUT_GENERAL, st.buffer(), r);
        }
        submitWait();
        float[] out = TexelCodec.decode(im.d.vkFormat(), st.mapped(), (int) texels);
        freeHostBuffer(st);
        return out;
    }

    public long allocatedBytes() {
        return allocated;
    }

    @Override
    public void close() {
        submitWait();
        if (queryPool != 0)
            vkDestroyQueryPool(device, queryPool, null);
        queryPool = 0;
        if (releaseOnClose)
            destroyShared();
        submitWait();
        for (Runnable r : later)
            r.run();
        later.clear();
        // PACK-FULL: submitWait returns early when nothing is recording, so host buffers freed "after the frame" since
        // the last submit (staging, SSBOs) were never released: packBarrierBench leaked 18 objects at vkDestroyDevice
        for (HostBuf b : frameBufs)
            freeHostBuffer(b);
        frameBufs.clear();
        vkDeviceWaitIdle(device);
        if (inFlight > 1)
            for (int i = 0; i < inFlight; i++) {
                vkDestroyDescriptorPool(device, slotPools[i], null);
                vkDestroyFence(device, slotFences[i], null);
            }
        vkDestroyDescriptorPool(device, descPool, null);
        vkDestroyFence(device, fence, null);
        vkDestroyCommandPool(device, cmdPool, null);
        vkDestroyDevice(device, null);
        destroyInstanceObjects();
        memProps.free();
    }
}
