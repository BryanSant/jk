# `jk deactivate` for bash: tear down hooks, restore PATH.
if [ -n "${__JK_ORIG_PATH:-}" ]; then
    export PATH="$__JK_ORIG_PATH"
fi
unset JAVA_HOME
unset GRAALVM_HOME
unset __JK_EXE
unset __JK_SHELL
unset __JK_ORIG_PATH
unset __JK_DIFF
# Drop the prompt hook entry if present.
if [[ "$(declare -p PROMPT_COMMAND 2>/dev/null)" == "declare -a"* ]]; then
    PROMPT_COMMAND=("${PROMPT_COMMAND[@]/_jk_hook/}")
else
    PROMPT_COMMAND="${PROMPT_COMMAND//_jk_hook;/}"
    PROMPT_COMMAND="${PROMPT_COMMAND//;_jk_hook/}"
    PROMPT_COMMAND="${PROMPT_COMMAND//_jk_hook/}"
fi
unset -f _jk_hook 2>/dev/null
unset -f jk 2>/dev/null
unset -f jkx 2>/dev/null  # legacy: jkx was a function before it became a real binary
