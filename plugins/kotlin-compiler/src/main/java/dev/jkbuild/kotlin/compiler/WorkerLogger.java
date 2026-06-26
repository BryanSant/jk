// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.kotlin.compiler;

import org.jetbrains.kotlin.buildtools.api.KotlinLogger;

/**
 * Bridges the Build Tools API's logger onto jk's NDJSON protocol. Compiler
 * diagnostics arrive here as {@code error}/{@code warn} calls; we forward them
 * to the parent as structured {@code diag} lines. {@code debug} is dropped
 * (we report {@link #isDebugEnabled()} {@code false}).
 */
final class WorkerLogger implements KotlinLogger {

    private final KcProtocol proto;

    WorkerLogger(KcProtocol proto) {
        this.proto = proto;
    }

    @Override
    public boolean isDebugEnabled() {
        return false;
    }

    @Override
    public void error(String msg, Throwable throwable) {
        proto.diagnostic("ERROR", withThrowable(msg, throwable));
    }

    @Override
    public void warn(String msg, Throwable throwable) {
        proto.diagnostic("WARNING", withThrowable(msg, throwable));
    }

    @Override
    public void warn(String msg) {
        proto.diagnostic("WARNING", msg);
    }

    @Override
    public void info(String msg) {
        proto.diagnostic("INFO", msg);
    }

    @Override
    public void debug(String msg) {
        // dropped — see isDebugEnabled()
    }

    @Override
    public void lifecycle(String msg) {
        proto.diagnostic("INFO", msg);
    }

    private static String withThrowable(String msg, Throwable t) {
        return t == null ? msg : msg + "\n" + t;
    }
}
