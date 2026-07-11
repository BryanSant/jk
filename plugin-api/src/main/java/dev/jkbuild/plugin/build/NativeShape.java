// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.plugin.build;

import java.util.List;

/**
 * Reserved (P7): native-image shaping beyond what step {@code contributes*} declarations already
 * fold into the image classpath — extra build args, resource-config hints.
 */
public record NativeShape(List<String> imageArgs) {}
