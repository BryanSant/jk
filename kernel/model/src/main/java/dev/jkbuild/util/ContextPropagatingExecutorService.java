// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * An {@link ExecutorService} that wraps every submitted task via {@link ContextPropagator} — capturing
 * the submitting thread's ambient context and re-establishing it on the worker — then delegates to a
 * real executor's <em>same</em> method. This is how {@link JkThreads#io()}/{@link JkThreads#cpu()}
 * carry a {@code where()}-bound session onto the shared pools.
 *
 * <p><b>No double-wrap.</b> Each task-submitting method wraps once and forwards to the delegate's
 * corresponding method — {@code submit} forwards to {@code delegate.submit}, not to this decorator's
 * {@code execute}. The JDK's {@code AbstractExecutorService} (which {@code ForkJoinPool} extends)
 * routes {@code submit}/{@code invokeAll}/{@code invokeAny} internally through <em>its own</em>
 * {@code execute}, i.e. the real pool's {@code execute} — never back through this decorator — so a
 * task wrapped in {@code submit} is not re-wrapped in {@code execute}. (The virtual-thread executor
 * from {@code Executors.newThreadPerTaskExecutor} likewise keeps its {@code submit}→internal-dispatch
 * path inside the delegate.) {@code CompletableFuture.supplyAsync(supplier, exec)} calls
 * {@code exec.execute(runnable)} directly, so it is covered by {@link #execute} alone.
 */
final class ContextPropagatingExecutorService implements ExecutorService {

    private final ExecutorService delegate;

    ContextPropagatingExecutorService(ExecutorService delegate) {
        this.delegate = delegate;
    }

    // ---- task submission: wrap once on the submitting thread, then delegate to the SAME method ----

    @Override
    public void execute(Runnable command) {
        delegate.execute(ContextPropagator.wrapRunnable(command));
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
        return delegate.submit(ContextPropagator.wrapCallable(task));
    }

    @Override
    public Future<?> submit(Runnable task) {
        return delegate.submit(ContextPropagator.wrapRunnable(task));
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
        return delegate.submit(ContextPropagator.wrapRunnable(task), result);
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks)
            throws InterruptedException {
        return delegate.invokeAll(wrapAll(tasks));
    }

    @Override
    public <T> List<Future<T>> invokeAll(
            Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException {
        return delegate.invokeAll(wrapAll(tasks), timeout, unit);
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks)
            throws InterruptedException, ExecutionException {
        return delegate.invokeAny(wrapAll(tasks));
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        return delegate.invokeAny(wrapAll(tasks), timeout, unit);
    }

    private static <T> List<Callable<T>> wrapAll(Collection<? extends Callable<T>> tasks) {
        List<Callable<T>> wrapped = new ArrayList<>(tasks.size());
        for (Callable<T> task : tasks) {
            wrapped.add(ContextPropagator.wrapCallable(task));
        }
        return wrapped;
    }

    // ---- lifecycle: straight passthrough to the real executor ----

    @Override
    public void shutdown() {
        delegate.shutdown();
    }

    @Override
    public List<Runnable> shutdownNow() {
        return delegate.shutdownNow();
    }

    @Override
    public boolean isShutdown() {
        return delegate.isShutdown();
    }

    @Override
    public boolean isTerminated() {
        return delegate.isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.awaitTermination(timeout, unit);
    }

    @Override
    public void close() {
        delegate.close();
    }
}
