// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.runtime;

import dev.jkbuild.androidsdk.AndroidSdk;
import dev.jkbuild.androidsdk.AndroidSdkInstaller;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves {@code sdk-component} step-dependencies (build-plugins SPI): a component in
 * sdkmanager spelling is ensured under the managed SDK root and its path (or a file inside it)
 * handed to the plugin worker. The one provider today is the Android SDK — the manifest
 * vocabulary stays provider-neutral so a second provisioned-SDK kind slots in behind the same
 * key when one exists.
 *
 * <p>The pseudo-component {@code root} resolves to the SDK root itself (no download, no license)
 * — verbs that manage the SDK (licenses, status) anchor on it.
 */
final class SdkComponents {

    private SdkComponents() {}

    static Path resolve(String component, String pathInside) throws IOException, InterruptedException {
        AndroidSdk sdk = AndroidSdk.resolve();
        Path base;
        if ("root".equals(component)) {
            base = sdk.root();
        } else {
            base = new AndroidSdkInstaller(sdk).ensure(component);
        }
        if (pathInside == null || pathInside.isBlank()) return base;
        Path inside = base.resolve(pathInside).normalize();
        if (!inside.startsWith(base)) {
            throw new IOException("sdk-path escapes the component: " + pathInside);
        }
        if (!Files.exists(inside)) {
            throw new IOException("sdk component " + component + " has no " + pathInside + " (looked at " + inside
                    + ")");
        }
        return inside;
    }
}
