// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.plugin.build;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A packager that replaces the module's main artifact (build-plugins plan §3.3). jk keys the
 * artifact cache on the declared inputs and owns where the artifact lives; the static shape of
 * the artifact (self-contained, exec mode) is declared in the manifest's {@code [packaging]}
 * table so run/install/image can consult it without executing plugin code.
 */
public final class PackagerSpec {

    /** The packager's assembly body — runs in the plugin's worker JVM. */
    @FunctionalInterface
    public interface Body {
        void produce(PackageIo io) throws Exception;
    }

    private final String name;
    private final List<In> inputs = new ArrayList<>();
    private Body body;

    private PackagerSpec(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    /** The only composition rule for now: one packager may replace the main artifact. */
    public static PackagerSpec replacingMainArtifact(String name) {
        return new PackagerSpec(name);
    }

    public PackagerSpec inputs(In... ins) {
        for (In in : ins) inputs.add(in);
        return this;
    }

    public PackagerSpec produce(Body body) {
        this.body = body;
        return this;
    }

    public String name() {
        return name;
    }

    public List<In> declaredInputs() {
        return List.copyOf(inputs);
    }

    public Body body() {
        return body;
    }
}
