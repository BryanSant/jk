// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.util.Hashing;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Stream-into-CAS for an action's output directory while another thread (typically a {@code javac}
 * subprocess) is still writing to it.
 *
 * <p>Polls {@code outputDir} on a background thread. When a file's {@code (size, mtime)} matches
 * across two consecutive polls — i.e. it has been stable for at least one poll interval — hash it,
 * copy it into the CAS (never link — see Cas.putFile), and record the {@code (relPath → hex)} mapping. By the time the
 * compile finishes, most outputs are already in the CAS and the manifest write just needs to
 * consult the precomputed map.
 *
 * <p>{@link #finish} stops the poller and runs a final correctness pass: any file whose {@code
 * (size, mtime)} has drifted since we processed it — or that we missed entirely — gets re-hashed
 * and re-linked. So the pre-processing is a best-effort optimisation; the final pass is what the
 * action record actually trusts.
 *
 * <p>Magnitude check: for a typical 1000-file Java compile, the CAS write costs about 60ms total (5
 * MB of bytecode, SHA-256 + copy). Streaming this in parallel with a 5–30 second compile saves
 * ~60ms. Not transformative. The architecture matters more than the millis — future post-compile
 * steps (SBOM extraction, ABI hashing for Zinc-style incremental, signature analysis) plug into the
 * same "background work during compile" slot.
 */
public final class CasPrewriter implements AutoCloseable {

    private static final long POLL_INTERVAL_MILLIS = 100;

    private final Cas cas;
    private final Path outputDir;
    private final ScheduledExecutorService scheduler;
    private final ConcurrentMap<Path, Snapshot> tracked = new ConcurrentHashMap<>();
    private final ConcurrentMap<Path, Processed> processed = new ConcurrentHashMap<>();
    private volatile boolean running = true;

    private CasPrewriter(Cas cas, Path outputDir) {
        this.cas = cas;
        this.outputDir = outputDir;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "jk-cas-prewriter");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Start a prewriter watching {@code outputDir}. The caller's responsibility to call {@link
     * #finish} before reading the result — {@code finish} also stops the background poller.
     */
    public static CasPrewriter watching(Cas cas, Path outputDir) {
        CasPrewriter p = new CasPrewriter(cas, outputDir);
        p.scheduler.scheduleWithFixedDelay(
                p::pollOnce, POLL_INTERVAL_MILLIS, POLL_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
        return p;
    }

    /**
     * Stop polling and run the final correctness pass. Returns the {@code (relPath → hex)} map ready
     * for {@link ActionCache#storeWithOutputs}.
     */
    public Map<String, String> finish() throws IOException {
        running = false;
        scheduler.shutdown();
        try {
            scheduler.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        Map<String, String> outputs = new TreeMap<>();
        if (!Files.exists(outputDir)) return outputs;
        try (Stream<Path> stream = Files.walk(outputDir)) {
            for (Path file : (Iterable<Path>) stream::iterator) {
                if (!Files.isRegularFile(file)) continue;
                if (FreshnessStamp.isStampFile(file.getFileName().toString())) continue;

                String relPath = outputDir.relativize(file).toString().replace(File.separatorChar, '/');
                long size = Files.size(file);
                long mtime = Files.getLastModifiedTime(file).toMillis();

                Processed pre = processed.get(file);
                if (pre != null && pre.size == size && pre.mtime == mtime) {
                    // Pre-processing caught this one and its content hasn't
                    // changed — reuse the cached hex.
                    outputs.put(relPath, pre.hex);
                } else {
                    // Either missed by the poller, or modified after we saw
                    // it. Hash + link now.
                    outputs.put(relPath, hashAndLink(file));
                }
            }
        }
        return outputs;
    }

    @Override
    public void close() {
        if (running) {
            running = false;
            scheduler.shutdownNow();
        }
    }

    // --- polling ---------------------------------------------------------

    private void pollOnce() {
        if (!running) return;
        if (!Files.isDirectory(outputDir)) return;
        try (Stream<Path> stream = Files.walk(outputDir)) {
            for (Path file : (Iterable<Path>) stream::iterator) {
                if (!Files.isRegularFile(file)) continue;
                if (FreshnessStamp.isStampFile(file.getFileName().toString())) continue;
                if (processed.containsKey(file)) continue;
                handleCandidate(file);
            }
        } catch (IOException ignored) {
            // Polling is best-effort — a transient walk failure just delays
            // processing until the next tick or the final pass.
        }
    }

    /**
     * Two-poll stability rule: if a file's (size, mtime) matches what we saw last poll, it's been
     * quiet for at least one interval — safe to hash. Otherwise update the snapshot and revisit next
     * tick.
     */
    private void handleCandidate(Path file) {
        try {
            long size = Files.size(file);
            long mtime = Files.getLastModifiedTime(file).toMillis();
            Snapshot prev = tracked.get(file);
            if (prev != null && prev.size == size && prev.mtime == mtime) {
                String hex = hashAndLink(file);
                processed.put(file, new Processed(hex, size, mtime));
                tracked.remove(file);
            } else {
                tracked.put(file, new Snapshot(size, mtime));
            }
        } catch (IOException ignored) {
            // Skip; either the file vanished mid-poll or we hit a permission
            // hiccup. The final pass will pick it up.
        }
    }

    private String hashAndLink(Path file) throws IOException {
        // Streamed hash + hard-link — the file's bytes never land in the heap.
        String hex = Hashing.sha256Hex(file);
        cas.putFile(file, hex);
        return hex;
    }

    private record Snapshot(long size, long mtime) {}

    private record Processed(String hex, long size, long mtime) {}
}
