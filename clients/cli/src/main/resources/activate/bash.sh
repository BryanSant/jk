# shellcheck shell=bash
# `jk activate bash` — directory-aware JAVA_HOME / PATH management.
# Source via: eval "$(jk activate bash)"
export __JK_EXE=__JK_EXE__
export __JK_SHELL=bash

# Save the original PATH once, so deactivation can restore it cleanly.
if [ -z "${__JK_ORIG_PATH:-}" ]; then
    export __JK_ORIG_PATH="$PATH"
fi

# Proxy: lets `jk shell` / `jk deactivate` mutate the current shell session.
jk() {
    local cmd
    cmd="${1:-}"
    if [ "$#" = 0 ]; then
        command "$__JK_EXE"
        return
    fi
    shift
    case "$cmd" in
        deactivate)
            if [[ ! " $* " =~ " --help " ]] && [[ ! " $* " =~ " -h " ]]; then
                eval "$(command "$__JK_EXE" "$cmd" "$@")"
                return $?
            fi
            ;;
    esac
    command "$__JK_EXE" "$cmd" "$@"
}

# (`jkx` is a real binary in $JK_BIN_DIR — a hardlink to jk with argv[0]
# dispatch — not a shell function, so shebangs and CI work without this file.)

_jk_hook() {
    local prev_status=$?
    eval "$(command "$__JK_EXE" hook-env -s bash)"
    return $prev_status
}

# Bash doesn't have chpwd hooks natively; PROMPT_COMMAND runs before every
# prompt, which is close enough for our cd-tracking needs.
if [[ ";${PROMPT_COMMAND:-};" != *";_jk_hook;"* ]]; then
    if [[ "$(declare -p PROMPT_COMMAND 2>/dev/null)" == "declare -a"* ]]; then
        PROMPT_COMMAND=("_jk_hook" "${PROMPT_COMMAND[@]}")
    else
        PROMPT_COMMAND="_jk_hook${PROMPT_COMMAND:+;$PROMPT_COMMAND}"
    fi
fi

_jk_hook
