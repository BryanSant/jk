// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.model.command;

import java.util.List;

/**
 * A command-line option (a {@code --flag} or {@code --name <value>}), declared
 * as data rather than a picocli annotation. The set of fields is exactly what
 * jk's commands relied on from {@code @Option}: multiple names, an optional
 * value (with label), boolean flags, repeatable + comma-split values, hidden,
 * negatable ({@code --no-x}), required, and an optional-argument fallback.
 *
 * <p>Build instances with {@link #flag} / {@link #value} and the {@code with*}
 * tweaks, so a command's {@code options()} reads as a short declarative list.
 *
 * @param names         option names, e.g. {@code ["-q", "--quiet"]}; the last is canonical
 * @param paramLabel    label for the value in help, or {@code null} for a boolean flag
 * @param description   help text
 * @param takesValue    true when the option consumes a value (false ⇒ boolean flag)
 * @param repeatable    true when the option may appear multiple times (List value)
 * @param split         delimiter to split a single value into many (e.g. {@code ","}), or {@code null}
 * @param hidden        true to omit from help
 * @param negatable     true when a {@code --no-<name>} form is also accepted
 * @param required      true when the option must be present
 * @param fallbackValue value used when the option appears without an argument (optional-arg), or {@code null}
 */
public record Opt(
        List<String> names,
        String paramLabel,
        String description,
        boolean takesValue,
        boolean repeatable,
        String split,
        boolean hidden,
        boolean negatable,
        boolean required,
        String fallbackValue) {

    public Opt {
        names = List.copyOf(names);
    }

    /** A boolean flag option (no value), e.g. {@code --skip-tests}. */
    public static Opt flag(String description, String... names) {
        return new Opt(List.of(names), null, description, false, false, null, false, false, false, null);
    }

    /** A value option, e.g. {@code --profile <name>}. */
    public static Opt value(String paramLabel, String description, String... names) {
        return new Opt(List.of(names), paramLabel, description, true, false, null, false, false, false, null);
    }

    public Opt hide() {
        return new Opt(
                names,
                paramLabel,
                description,
                takesValue,
                repeatable,
                split,
                true,
                negatable,
                required,
                fallbackValue);
    }

    public Opt require() {
        return new Opt(
                names, paramLabel, description, takesValue, repeatable, split, hidden, negatable, true, fallbackValue);
    }

    public Opt negate() {
        return new Opt(
                names, paramLabel, description, takesValue, repeatable, split, hidden, true, required, fallbackValue);
    }

    public Opt repeat() {
        return new Opt(
                names, paramLabel, description, takesValue, true, split, hidden, negatable, required, fallbackValue);
    }

    public Opt splitOn(String delimiter) {
        return new Opt(
                names,
                paramLabel,
                description,
                takesValue,
                true,
                delimiter,
                hidden,
                negatable,
                required,
                fallbackValue);
    }

    /** Optional-argument option: present-without-value yields {@code fallback}. */
    public Opt withFallback(String fallback) {
        return new Opt(
                names, paramLabel, description, takesValue, repeatable, split, hidden, negatable, required, fallback);
    }

    /** The canonical (longest {@code --}) name, used as the lookup key in {@link Invocation}. */
    public String canonicalName() {
        String best = names.get(names.size() - 1);
        for (String n : names) {
            if (n.startsWith("--") && n.length() > best.length()) best = n;
        }
        return best.replaceFirst("^--?", "");
    }

    public boolean matches(String token) {
        return names.contains(token);
    }
}
