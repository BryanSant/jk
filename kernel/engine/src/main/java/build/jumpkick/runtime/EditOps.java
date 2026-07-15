// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.runtime;

import build.jumpkick.config.JkBuildEditor;
import build.jumpkick.model.Scope;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Engine-hosted {@code jk.toml} edits (thin-client contract): the client names an operation, the
 * engine parses/edits/writes — {@link JkBuildEditor} (and its TOML stack) never runs client-side.
 * Every op is read-modify-write on one file and idempotent where the editor is (registering an
 * already-registered module is a no-op).
 *
 * <p>Ops and their positional args:
 *
 * <ul>
 *   <li>{@code add-dependency}: scope, name, group, artifact, version
 *   <li>{@code add-file-dependency}: scope, library, group, artifact, version, sha256
 *   <li>{@code remove-dependency}: scope, name
 *   <li>{@code add-workspace-module}: modulePath
 *   <li>{@code register-workspace-module}: modulePath (promotes a plain project to a root)
 * </ul>
 */
public final class EditOps {

    /** {@code changed} false = the edit was a no-op (content already as requested). */
    public record Result(boolean changed, String error) {}

    private EditOps() {}

    public static Result apply(Path file, String op, List<String> args) {
        try {
            String original = Files.readString(file, StandardCharsets.UTF_8);
            String updated =
                    switch (op) {
                        case "add-dependency" ->
                            JkBuildEditor.addDependency(
                                    original,
                                    Scope.fromCanonical(args.get(0)),
                                    args.get(1),
                                    args.get(2),
                                    args.get(3),
                                    args.get(4));
                        case "add-file-dependency" ->
                            JkBuildEditor.addFileDependency(
                                    original,
                                    Scope.fromCanonical(args.get(0)),
                                    args.get(1),
                                    args.get(2),
                                    args.get(3),
                                    args.get(4),
                                    args.get(5));
                        case "remove-dependency" ->
                            JkBuildEditor.removeDependency(original, Scope.fromCanonical(args.get(0)), args.get(1));
                        case "add-workspace-module" -> JkBuildEditor.addWorkspaceModule(original, args.get(0));
                        case "register-workspace-module" ->
                            JkBuildEditor.registerWorkspaceModule(original, args.get(0));
                        default -> throw new IllegalArgumentException("unknown edit op: " + op);
                    };
            if (updated.equals(original)) return new Result(false, null);
            Files.writeString(file, updated, StandardCharsets.UTF_8);
            return new Result(true, null);
        } catch (IOException | RuntimeException e) {
            return new Result(false, String.valueOf(e.getMessage()));
        }
    }
}
