// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.android;

import dev.jkbuild.plugin.build.PackageIo;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Android view of the module's runtime entries: every dependency whose artifact is an AAR
 * container (a remote androidx library or a workspace {@code [android] library} sibling), in
 * classpath order — the deterministic merge order for resources and manifests (app last, so the
 * app wins; matching AGP's precedence).
 */
final class AndroidDeps {

    private AndroidDeps() {}

    /** One AAR dependency: its exploded container and the pieces the steps consume. */
    record Aar(String fileName, Path container) {

        Path res() {
            return container.resolve("res");
        }

        Path manifest() {
            return container.resolve("AndroidManifest.xml");
        }

        Path rTxt() {
            return container.resolve("R.txt");
        }

        boolean hasRes() throws IOException {
            if (!Files.isDirectory(res())) return false;
            try (var listing = Files.list(res())) {
                return listing.findFirst().isPresent();
            }
        }

        /** The AAR's package/namespace, parsed from its manifest's {@code package} attribute. */
        String namespace() throws IOException {
            if (!Files.isRegularFile(manifest())) return null;
            String xml = Files.readString(manifest());
            Matcher m = Pattern.compile("package\\s*=\\s*\"([^\"]+)\"").matcher(xml);
            return m.find() ? m.group(1) : null;
        }
    }

    /** The AAR containers among {@code entries}, in entry (classpath) order. */
    static List<Aar> aars(List<PackageIo.RuntimeEntry> entries) {
        List<Aar> out = new ArrayList<>();
        for (PackageIo.RuntimeEntry e : entries) {
            if (e.container() != null) out.add(new Aar(e.fileName(), e.container()));
        }
        return out;
    }
}
