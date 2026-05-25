functions --erase __jk_env_eval 2>/dev/null
functions --erase __jk_cd_hook 2>/dev/null
functions --erase jk 2>/dev/null
functions --erase jkx 2>/dev/null
if set -q __JK_ORIG_PATH
    set -gx PATH $__JK_ORIG_PATH
end
set -e JAVA_HOME
set -e GRAALVM_HOME
set -e __JK_EXE
set -e __JK_SHELL
set -e __JK_ORIG_PATH
set -e __JK_DIFF
