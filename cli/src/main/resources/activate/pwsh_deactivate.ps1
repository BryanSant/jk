if (Test-Path -Path Env:/__JK_ORIG_PATH) {
    $env:PATH = $env:__JK_ORIG_PATH
}
Remove-Item -ErrorAction SilentlyContinue -Path Env:/JAVA_HOME
Remove-Item -ErrorAction SilentlyContinue -Path Env:/GRAALVM_HOME
Remove-Item -ErrorAction SilentlyContinue -Path Env:/__JK_EXE
Remove-Item -ErrorAction SilentlyContinue -Path Env:/__JK_SHELL
Remove-Item -ErrorAction SilentlyContinue -Path Env:/__JK_ORIG_PATH
Remove-Item -ErrorAction SilentlyContinue -Path Env:/__JK_DIFF
Remove-Item -ErrorAction SilentlyContinue function:_jk_hook
Remove-Item -ErrorAction SilentlyContinue function:jk
Remove-Item -ErrorAction SilentlyContinue function:jkx  # legacy: jkx was a function before it became a real binary
