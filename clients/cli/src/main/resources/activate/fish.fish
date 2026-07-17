# `jk activate fish` — directory-aware JAVA_HOME / PATH management.
# Source via: jk activate fish | source
set -gx __JK_EXE __JK_EXE__
set -gx __JK_SHELL fish

if not set -q __JK_ORIG_PATH
    set -gx __JK_ORIG_PATH $PATH
end

function jk
    if test (count $argv) -eq 0
        command $__JK_EXE
        return
    end

    set -l cmd $argv[1]
    set -e argv[1]

    if contains -- --help $argv; or contains -- -h $argv
        command $__JK_EXE $cmd $argv
        return $status
    end

    switch $cmd
        case deactivate
            command $__JK_EXE $cmd $argv | source
        case '*'
            command $__JK_EXE $cmd $argv
    end
end

# (`jkx` is a real binary in $JK_BIN_DIR — a hardlink to jk with argv[0]
# dispatch — not a shell function, so shebangs and CI work without this file.)

function __jk_env_eval --on-event fish_prompt --description 'jk: refresh env'
    command $__JK_EXE hook-env -s fish | source
end

function __jk_cd_hook --on-variable PWD --description 'jk: refresh env on cd'
    command $__JK_EXE hook-env -s fish | source
end

__jk_env_eval
