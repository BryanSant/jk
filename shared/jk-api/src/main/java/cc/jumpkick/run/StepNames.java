// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.run;

/**
 * Canonical step-name constants (the {@code {phase}-{step}} step names (their place in the run hierarchy is {@code pipeline/phase/step}) used by {@link
 * Step#builder} producers and their {@code .requires(...)} consumers). Naming a step in one
 * place kills the stringly-typed producer/consumer duplication where a typo would otherwise be a
 * silent missing-dependency edge rather than a compile error.
 */
public final class StepNames {

    private StepNames() {}

    public static final String ASSEMBLE_CLASSES = "assemble-classes";
    public static final String CACHE_INSTALL = "cache-install";
    public static final String COLLECT_SOURCES = "collect-sources";
    public static final String COMPARE_HASHES = "compare-hashes";
    public static final String COMPILE_JAVA = "compile-java";
    public static final String COMPILE_KOTLIN = "compile-kotlin";
    public static final String COMPILE_TEST = "compile-test";
    public static final String COPY_RESOURCES = "copy-resources";
    public static final String ENSURE_JDK = "ensure-jdk";
    public static final String FETCH_CATALOG = "fetch-catalog";
    public static final String FETCH_GIT = "fetch-git";
    public static final String IMAGE_PLAN = "image-plan";
    public static final String INSPECT_JAR = "inspect-jar";
    public static final String INSTALL_JDK = "install-jdk";
    public static final String LOCK_PLUGINS = "lock-plugins";
    public static final String LOCK_SDK = "lock-sdk";
    public static final String NATIVE_IMAGE = "native-image";
    public static final String PACKAGE_JAR = "package-jar";
    public static final String PACKAGE_SHADOW = "package-shadow";
    public static final String PACKAGE_SOURCES = "package-sources";
    public static final String PARSE_BUILD = "parse-build";
    public static final String PARSE_LOCK = "parse-lock";
    public static final String PARSE_SCRIPT = "parse-script";
    public static final String QUERY_OSV = "query-osv";
    public static final String READ_LOCK = "read-lock";
    public static final String REBUILD_SCRATCH = "rebuild-scratch";
    public static final String RECONCILE_DEFAULT = "reconcile-default";
    public static final String RESOLVE_COORD = "resolve-coord";
    public static final String RESOLVE_DEPS = "resolve-deps";
    public static final String RESOLVE_FORMATTERS = "resolve-formatters";
    public static final String RESOLVE_JAR_DEPS = "resolve-jar-deps";
    public static final String RESOLVE_KOTLINC = "resolve-kotlinc";
    public static final String RUN_TESTS = "run-tests";
    public static final String SET_DEFAULT = "set-default";
    public static final String SYNC_CAS = "sync-cas";
    public static final String SYNC_MODULES = "sync-modules";
    public static final String SYNC_PLUGINS = "sync-plugins";
    public static final String SYNC_SOURCES = "sync-sources";
    public static final String SYNC_WORKERS = "sync-workers";
    public static final String WRITE_IMAGE = "write-image";
    public static final String WRITE_LOCKFILE = "write-lockfile";
    public static final String WRITE_STAMP = "write-stamp";
    public static final String WRITE_STAMP_KOTLIN = "write-stamp-kotlin";
    public static final String WRITE_SYNC_MANIFEST = "write-sync-manifest";
    public static final String DELETE = "delete";
    public static final String INSTALL = "install";
    public static final String PREWARM = "prewarm";
    public static final String SELECT = "select";
    public static final String WIZARD = "wizard";
    public static final String SCAFFOLD = "scaffold";
}
