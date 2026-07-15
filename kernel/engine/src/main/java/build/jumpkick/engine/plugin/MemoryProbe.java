// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.engine.plugin;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Container-aware host memory, read from the OS rather than a JVM bean so it works the same in the
 * native {@code jk} binary as on a hosted JVM.
 *
 * <p>On Linux it reads {@code /proc/meminfo} and the cgroup memory controller (v2 {@code
 * memory.max}/{@code memory.current}, v1 {@code memory.limit_in_bytes}/{@code
 * memory.usage_in_bytes}), so a build running under a container limit sees the limit — not the
 * host's RAM. On macOS it calls {@code host_statistics64(2)} directly via FFM (the same Mach call
 * {@code vm_stat} itself shells out to — see {@link #fromMachHostStatistics64()}): {@code
 * com.sun.management}'s free-memory figure only counts truly-idle pages, not the inactive/purgeable
 * pages macOS's VM keeps stocked with reclaimable file cache, so on a machine that's been up a while
 * it reports a few hundred MiB free out of tens of GiB — starving {@code HeapPlan} into handing
 * worker JVMs a near-zero heap. Elsewhere (Windows, or if the Mach call fails) it falls back to the
 * {@code com.sun.management} OS bean. Every probe is best-effort and never throws; on total failure
 * it reports a conservative {@link #FALLBACK_TOTAL}.
 */
public final class MemoryProbe {

    private MemoryProbe() {}

    /** Conservative last-resort total when nothing else can be read. */
    static final long FALLBACK_TOTAL = 2L << 30; // 2 GiB

    /** A cgroup limit at/above this is treated as "unlimited" (kernel sentinel). */
    static final long CGROUP_UNLIMITED = Long.MAX_VALUE / 2;

    private static final Path PROC_MEMINFO = Path.of("/proc/meminfo");
    private static final Path PROC_SELF_STATUS = Path.of("/proc/self/status");
    private static final Path CGROUP2_MAX = Path.of("/sys/fs/cgroup/memory.max");
    private static final Path CGROUP2_CURRENT = Path.of("/sys/fs/cgroup/memory.current");
    private static final Path CGROUP1_LIMIT = Path.of("/sys/fs/cgroup/memory/memory.limit_in_bytes");
    private static final Path CGROUP1_USAGE = Path.of("/sys/fs/cgroup/memory/memory.usage_in_bytes");

    /** {@code total} = physical/limit RAM; {@code available} = what we can still allocate. */
    public record Memory(long totalBytes, long availableBytes) {}

    private static volatile Memory cached;

    /** Probe once and cache — memory headroom is read at planning time and reused. */
    public static Memory probe() {
        Memory m = cached;
        if (m == null) {
            synchronized (MemoryProbe.class) {
                if (cached == null) cached = measure();
                m = cached;
            }
        }
        return m;
    }

    /**
     * This process's resident set size — best-effort, uncached (callers want "now", not
     * planning-time). Linux reads {@code VmRSS} from {@code /proc/self/status} (same format as
     * {@code /proc/meminfo}); {@code -1} where the OS doesn't expose it (macOS, Windows).
     */
    public static long ownRssBytes() {
        try {
            if (Files.isReadable(PROC_SELF_STATUS)) {
                return meminfoValueBytes(Files.readString(PROC_SELF_STATUS), "VmRSS");
            }
        } catch (IOException | RuntimeException ignored) {
            // best-effort: fall through to unknown
        }
        return -1;
    }

    private static Memory measure() {
        long hostTotal = -1;
        long hostAvail = -1;
        try {
            if (Files.isReadable(PROC_MEMINFO)) {
                String meminfo = Files.readString(PROC_MEMINFO);
                hostTotal = meminfoValueBytes(meminfo, "MemTotal");
                hostAvail = meminfoValueBytes(meminfo, "MemAvailable");
            }
        } catch (IOException | RuntimeException ignored) {
            // fall through to the bean fallback below
        }

        long cgLimit = readLong(CGROUP2_MAX);
        if (cgLimit < 0) cgLimit = readLong(CGROUP1_LIMIT);
        long cgUsage = readLong(CGROUP2_CURRENT);
        if (cgUsage < 0) cgUsage = readLong(CGROUP1_USAGE);

        long total = hostTotal;
        long available = hostAvail;

        // A real cgroup cap (smaller than the host) overrides both numbers: the
        // build can only use what the container allows.
        if (cgLimit > 0 && cgLimit < CGROUP_UNLIMITED && (total <= 0 || cgLimit < total)) {
            total = cgLimit;
            if (cgUsage >= 0) {
                long cgAvail = cgLimit - cgUsage;
                available = available > 0 ? Math.min(available, cgAvail) : cgAvail;
            }
        }

        if (total <= 0 || available <= 0) {
            Memory fallback = isMac() ? fromMachHostStatistics64() : null;
            if (fallback == null) fallback = fromBean();
            if (total <= 0) total = fallback.totalBytes();
            if (available <= 0) available = fallback.availableBytes();
        }
        // Clamp so available never exceeds total and neither is non-positive.
        if (total <= 0) total = FALLBACK_TOTAL;
        if (available <= 0) available = total;
        if (available > total) available = total;
        return new Memory(total, available);
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }

    /** {@code mach/host_info.h}: the {@code HOST_VM_INFO64} flavor selector for {@code host_statistics64}. */
    private static final int HOST_VM_INFO64 = 4;

    /** {@code HOST_VM_INFO64_COUNT}: {@code sizeof(vm_statistics64_data_t) / sizeof(integer_t)}. */
    private static final int HOST_VM_INFO64_COUNT = 38;

    /** {@code KERN_SUCCESS} in {@code mach/kern_return.h}. */
    private static final int KERN_SUCCESS = 0;

    // Byte offsets into vm_statistics64_data_t (mach/host_info.h) of the fields we read: natural_t
    // (4-byte) fields packed until a uint64_t field forces 8-byte alignment. Verified against this
    // struct's known layout and cross-checked against `vm_stat`'s own output (see MemoryProbeTest).
    private static final long FREE_COUNT_OFFSET = 0;
    private static final long INACTIVE_COUNT_OFFSET = 8;
    private static final long PURGEABLE_COUNT_OFFSET = 88;
    private static final long SPECULATIVE_COUNT_OFFSET = 92;

    private static final FunctionDescriptor MACH_HOST_SELF = FunctionDescriptor.of(ValueLayout.JAVA_INT);
    private static final FunctionDescriptor HOST_PAGE_SIZE =
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS);
    private static final FunctionDescriptor HOST_STATISTICS64 = FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS);

    /**
     * macOS: reclaimable pages (free + inactive + speculative + purgeable) times the page size,
     * matching how Activity Monitor and other macOS tools report "available" memory — as opposed to
     * {@code com.sun.management}'s free-memory figure, which counts only truly-idle pages. Reads
     * {@code vm_statistics64_data_t} straight from the kernel via the same {@code
     * mach_host_self}/{@code host_statistics64} calls {@code vm_stat} itself makes — no subprocess, no
     * text parsing. {@code null} on any failure (missing symbol, non-zero {@code kern_return_t}, no
     * native access), so the caller falls back to {@link #fromBean()}.
     */
    private static Memory fromMachHostStatistics64() {
        try {
            Linker linker = Linker.nativeLinker();
            var lookup = linker.defaultLookup();
            MethodHandle machHostSelf =
                    linker.downcallHandle(lookup.find("mach_host_self").orElseThrow(), MACH_HOST_SELF);
            MethodHandle hostPageSize =
                    linker.downcallHandle(lookup.find("host_page_size").orElseThrow(), HOST_PAGE_SIZE);
            MethodHandle hostStatistics64 =
                    linker.downcallHandle(lookup.find("host_statistics64").orElseThrow(), HOST_STATISTICS64);

            try (Arena arena = Arena.ofConfined()) {
                int hostPort = (int) machHostSelf.invokeExact();

                MemorySegment pageSizeOut = arena.allocate(ValueLayout.JAVA_LONG);
                if ((int) hostPageSize.invokeExact(hostPort, pageSizeOut) != KERN_SUCCESS) return null;
                long pageSize = pageSizeOut.get(ValueLayout.JAVA_LONG, 0);

                MemorySegment info = arena.allocate((long) HOST_VM_INFO64_COUNT * Integer.BYTES);
                MemorySegment countInOut = arena.allocate(ValueLayout.JAVA_INT);
                countInOut.set(ValueLayout.JAVA_INT, 0, HOST_VM_INFO64_COUNT);
                if ((int) hostStatistics64.invokeExact(hostPort, HOST_VM_INFO64, info, countInOut) != KERN_SUCCESS) {
                    return null;
                }

                long free = pages(info, FREE_COUNT_OFFSET);
                long inactive = pages(info, INACTIVE_COUNT_OFFSET);
                long speculative = pages(info, SPECULATIVE_COUNT_OFFSET);
                long purgeable = pages(info, PURGEABLE_COUNT_OFFSET);
                long available = (free + inactive + speculative + purgeable) * pageSize;

                long total = ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean())
                        .getTotalMemorySize();
                return available > 0 ? new Memory(total, available) : null;
            }
        } catch (Throwable t) {
            // Best-effort: symbol missing, native access not granted, bad kern_return_t — fall back.
            return null;
        }
    }

    /** {@code natural_t} (unsigned 32-bit) page count at {@code byteOffset} into a vm_statistics64_data_t. */
    private static long pages(MemorySegment info, long byteOffset) {
        return Integer.toUnsignedLong(info.get(ValueLayout.JAVA_INT, byteOffset));
    }

    /** {@code com.sun.management} OS bean fallback (Windows, or the Mach call unavailable). */
    private static Memory fromBean() {
        try {
            var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            long total = os.getTotalMemorySize();
            long free = os.getFreeMemorySize();
            return new Memory(total, free > 0 ? free : total);
        } catch (Throwable t) {
            // Bean unavailable (e.g. native-image without monitoring) — last resort.
            return new Memory(FALLBACK_TOTAL, FALLBACK_TOTAL);
        }
    }

    /** Parse a {@code /proc/meminfo} line ("MemTotal: 16004 kB") to bytes; {@code -1} if absent. */
    static long meminfoValueBytes(String meminfo, String key) {
        for (String line : meminfo.split("\n")) {
            if (!line.startsWith(key + ":")) continue;
            String rest = line.substring(key.length() + 1).trim(); // "16004 kB"
            String[] parts = rest.split("\\s+");
            try {
                long value = Long.parseLong(parts[0]);
                // meminfo is in kB unless a value carries no unit (rare); assume kB.
                return parts.length > 1 ? value * 1024 : value;
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return -1;
    }

    /** Read a single-number cgroup file; {@code -1} for absent/"max"/unparseable. */
    private static long readLong(Path file) {
        try {
            if (!Files.isReadable(file)) return -1;
            String s = Files.readString(file).trim();
            if (s.isEmpty() || s.equals("max")) return -1;
            return Long.parseLong(s);
        } catch (IOException | RuntimeException e) {
            return -1;
        }
    }
}
