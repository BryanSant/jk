// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.command;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Guesses a sensible {@code groupId} default for {@code jk init} by walking up from the current
 * directory, looking for a {@code .gitconfig} (then falling back to {@code ~/.gitconfig}), reading
 * {@code [user] email}, and mapping the email domain to a Maven group:
 *
 * <ul>
 *   <li>{@code @github.com} / {@code @github.io} → {@code io.github.<local-part>}.
 *   <li>Well-known free-mail providers ({@code gmail.com}, {@code outlook.com}, {@code
 *       hotmail.com}, {@code proton.me}, {@code yahoo.com}, …) → {@code com.example}.
 *   <li>Any other domain → reversed: {@code modmed.com} → {@code com.modmed}.
 * </ul>
 *
 * The empty/missing cases collapse to {@code "com.example"}.
 */
public final class NewGroupGuess {

    static final String FALLBACK = "com.example";

    /** Free-mail providers that we explicitly don't want surfacing as a groupId. */
    private static final Set<String> FREE_MAIL = Set.of(
            "gmail.com",
            "googlemail.com",
            "outlook.com",
            "hotmail.com",
            "live.com",
            "msn.com",
            "proton.me",
            "protonmail.com",
            "yahoo.com",
            "ymail.com",
            "icloud.com",
            "me.com",
            "mac.com",
            "fastmail.com",
            "aol.com",
            "duck.com",
            "pm.me");

    private static final Pattern EMAIL_LINE =
            Pattern.compile("^\\s*email\\s*=\\s*(.+?)\\s*$", Pattern.CASE_INSENSITIVE);

    private NewGroupGuess() {}

    /** Convenience entry point that uses the real cwd and {@code $HOME}. */
    public static String guess() {
        return guess(
                Path.of(".").toAbsolutePath().normalize(),
                Optional.ofNullable(System.getProperty("user.home"))
                        .map(Path::of)
                        .orElse(null));
    }

    /** Package-private for unit testing: caller supplies cwd + home. */
    static String guess(Path cwd, Path home) {
        return readEmail(cwd, home).map(NewGroupGuess::groupForEmail).orElse(FALLBACK);
    }

    /** Maps an email to a groupId per the rules in the class javadoc. */
    static String groupForEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 0 || at == email.length() - 1) return FALLBACK;
        String localPart = email.substring(0, at).toLowerCase(Locale.ROOT);
        String domain = email.substring(at + 1).toLowerCase(Locale.ROOT);

        if (domain.equals("github.com") || domain.equals("github.io") || domain.equals("users.noreply.github.com")) {
            String user = stripNoreplyPrefix(localPart);
            if (user.isEmpty()) return FALLBACK;
            return "io.github." + sanitizeIdentifier(user);
        }
        if (FREE_MAIL.contains(domain)) return FALLBACK;
        return reverseDomain(domain);
    }

    /**
     * GitHub's noreply emails look like {@code 12345+username@users.noreply.github.com} (with the
     * leading digits as the user id). Strip the {@code <digits>+} prefix so the resulting group lands
     * on the username, not the user id.
     */
    private static String stripNoreplyPrefix(String localPart) {
        int plus = localPart.indexOf('+');
        if (plus < 0) return localPart;
        return localPart.substring(plus + 1);
    }

    /** Reverse {@code modmed.com} → {@code com.modmed}; multi-label domains preserved. */
    static String reverseDomain(String domain) {
        var labels = List.of(domain.split("\\."));
        if (labels.isEmpty()) return FALLBACK;
        var sb = new StringBuilder();
        for (int i = labels.size() - 1; i >= 0; i--) {
            var label = sanitizeIdentifier(labels.get(i));
            if (label.isEmpty()) continue;
            if (sb.length() > 0) sb.append('.');
            sb.append(label);
        }
        return sb.length() == 0 ? FALLBACK : sb.toString();
    }

    /**
     * Drop characters that can't appear in a Java package segment, and prefix an underscore if the
     * segment now starts with a digit. Belt-and-suspenders — a domain label like {@code 3com} would
     * otherwise produce {@code com.3com}, which is invalid.
     */
    private static String sanitizeIdentifier(String label) {
        var sb = new StringBuilder();
        for (int i = 0; i < label.length(); i++) {
            char c = label.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_') sb.append(c);
        }
        if (sb.length() == 0) return "";
        if (Character.isDigit(sb.charAt(0))) sb.insert(0, '_');
        return sb.toString();
    }

    /**
     * Walk from {@code cwd} up to the filesystem root looking for a {@code .gitconfig}; if none, try
     * {@code ~/.gitconfig}. Return the first {@code user.email} found.
     */
    static Optional<String> readEmail(Path cwd, Path home) {
        for (Path p = cwd; p != null; p = p.getParent()) {
            var local = p.resolve(".gitconfig");
            var found = parseEmail(local);
            if (found.isPresent()) return found;
        }
        if (home != null) {
            var found = parseEmail(home.resolve(".gitconfig"));
            if (found.isPresent()) return found;
        }
        return Optional.empty();
    }

    private static Optional<String> parseEmail(Path file) {
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            boolean inUser = false;
            for (var raw : Files.readAllLines(file)) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue;
                if (line.startsWith("[")) {
                    inUser = line.equalsIgnoreCase("[user]");
                    continue;
                }
                if (!inUser) continue;
                Matcher m = EMAIL_LINE.matcher(line);
                if (m.matches()) {
                    String value = stripQuotes(m.group(1).trim());
                    if (!value.isEmpty()) return Optional.of(value);
                }
            }
        } catch (IOException ignored) {
            // best-effort; treat as missing
        }
        return Optional.empty();
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}
