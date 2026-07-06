// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link SessionContext} scoped-value semantics (two builds in one JVM) and the per-session
 * {@link Session.CancelToken}.
 */
class SessionScopeTest {

    @AfterEach
    void tidy() {
        SessionContext.reset();
    }

    @Test
    void concurrent_scopes_do_not_clobber_each_other() throws Exception {
        Session a = Session.defaults().withWorkingDir(Path.of("/tmp/jk-a").toAbsolutePath());
        Session b = Session.defaults().withWorkingDir(Path.of("/tmp/jk-b").toAbsolutePath());

        // Barrier of 3: both worker threads and the test thread rendezvous so we KNOW both
        // scopes are live simultaneously when each thread samples its own current().
        CyclicBarrier bothInside = new CyclicBarrier(3);
        AtomicReference<Session> seenByA = new AtomicReference<>();
        AtomicReference<Session> seenByB = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread ta = new Thread(() -> SessionContext.runWhere(a, () -> await(bothInside, seenByA, failure)), "sess-a");
        Thread tb = new Thread(() -> SessionContext.runWhere(b, () -> await(bothInside, seenByB, failure)), "sess-b");

        ta.start();
        tb.start();
        bothInside.await(); // release both while inside their scopes
        ta.join();
        tb.join();

        assertThat(failure.get()).isNull();
        // Each thread observed ITS OWN session — no cross-thread clobber.
        assertThat(seenByA.get()).isSameAs(a);
        assertThat(seenByB.get()).isSameAs(b);
        assertThat(seenByA.get().workingDir()).isEqualTo(a.workingDir());
        assertThat(seenByB.get().workingDir()).isEqualTo(b.workingDir());
        assertThat(seenByA.get().workingDir()).isNotEqualTo(seenByB.get().workingDir());

        // Outside any scope we are back to the process static (defaults after tidy/no install).
        assertThat(SessionContext.current().workingDir()).isNotEqualTo(a.workingDir());
        assertThat(SessionContext.current().workingDir()).isNotEqualTo(b.workingDir());
    }

    private static void await(CyclicBarrier barrier, AtomicReference<Session> sink, AtomicReference<Throwable> failure) {
        try {
            barrier.await(); // all three meet: both scopes are now concurrently live
            sink.set(SessionContext.current());
        } catch (Throwable t) {
            failure.set(t);
        }
    }

    @Test
    void falls_back_to_installed_static_when_no_scope_bound() {
        Session installed = Session.defaults().withWorkingDir(Path.of("/tmp/jk-installed").toAbsolutePath());
        SessionContext.install(installed);

        assertThat(SessionContext.current()).isSameAs(installed);
    }

    @Test
    void scope_shadows_the_installed_static() throws Exception {
        Session installed = Session.defaults().withWorkingDir(Path.of("/tmp/jk-installed").toAbsolutePath());
        Session scoped = Session.defaults().withWorkingDir(Path.of("/tmp/jk-scoped").toAbsolutePath());
        SessionContext.install(installed);

        Session inside = SessionContext.where(scoped, SessionContext::current);

        assertThat(inside).isSameAs(scoped); // scoped binding wins over the static
        assertThat(SessionContext.current()).isSameAs(installed); // static intact after the scope closes
    }

    @Test
    void reset_restores_defaults() {
        SessionContext.install(Session.defaults().withWorkingDir(Path.of("/tmp/jk-x").toAbsolutePath()));
        SessionContext.reset();

        assertThat(SessionContext.current().workingDir())
                .isEqualTo(Session.defaults().workingDir());
    }

    @Test
    void fresh_session_has_a_live_cancel_token() {
        Session s = Session.defaults();

        assertThat(s.cancelled()).isFalse();
        s.cancel().cancel();
        assertThat(s.cancelled()).isTrue();
    }

    @Test
    void none_token_is_inert() {
        assertThat(Session.CancelToken.NONE.cancelled()).isFalse();
        Session.CancelToken.NONE.cancel(); // no-op
        assertThat(Session.CancelToken.NONE.cancelled()).isFalse();
    }

    @Test
    void null_cancel_normalizes_to_none() {
        Session s = Session.defaults().withCancel(null);

        assertThat(s.cancel()).isSameAs(Session.CancelToken.NONE);
        assertThat(s.cancelled()).isFalse();
        s.cancel().cancel(); // still inert
        assertThat(s.cancelled()).isFalse();
    }
}
