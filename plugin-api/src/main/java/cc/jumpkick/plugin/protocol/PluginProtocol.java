// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.protocol;

/**
 * The single vocabulary of the engine↔plugin wire — one JSONL protocol every forked plugin
 * speaks (see {@code docs/wip/unified-plugin-wire.md}). Every line is a flat object keyed by
 * {@link #T} ({@code "t"}); the spec (engine→plugin, a file) and the reply (plugin→engine, stdout,
 * {@code ##JK<XX>:}-prefixed) draw from the constants here so neither side hard-codes a string.
 *
 * <p>Spec and reply share the envelope but not the type sets: spec lines describe inputs; reply
 * lines describe progress, structured events, and the terminal outcome. Each op uses only the
 * subset it needs; unknown types and unknown {@link #CONFIG_KIND}s are ignored (forward-compat).
 */
public final class PluginProtocol {

    private PluginProtocol() {}

    /** The discriminator field present on every spec and reply line. */
    public static final String T = "t";

    // ---- ops (the value of the `op` spec line's `op` field) --------------------------------

    public static final String OP = "op";
    public static final String OP_NAME = "op"; // the op field within an `op` line
    public static final String OP_DESCRIBE = "describe";
    public static final String OP_RUN_STEP = "run-step";
    public static final String OP_PACKAGE = "package";
    public static final String OP_COMMAND = "command";
    public static final String OP_COMPILE = "compile";
    public static final String OP_TEST = "test";
    public static final String OP_IMAGE = "image";
    public static final String OP_PUBLISH = "publish";
    public static final String OP_RUN = "run";

    // ---- spec line types (engine → plugin) ------------------------------------------------

    public static final String CONFIG = "config";
    public static final String PROJECT = "project";
    public static final String MANIFEST_ATTR = "manifest-attr";
    public static final String LAYOUT = "layout";
    public static final String JAVA_HOME = "java-home";
    public static final String ARTIFACT = "artifact";
    public static final String CP = "cp"; // classpath entry, with a `role`
    public static final String ENTRY = "entry"; // runtime-closure entry
    public static final String SOURCE = "source"; // a source file (compile)
    public static final String STEP_OUTPUT = "step-output";
    public static final String EXTRA = "extra";
    public static final String SECRET = "secret";
    public static final String COMMAND_ARGS = "command-args";
    public static final String ARG = "arg"; // raw compiler-arg passthrough
    public static final String COMPILER_PLUGIN = "compiler-plugin";

    // ---- reply line types (plugin → engine) -----------------------------------------------

    public static final String LABEL = "label"; // free-text progress label
    public static final String PROGRESS = "progress"; // numeric progress {done,total?}
    public static final String OUT = "out"; // user-facing output line
    public static final String DIAGNOSTIC = "diagnostic"; // {sev,file?,line?,col?,msg}
    public static final String PROVENANCE = "provenance"; // {gen,src[]}
    public static final String TEST = "test"; // {event,…} test lifecycle event
    public static final String STEP = "step"; // describe declaration
    public static final String PACKAGER = "packager"; // describe declaration
    public static final String COMMAND = "command"; // describe declaration
    public static final String FINDING = "finding"; // audit vulnerability
    public static final String FILE = "file"; // format per-file outcome
    public static final String WROTE = "wrote"; // compat import wrote a file
    public static final String RESULT = "result"; // terminal typed payload
    public static final String ERROR = "error"; // {code,message}
    public static final String DONE = "done"; // {exit} terminal marker

    // ---- common field names ---------------------------------------------------------------

    public static final String NAME = "name";
    public static final String PLUGIN = "plugin";
    public static final String KEY = "key";
    public static final String VALUE = "value";
    public static final String VALUES = "values";
    public static final String CONFIG_KIND = "kind"; // string|bool|int|list
    public static final String KIND_STRING = "string";
    public static final String KIND_BOOL = "bool";
    public static final String KIND_INT = "int";
    public static final String KIND_LIST = "list";
    public static final String PATH = "path";
    public static final String ROLE = "role"; // cp entry role
    public static final String ROLE_COMPILE = "compile";
    public static final String ROLE_PROCESSOR = "processor";
    public static final String ROLE_FRIEND = "friend";
    public static final String ROLE_RUNTIME = "runtime";
    public static final String FILE_NAME = "file";
    public static final String SNAPSHOT = "snapshot";
    public static final String CONTAINER = "container";
    public static final String DIR = "dir";
    public static final String TEXT = "text";
    public static final String LINE = "line";
    public static final String MESSAGE = "message";
    public static final String CODE = "code";
    public static final String EXIT = "exit";
    public static final String OK = "ok";
    public static final String EVENT = "event"; // test event kind
    public static final String SEVERITY = "sev";
    public static final String COL = "col";
    public static final String STATUS = "status";
}
