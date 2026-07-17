# `jk activate zsh` — directory-aware JAVA_HOME / PATH management.
# Source via: eval "$(jk activate zsh)"
export __JK_EXE=__JK_EXE__
export __JK_SHELL=zsh

if [ -z "${__JK_ORIG_PATH:-}" ]; then
    export __JK_ORIG_PATH="$PATH"
fi

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
            if [[ ! " $@ " =~ " --help " ]] && [[ ! " $@ " =~ " -h " ]]; then
                eval "$(command "$__JK_EXE" "$cmd" "$@")"
                return $?
            fi
            ;;
    esac
    command "$__JK_EXE" "$cmd" "$@"
}

# (`jkx` is a real binary in $JK_BIN_DIR — a hardlink to jk with argv[0]
# dispatch — not a shell function, so shebangs and CI work without this file.)

autoload -Uz add-zsh-hook

_jk_hook_precmd() {
    eval "$(command "$__JK_EXE" hook-env -s zsh)"
}

_jk_hook_chpwd() {
    eval "$(command "$__JK_EXE" hook-env -s zsh)"
}

add-zsh-hook precmd _jk_hook_precmd
add-zsh-hook chpwd _jk_hook_chpwd

_jk_hook_precmd
