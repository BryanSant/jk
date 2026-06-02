autoload -Uz add-zsh-hook
add-zsh-hook -d precmd _jk_hook_precmd 2>/dev/null
add-zsh-hook -d chpwd _jk_hook_chpwd 2>/dev/null
if [ -n "${__JK_ORIG_PATH:-}" ]; then
    export PATH="$__JK_ORIG_PATH"
fi
unset JAVA_HOME
unset GRAALVM_HOME
unset __JK_EXE
unset __JK_SHELL
unset __JK_ORIG_PATH
unset __JK_DIFF
(( $+functions[_jk_hook_precmd] )) && unset -f _jk_hook_precmd
(( $+functions[_jk_hook_chpwd] )) && unset -f _jk_hook_chpwd
(( $+functions[jk] )) && unset -f jk
(( $+functions[jkx] )) && unset -f jkx
