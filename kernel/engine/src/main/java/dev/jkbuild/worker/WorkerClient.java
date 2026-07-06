// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.worker;

import dev.jkbuild.plugin.protocol.Ndjson;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Host-side driver for a forked plugin worker. Launches the command, splits the worker's NDJSON
 * protocol stream ({@code ##PREFIX:{...}}) by its message-type discriminator, and dispatches each
 * typed message to a registered handler — collapsing the fork + read-loop + {@code switch (t)} that
 * every launch site used to hand-roll directly over {@link WorkerProcess}.
 *
 * <h2>The canonical envelope</h2>
 *
 * Every protocol line is a flat JSON object {@code {"t":"<type>", ...fields}} whose {@code "t"} field
 * names the message type; the terminal outcome is conventionally {@code t:"result"} (or a
 * plugin-specific summary such as {@code done}). The host registers one handler per type:
 *
 * <pre>{@code
 * int exit = new WorkerClient("##JKJC:")
 *         .on("diag",   json -> diagnostics.add(...))
 *         .on("result", json -> status[0] = Ndjson.str(json, "status"))
 *         .run(command);
 * }</pre>
 *
 * <p>The test runner predates this convention and keys its events on {@code "e"} with a two-way pull
 * protocol; construct with {@link #WorkerClient(String, String)} to use a different discriminator and
 * {@link #converse} for the pull loop.
 */
public final class WorkerClient {

    /** The canonical message-type discriminator field name. */
    public static final String TYPE = "t";

    private final String prefix;
    private final String typeKey;
    private final Map<String, Consumer<String>> handlers = new HashMap<>();
    private Consumer<String> onOther;
    private Consumer<String> passthrough;

    /** A client for a worker using the canonical {@code "t"} discriminator and the given prefix. */
    public WorkerClient(String prefix) {
        this(prefix, TYPE);
    }

    /** A client keying messages on {@code typeKey} (e.g. the test runner's {@code "e"}). */
    public WorkerClient(String prefix, String typeKey) {
        this.prefix = prefix;
        this.typeKey = typeKey;
    }

    /** Register the handler for protocol messages whose type equals {@code type}. */
    public WorkerClient on(String type, Consumer<String> handler) {
        handlers.put(type, handler);
        return this;
    }

    /** Handler for protocol messages with no type-specific handler (default: dropped). */
    public WorkerClient onOther(Consumer<String> handler) {
        this.onOther = handler;
        return this;
    }

    /** Handler for non-protocol (passthrough) lines the tool writes to stdout (default: dropped). */
    public WorkerClient passthrough(Consumer<String> handler) {
        this.passthrough = handler;
        return this;
    }

    /** Fork {@code command}, dispatch its protocol stream, and return the process exit code. */
    public int run(List<String> command) throws IOException, InterruptedException {
        return WorkerProcess.run(command, prefix, this::dispatch, passthrough);
    }

    /** As {@link #run(List)}, adding {@code extraEnv} to the child's environment. */
    public int run(List<String> command, Map<String, String> extraEnv) throws IOException, InterruptedException {
        return WorkerProcess.run(command, extraEnv, prefix, this::dispatch, passthrough);
    }

    /**
     * Two-way pull protocol: each protocol message is delivered to {@code onMessage} with a {@link
     * WorkerProcess.Conversation} for sending commands back to the worker's stdin. The registered
     * {@link #on} handlers are not used in this mode (the caller dispatches on the message itself).
     */
    public int converse(List<String> command, BiConsumer<String, WorkerProcess.Conversation> onMessage)
            throws IOException, InterruptedException {
        return WorkerProcess.converse(command, prefix, onMessage, passthrough);
    }

    private void dispatch(String json) {
        String t = Ndjson.str(json, typeKey);
        Consumer<String> handler = t != null ? handlers.get(t) : null;
        if (handler != null) {
            handler.accept(json);
        } else if (onOther != null) {
            onOther.accept(json);
        }
    }
}
