// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.runtime;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.config.WorkspaceLoader;
import dev.jkbuild.engine.protocol.OutdatedReport;
import dev.jkbuild.git.GitFetcher;
import dev.jkbuild.library.LibraryCatalog;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.lock.LockfileReader;
import dev.jkbuild.model.Coordinate;
import dev.jkbuild.model.Dependency;
import dev.jkbuild.model.GitRefSpec;
import dev.jkbuild.model.GitSource;
import dev.jkbuild.model.GitVersion;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.Scope;
import dev.jkbuild.model.WorkspaceMerge;
import dev.jkbuild.repo.RepoGroup;
import dev.jkbuild.resolver.VersionSelectors;
import dev.jkbuild.resolver.Versions;
import dev.jkbuild.resolver.pubgrub.VersionSet;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Computes the {@code jk outdated} report: for each direct declared dependency, the version pinned
 * in {@code jk.lock}, the newest version its declared selector still allows, the newest stable
 * version overall, and the non-stable "tip" frontier.
 *
 * <p>Read-only — never writes {@code jk.lock} or fetches artifacts. Maven versions come from {@link
 * RepoGroup#availableVersions} (the TTL'd metadata cache); git tags come from {@link
 * GitFetcher#listRefs} (a no-clone {@code ls-remote}). "Highest satisfying / highest stable" picks
 * reuse {@code VersionSelectors} + {@code Versions.compare/isStable} exactly like {@code
 * LockGoals.resolveKotlinVersion}.
 *
 * <p>At a workspace root the report cascades over every member module (each row tagged with its
 * owning module); otherwise it covers the single entry project. Path/workspace/file/platform-managed
 * deps are skipped. Coordinates are mapped back to their short catalog name for display when one
 * exists.
 */
public final class OutdatedGoals {

    private OutdatedGoals() {}

    /** Produce the report for the project (or workspace) rooted at {@code dir}. */
    public static OutdatedReport compute(Path dir, Path cache, URI repoUrl) {
        LinkedHashMap<Path, JkBuild> scopes = new LinkedHashMap<>();
        try {
            JkBuild root = JkBuildParser.parse(dir.resolve("jk.toml"));
            JkBuild effectiveRoot = LockGoals.applyWorkspaceContextIfModule(dir, root);
            scopes.put(dir, effectiveRoot);
            if (effectiveRoot.isWorkspaceRoot()) {
                Map<Path, JkBuild> modules = WorkspaceLoader.loadModules(dir, effectiveRoot);
                for (Map.Entry<Path, JkBuild> e : modules.entrySet()) {
                    scopes.put(e.getKey(), WorkspaceMerge.applyToModule(effectiveRoot, e.getValue(), modules.values()));
                }
            }
        } catch (Exception e) {
            return OutdatedReport.error(String.valueOf(e.getMessage()));
        }

        boolean workspace = scopes.size() > 1;
        Map<String, String> shortNames = reverseCatalog();
        GitFetcher git = new GitFetcher(cache.resolve("git"));
        Map<String, GitFetcher.RemoteRefs> gitRefsCache = new HashMap<>();

        List<OutdatedReport.Row> rows = new ArrayList<>();
        for (Map.Entry<Path, JkBuild> scope : scopes.entrySet()) {
            Path moduleDir = scope.getKey();
            JkBuild build = scope.getValue();
            String moduleLabel = workspace ? LockGoals.coordLabel(build, moduleDir) : "";
            Map<String, String> locked = lockedVersions(moduleDir.resolve("jk.lock"));
            Cas cas = new Cas(cache);
            RepoGroup repos = RepoGroupBuilder.buildFor(build, repoUrl, cas);
            Set<String> seen = new LinkedHashSet<>();
            for (Map.Entry<Scope, List<Dependency>> entry : build.dependencies().byScope().entrySet()) {
                String scopeName = entry.getKey().canonical();
                for (Dependency dep : entry.getValue()) {
                    if (dep.isPath() || dep.isWorkspace() || dep.isFile() || dep.isPlatformManaged()) {
                        continue; // no enumerable version frontier for these
                    }
                    if (!seen.add(dep.module())) continue; // one row per coordinate — first scope wins
                    String display = shortNames.getOrDefault(dep.module(), "");
                    rows.add(dep.isGit()
                            ? gitRow(dep, moduleLabel, display, scopeName, git, gitRefsCache)
                            : mavenRow(dep, moduleLabel, display, scopeName, locked.get(dep.module()), repos));
                }
            }
        }
        return OutdatedReport.of(workspace, rows);
    }

    // ---- Maven --------------------------------------------------------------

    private static OutdatedReport.Row mavenRow(
            Dependency dep, String moduleLabel, String display, String scope, String current, RepoGroup repos) {
        final List<String> available = enumerate(dep, repos);
        VersionSet set = VersionSelectors.toVersionSet(dep.version());
        String compatible = available.stream()
                .filter(set::contains)
                .filter(Versions::isStable)
                .max(Versions::compare)
                .or(() -> available.stream().filter(set::contains).max(Versions::compare))
                .orElse("");
        String latest = available.stream().filter(Versions::isStable).max(Versions::compare).orElse("");
        final String stable = latest;
        String tip = available.stream()
                .filter(v -> !Versions.isStable(v))
                .max(Versions::compare)
                .filter(t -> stable.isEmpty() || Versions.compare(t, stable) > 0)
                .orElse("");
        return new OutdatedReport.Row(
                moduleLabel, dep.module(), display, scope, current == null ? "" : current, compatible, latest, tip);
    }

    /** Merged available versions across repos; empty on any I/O error (offline / unreachable). */
    private static List<String> enumerate(Dependency dep, RepoGroup repos) {
        try {
            return repos.availableVersions(Coordinate.ofModule(dep.module(), "any"));
        } catch (IOException e) {
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        }
    }

    // ---- git ----------------------------------------------------------------

    private static OutdatedReport.Row gitRow(
            Dependency dep,
            String moduleLabel,
            String display,
            String scope,
            GitFetcher git,
            Map<String, GitFetcher.RemoteRefs> cache) {
        GitSource src = dep.gitSource();
        String current = gitCurrent(src.ref());
        GitFetcher.RemoteRefs refs = cache.computeIfAbsent(src.canonicalUrl(), u -> listRefsQuiet(git, src));
        String[] latestAndTip = gitLatestAndTip(refs.tags());
        return new OutdatedReport.Row(
                moduleLabel,
                dep.module(),
                display,
                scope,
                current,
                /* compatible = the immutable pin */ current,
                latestAndTip[0],
                latestAndTip[1]);
    }

    /**
     * From a git remote's tag names, pick {@code [latest, tip]}: {@code latest} is the newest
     * version-like <em>stable</em> tag (original name), {@code tip} is the newest non-stable tag
     * ahead of it, or the literal {@code "tip"} for the moving HEAD when no prerelease tag is newer.
     * Non-version-like tags (which {@link GitVersion#fromTag} returns verbatim, not starting with a
     * digit) are ignored. Package-private for tests.
     */
    static String[] gitLatestAndTip(List<String> tags) {
        String latestTag = "";
        String latestVer = null;
        String tipTag = "";
        String tipVer = null;
        for (String tag : tags) {
            String v = GitVersion.fromTag(tag);
            if (v.isEmpty() || !Character.isDigit(v.charAt(0))) continue; // not version-like (raw fallback)
            if (Versions.isStable(v)) {
                if (latestVer == null || Versions.compare(v, latestVer) > 0) {
                    latestVer = v;
                    latestTag = tag;
                }
            } else if (tipVer == null || Versions.compare(v, tipVer) > 0) {
                tipVer = v;
                tipTag = tag;
            }
        }
        String tip = (tipVer != null && (latestVer == null || Versions.compare(tipVer, latestVer) > 0))
                ? tipTag
                : "tip";
        return new String[] {latestTag, tip};
    }

    /** Display string for a git dependency's currently-pinned ref. */
    private static String gitCurrent(GitRefSpec ref) {
        return switch (ref) {
            case GitRefSpec.Tag t -> t.name();
            case GitRefSpec.Branch b -> "tip"; // tracks the moving branch HEAD
            case GitRefSpec.Rev r -> r.sha().length() > 12 ? r.sha().substring(0, 12) : r.sha();
        };
    }

    private static GitFetcher.RemoteRefs listRefsQuiet(GitFetcher git, GitSource src) {
        try {
            return git.listRefs(src);
        } catch (IOException e) {
            return new GitFetcher.RemoteRefs(List.of(), null); // offline / unreachable → no tags
        }
    }

    // ---- shared -------------------------------------------------------------

    /** Build a {@code group:artifact -> short catalog name} index; shortest name wins per coord. */
    private static Map<String, String> reverseCatalog() {
        Map<String, String> reverse = new HashMap<>();
        LibraryCatalog catalog = LibraryCatalog.layered();
        for (String name : catalog.names()) {
            var mod = catalog.lookup(name);
            if (mod.isEmpty()) continue;
            String coord = mod.get().moduleKey();
            String existing = reverse.get(coord);
            if (existing == null
                    || name.length() < existing.length()
                    || (name.length() == existing.length() && name.compareTo(existing) < 0)) {
                reverse.put(coord, name);
            }
        }
        return reverse;
    }

    private static Map<String, String> lockedVersions(Path lockFile) {
        Map<String, String> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(lockFile)) return out;
        try {
            for (Lockfile.Artifact a : LockfileReader.read(lockFile).artifacts()) {
                out.putIfAbsent(a.name(), a.version());
            }
        } catch (Exception ignored) {
            // unreadable lock — treat as no locked versions
        }
        return out;
    }
}
