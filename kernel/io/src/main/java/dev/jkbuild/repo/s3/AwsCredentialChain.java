// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.repo.s3;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Resolves AWS credentials the way the AWS SDKs/CLI do (the subset jk needs):
 *
 * <ol>
 *   <li>environment — {@code AWS_ACCESS_KEY_ID} / {@code AWS_SECRET_ACCESS_KEY} / {@code
 *       AWS_SESSION_TOKEN}, region from {@code AWS_REGION} then {@code AWS_DEFAULT_REGION};
 *   <li>shared files — {@code ~/.aws/credentials} (keys) and {@code ~/.aws/config} (region), under
 *       the profile named by {@code AWS_PROFILE} (default {@code default}).
 * </ol>
 *
 * <p>Instance/container role providers (IMDS, ECS, web-identity) are out of scope for now. Env and
 * the {@code ~/.aws} dir are injected so this is unit-testable without touching the real
 * environment. Returns empty when no keys are found — the S3 transport then issues unsigned
 * (anonymous) requests, which work for public buckets.
 */
public final class AwsCredentialChain {

    private final Function<String, String> env;
    private final Path awsDir;

    public AwsCredentialChain() {
        this(System::getenv, defaultAwsDir());
    }

    public AwsCredentialChain(Function<String, String> env, Path awsDir) {
        this.env = env;
        this.awsDir = awsDir;
    }

    /** Resolve credentials; {@code regionOverride} (e.g. from the repo URL) wins for the region. */
    public Optional<AwsCredentials> resolve(String regionOverride) {
        String envAk = nonBlank(env.apply("AWS_ACCESS_KEY_ID"));
        String envSk = nonBlank(env.apply("AWS_SECRET_ACCESS_KEY"));
        String envRegion = firstNonBlank(env.apply("AWS_REGION"), env.apply("AWS_DEFAULT_REGION"));
        String region = firstNonBlank(regionOverride, envRegion);

        if (envAk != null && envSk != null) {
            return Optional.of(new AwsCredentials(envAk, envSk, nonBlank(env.apply("AWS_SESSION_TOKEN")), region));
        }

        String profile = firstNonBlank(env.apply("AWS_PROFILE"), "default");
        Map<String, String> creds = section(awsDir.resolve("credentials"), profile);
        String ak = nonBlank(creds.get("aws_access_key_id"));
        String sk = nonBlank(creds.get("aws_secret_access_key"));
        if (ak == null || sk == null) return Optional.empty();

        // config uses "[profile name]" except for the default profile.
        String configSection = profile.equals("default") ? "default" : "profile " + profile;
        Map<String, String> config = section(awsDir.resolve("config"), configSection);
        if (region == null) region = nonBlank(config.get("region"));

        return Optional.of(new AwsCredentials(ak, sk, nonBlank(creds.get("aws_session_token")), region));
    }

    /** Parse a single INI {@code [section]} from an AWS shared file; missing → empty map. */
    private static Map<String, String> section(Path file, String wanted) {
        Map<String, String> out = new HashMap<>();
        if (!Files.isRegularFile(file)) return out;
        try {
            boolean inSection = false;
            for (String raw : Files.readAllLines(file)) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue;
                if (line.startsWith("[") && line.endsWith("]")) {
                    inSection = line.substring(1, line.length() - 1).trim().equals(wanted);
                    continue;
                }
                if (!inSection) continue;
                int eq = line.indexOf('=');
                if (eq > 0) {
                    out.put(
                            line.substring(0, eq).trim().toLowerCase(Locale.ROOT),
                            line.substring(eq + 1).trim());
                }
            }
        } catch (IOException ignored) {
            // best-effort
        }
        return out;
    }

    private static Path defaultAwsDir() {
        String home = System.getProperty("user.home", "");
        return Path.of(home, ".aws");
    }

    private static String nonBlank(String s) {
        return (s == null || s.isBlank()) ? null : s.strip();
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            String n = nonBlank(v);
            if (n != null) return n;
        }
        return null;
    }
}
