// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.run.SessionCancel;
import cc.jumpkick.run.JkThreads;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Proves that a {@code where()}-bound {@link Session} — and its per-request cancel token — propagates
 * to tasks dispatched to the shared {@link JkThreads} pools via the {@code ContextPropagator} seam
 * that {@link SessionContext}'s static initializer installs. Without propagation a pool worker would
 * read the process-static session instead, so two concurrent requests could not be isolated.
 */
class SessionPropagationTest {

    @AfterEach
    void tidy() {
        SessionContext.reset();
        SessionCancel.bind(null); // undo any probe binding this test installed
    }

    private static Session sessionAt(String dir) {
        return Session.defaults().withWorkingDir(Path.of(dir).toAbsolutePath());
    }

    @Test
    void io_pool_task_sees_the_where_bound_session_not_the_static() throws Exception {
        Session installed = sessionAt("/tmp/jk-static");
        Session scoped = sessionAt("/tmp/jk-io-scoped");
        SessionContext.install(installed);

        Session seen =
                SessionContext.where(
                        scoped,
                        () ->
                                CompletableFuture.supplyAsync(SessionContext::current, JkThreads.io())
                                        .get());

        assertThat(seen).isSameAs(scoped);
        assertThat(SessionContext.current()).isSameAs(installed); // static intact after the scope
    }

    @Test
    void cpu_pool_task_sees_the_where_bound_session_not_the_static() throws Exception {
        Session installed = sessionAt("/tmp/jk-static");
        Session scoped = sessionAt("/tmp/jk-cpu-scoped");
        SessionContext.install(installed);

        Session seen =
                SessionContext.where(
                        scoped,
                        () ->
                                CompletableFuture.supplyAsync(SessionContext::current, JkThreads.cpu())
                                        .get());

        assertThat(seen).isSameAs(scoped);
    }

    @Test
    void two_concurrent_scopes_each_dispatch_and_see_their_own_session() throws Exception {
        Session a = sessionAt("/tmp/jk-conc-a");
        Session b = sessionAt("/tmp/jk-conc-b");

        // Barrier of 2 so BOTH pool tasks are in-flight together when each samples current():
        // this rules out sequential reuse of a single worker masking a leak.
        CyclicBarrier bothInFlight = new CyclicBarrier(2);

        Future<Session> fa =
                SessionContext.where(a, () -> dispatchSampling(bothInFlight));
        Future<Session> fb =
                SessionContext.where(b, () -> dispatchSampling(bothInFlight));

        assertThat(fa.get()).isSameAs(a);
        assertThat(fb.get()).isSameAs(b);
        assertThat(fa.get().workingDir()).isNotEqualTo(fb.get().workingDir());
    }

    /** Submit (from inside a where scope) a pool task that rendezvouses, then reads current(). */
    private static Future<Session> dispatchSampling(CyclicBarrier barrier) {
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        barrier.await(); // both tasks now concurrently live
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    return SessionContext.current();
                },
                JkThreads.io());
    }

    @Test
    void without_a_scope_a_pool_task_sees_the_installed_static() throws Exception {
        Session installed = sessionAt("/tmp/jk-baseline");
        SessionContext.install(installed);

        Session seen =
                CompletableFuture.supplyAsync(SessionContext::current, JkThreads.io()).get();

        // Baseline / CLI single-session behavior: the wrapper captures current() (the static) on the
        // submitting thread and rebinds the same session on the worker — no change from before.
        assertThat(seen).isSameAs(installed);
    }

    @Test
    void per_session_cancel_token_propagates_to_pool_tasks() throws Exception {
        // Mirror the engine's binding: SessionCancel reads current().cancelled(). With the session
        // now propagating to workers, a scoped session's cancel reaches async pool tasks too
        // (this is the L7 async cancel-propagation fix).
        SessionCancel.bind(() -> SessionContext.current().cancelled());

        Session cancelled = sessionAt("/tmp/jk-cancel");
        cancelled.cancel().cancel(); // signal this session's token before dispatch

        boolean seenCancelled =
                SessionContext.where(
                        cancelled,
                        () ->
                                CompletableFuture.supplyAsync(SessionCancel::cancelled, JkThreads.io())
                                        .get());

        assertThat(seenCancelled).isTrue();

        // And an un-cancelled session's task does not observe cancellation.
        Session live = sessionAt("/tmp/jk-live");
        boolean seenLive =
                SessionContext.where(
                        live,
                        () ->
                                CompletableFuture.supplyAsync(SessionCancel::cancelled, JkThreads.io())
                                        .get());
        assertThat(seenLive).isFalse();
    }

    @Test
    void executor_lifecycle_methods_still_work_through_the_wrapper() {
        ExecutorService io = JkThreads.io();
        // The wrapper must not break the shared pool's liveness contract.
        assertThat(io.isShutdown()).isFalse();
        assertThat(io.isTerminated()).isFalse();
    }
}
