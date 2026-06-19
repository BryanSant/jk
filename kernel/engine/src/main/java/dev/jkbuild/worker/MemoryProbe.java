// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.worker;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Container-aware host memory, read from the OS rather than a JVM bean so it
 * works the same in the native {@code jk} binary as on a hosted JVM.
 *
 * <p>On Linux it reads {@code /proc/meminfo} and the cgroup memory controller
 * (v2 {@code memory.max}/{@code memory.current}, v1
 * {@code memory.limit_in_bytes}/{@code memory.usage_in_bytes}), so a build
 * running under a container limit sees the limit — not the host's RAM.
 * Elsewhere (macOS, no {@code /proc}) it falls back to the
 * {@code com.sun.management} OS bean. Every probe is best-effort and never
 * throws; on total failure it reports a conservative {@link #FALLBACK_TOTAL}.
 */
public final class MemoryProbe {

    private MemoryProbe() {}

    /** Conservative last-resort total when nothing else can be read. */
    static final long FALLBACK_TOTAL = 2L << 30;   // 2 GiB
    /** A cgroup limit at/above this is treated as "unlimited" (kernel sentinel). */
    static final long CGROUP_UNLIMITED = Long.MAX_VALUE / 2;

    private static final Path PROC_MEMINFO = Path.of("/proc/meminfo");
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
            Memory bean = fromBean();
            if (total <= 0) total = bean.totalBytes();
            if (available <= 0) available = bean.availableBytes();
        }
        // Clamp so available never exceeds total and neither is non-positive.
        if (total <= 0) total = FALLBACK_TOTAL;
        if (available <= 0) available = total;
        if (available > total) available = total;
        return new Memory(total, available);
    }

    /** {@code com.sun.management} OS bean fallback (macOS / no {@code /proc}). */
    private static Memory fromBean() {
        try {
            var os = (com.sun.management.OperatingSystemMXBean)
                    ManagementFactory.getOperatingSystemMXBean();
            long total = os.getTotalMemorySize();
            long free = os.getFreeMemorySize();
            return new Memory(total, free > 0 ? free : total);
        } catch (Throwable t) {
            // Bean unavailable (e.g. native-image without monitoring) — last resort.
            return new Memory(FALLBACK_TOTAL, FALLBACK_TOTAL);
        }
    }

    /** Parse a {@code /proc/meminfo} line ("MemTotal:  16004 kB") to bytes; {@code -1} if absent. */
    static long meminfoValueBytes(String meminfo, String key) {
        for (String line : meminfo.split("\n")) {
            if (!line.startsWith(key + ":")) continue;
            String rest = line.substring(key.length() + 1).trim();   // "16004 kB"
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
