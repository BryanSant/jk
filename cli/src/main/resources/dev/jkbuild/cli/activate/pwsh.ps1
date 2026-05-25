# `jk activate pwsh` — directory-aware JAVA_HOME / PATH management.
# Source via: (& jk activate pwsh) | Out-String | Invoke-Expression
$env:__JK_EXE = '__JK_EXE__'
$env:__JK_SHELL = 'pwsh'

if (-not (Test-Path -Path Env:/__JK_ORIG_PATH)) {
    $env:__JK_ORIG_PATH = $env:PATH
}

function global:jk {
    [CmdletBinding()]
    param(
        [Parameter(ValueFromRemainingArguments = $true)]
        [string[]] $arguments
    )

    if ($arguments.Count -eq 0) {
        & $env:__JK_EXE
        return
    }

    if ($arguments -contains '-h' -or $arguments -contains '--help') {
        & $env:__JK_EXE @arguments
        return
    }

    $cmd = $arguments[0]
    if ($arguments.Length -gt 1) {
        $rest = $arguments[1..($arguments.Length - 1)]
    } else {
        $rest = @()
    }

    switch ($cmd) {
        'deactivate' {
            & $env:__JK_EXE $cmd @rest | Out-String | Invoke-Expression -ErrorAction SilentlyContinue
        }
        default {
            & $env:__JK_EXE $cmd @rest
        }
    }
}

# `jkx` — uvx-style ephemeral tool exec; expands to `jk tool run`.
function global:jkx {
    [CmdletBinding()]
    param(
        [Parameter(ValueFromRemainingArguments = $true)]
        [string[]] $arguments
    )
    & $env:__JK_EXE tool run @arguments
}

function global:_jk_hook {
    if ($env:__JK_SHELL -eq 'pwsh') {
        $output = & $env:__JK_EXE hook-env -s pwsh | Out-String
        if ($output -and $output.Trim()) {
            $output | Invoke-Expression
        }
    }
}

# Chpwd: requires PowerShell 7+ (LocationChangedAction).
if ($PSVersionTable.PSVersion.Major -ge 7 -and -not $__jk_pwsh_chpwd_installed) {
    $Global:__jk_pwsh_chpwd_installed = $true
    $__jk_chpwd = [EventHandler[System.Management.Automation.LocationChangedEventArgs]] {
        param($source, $args)
        end { _jk_hook }
    }
    $existing = $ExecutionContext.SessionState.InvokeCommand.LocationChangedAction
    if ($existing) {
        $ExecutionContext.SessionState.InvokeCommand.LocationChangedAction = [Delegate]::Combine($existing, $__jk_chpwd)
    } else {
        $ExecutionContext.SessionState.InvokeCommand.LocationChangedAction = $__jk_chpwd
    }
}

# Prompt hook: runs each prompt, so a `cd` from a subshell or function still updates env.
if (-not $__jk_pwsh_prompt_installed) {
    $Global:__jk_pwsh_prompt_installed = $true
    $Global:__jk_previous_prompt = $function:prompt
    function global:prompt {
        _jk_hook
        & $__jk_previous_prompt
    }
}

_jk_hook
