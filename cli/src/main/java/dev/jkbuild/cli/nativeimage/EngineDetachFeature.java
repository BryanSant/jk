// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.nativeimage;

import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeForeignAccess;

/**
 * Native-image build-time registration for the FFM downcalls the CLI makes at run time. GraalVM
 * requires every downcall's {@link java.lang.foreign.FunctionDescriptor} to be registered during
 * the image build; an unregistered descriptor throws at first use in the native binary (it works
 * silently on a hosted JVM, which is exactly why this must not be forgotten here).
 *
 * <p>Registered via {@code --features=} in {@code cli/build.gradle.kts}. The {@code
 * org.graalvm.nativeimage} API is {@code compileOnly}: the image builder supplies it at build
 * time, and nothing on the runtime classpath references this class.
 *
 * <p>Currently registered: {@code setsid(2)} for {@code PosixDetach} (the engine role's
 * self-detach into its own session).
 */
public final class EngineDetachFeature implements Feature {

    @Override
    public void duringSetup(DuringSetupAccess access) {
        RuntimeForeignAccess.registerForDowncall(dev.jkbuild.cli.engine.PosixDetach.SETSID);
    }
}
