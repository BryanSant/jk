// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.command.ide;

import build.jumpkick.model.Scope;
import java.util.List;

/**
 * A module's reference to an external {@link LibDef}, carrying the raw jk scopes so each
 * {@link IdeGenerator} can map them to its own vocabulary (IntelliJ order-entry scope, Eclipse
 * {@code test} attribute, …).
 *
 * @param libName coordinate string {@code group:artifact:version} (key into {@code IdeModel.allLibs})
 * @param scopes the jk scopes this dependency is declared in
 */
public record LibEntry(String libName, List<Scope> scopes) {}
