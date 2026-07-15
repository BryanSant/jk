#!/usr/bin/env bash
#
# jk installer
#
# Usage:
#   curl -fsSL https://jkbuild.dev/install.sh | bash
#   wget -qO- https://jkbuild.dev/install.sh | bash
#   bash install.sh [/path/to/jk[.xz|.zip]]
#
# Environment variables:
#   JK_ARCHIVE_URL   Override the archive URL to download. Supports .xz and
#                    .zip (a plain uncompressed binary also works for local
#                    files). Defaults to the latest release matching this
#                    machine's OS/arch and available extractor.
#   JK_RELEASES_URL  Override the release site root (mirrors).
#   JK_VERSION       Install a specific version instead of the latest.
#   JK_INSTALL_DIR   Override the install directory (default: ~/.jk/bin).
#
set -euo pipefail

JK_HOME="${JK_HOME:-$HOME/.jk}"
INSTALL_DIR="${JK_INSTALL_DIR:-$JK_HOME/bin}"
# One immutable directory per version (jk-<os>-<arch>[.xz] + jk-engine-<version>.jar
# + SHA256SUMS); `latest/VERSION` is the only mutable pointer. The version is
# resolved ONCE and both artifacts come from the frozen directory, so a release
# published mid-install can never hand out a binary and an engine jar that
# disagree (the client refuses to launch a version-skewed jar).
RELEASES_URL="${JK_RELEASES_URL:-https://jkbuild.dev/releases}"

# Optional positional argument: local path to jk, jk.xz, or jk.zip.
LOCAL_FILE="${1:-}"

# ---- terminal / interactivity detection ------------------------------------
#
# Two independent signals, resolved once:
#   * colour — gated on stdout being a real terminal (and not NO_COLOR / a dumb
#     terminal), so piped/redirected output stays clean.
#   * interactivity — whether a controlling terminal is reachable at all. Under
#     `curl … | bash`, fd 0 is the PIPE feeding bash the script, NOT a tty, so
#     `[ -t 0 ]` is the wrong probe — /dev/tty is. This drives TTY_IN, the stdin
#     every child `jk` command is given: a real terminal when one exists (so
#     jk's own prompts / console detection work), else /dev/null. Routing
#     children away from the script's stdin also stops a child from consuming
#     the rest of the piped script — the classic `curl | bash` truncation.
#
# Honour CI and JK_NONINTERACTIVE to force the non-interactive path explicitly.

if [ -t 1 ] && [ -z "${NO_COLOR:-}" ] && [ "${TERM:-}" != "dumb" ]; then
  BOLD=$'\033[1m'; RED=$'\033[31m'; GREEN=$'\033[32m'; DIM=$'\033[2m'; RESET=$'\033[0m'
else
  BOLD=""; RED=""; GREEN=""; DIM=""; RESET=""
fi

if [ -z "${CI:-}" ] && [ -z "${JK_NONINTERACTIVE:-}" ] && { : </dev/tty; } 2>/dev/null; then
  INTERACTIVE=1; TTY_IN=/dev/tty
else
  INTERACTIVE=0; TTY_IN=/dev/null
fi

info()  { printf '%s●%s %s\n' "$GREEN" "$RESET" "$*"; }
note()  { printf '%s    %s%s\n' "$DIM" "$*" "$RESET"; }
err()   { printf '%s✖%s %s\n' "$RED" "$RESET" "$*" >&2; }
die()   { err "$@"; exit 1; }

# Run a child `jk` with its stdin bound to the terminal (or /dev/null) — never
# the (possibly piped) script stdin. Callers add their own stdout/stderr redirs.
# ($JK_BIN is resolved at call time, after the install step sets it.)
run_jk() { "$JK_BIN" "$@" 0<"$TTY_IN"; }

# ---- detect tools ----------------------------------------------------------

have() { command -v "$1" >/dev/null 2>&1; }

# Pick a downloader (not needed when a local file is provided).
if [ -z "$LOCAL_FILE" ]; then
  if have curl; then
    download() { curl -fsSL "$1" -o "$2"; }
    fetch_text() { curl -fsSL "$1"; }
  elif have wget; then
    download() { wget -q "$1" -O "$2"; }
    fetch_text() { wget -qO- "$1"; }
  else
    die "neither curl nor wget found on PATH; cannot download jk."
  fi
fi

# Release artifacts are named jk-<os>-<arch> — the same vocabulary jk itself
# uses (HostPlatform): linux|macos × x86_64|aarch64. Windows gets its own
# PowerShell install script (jk-windows-x86_64.exe.zip), not this one.
detect_target() {
  local os arch
  case "$(uname -s)" in
    Linux)  os="linux" ;;
    Darwin) os="macos" ;;
    *) die "unsupported OS: $(uname -s) (this script supports Linux and macOS)" ;;
  esac
  case "$(uname -m)" in
    x86_64|amd64) arch="x86_64" ;;
    aarch64|arm64) arch="aarch64" ;;
    *) die "unsupported architecture: $(uname -m) (supported: x86_64, aarch64)" ;;
  esac
  printf '%s-%s' "$os" "$arch"
}

# Archive format for auto URL resolution: releases publish exactly two
# formats (docs/releases.md) — .xz, and .zip as the fallback for hosts
# without xz. Only needed for the download flow, so failing here must not
# break a local-file install.
detect_ext() {
  if have xz; then
    printf 'xz'
  elif have unzip; then
    printf 'zip'
  else
    die "neither xz nor unzip found on PATH; install one and re-run."
  fi
}

# ---- resolve source (URL or local file) ------------------------------------

# Sets decompress() based on the file/URL extension.
# Plain binary (no .xz/.zip — the local dist flow) is installed with cp.
infer_decompress() {
  case "$1" in
    *.xz)
      have xz || die "'$1' is a .xz file but xz is not installed."
      decompress() { xz -dc "$1" > "$2"; } ;;
    *.zip)
      have unzip || die "'$1' is a .zip file but unzip is not installed."
      # Single-entry archive: -p streams the binary to stdout.
      decompress() { unzip -p "$1" > "$2"; } ;;
    *)
      decompress() { cp "$1" "$2"; } ;;
  esac
}

if [ -n "$LOCAL_FILE" ]; then
  [ -f "$LOCAL_FILE" ] || die "local file not found: $LOCAL_FILE"
  infer_decompress "$LOCAL_FILE"
elif [ -n "${JK_ARCHIVE_URL:-}" ]; then
  ARCHIVE_URL="$JK_ARCHIVE_URL"
  infer_decompress "$ARCHIVE_URL"
else
  TARGET="$(detect_target)"
  EXT="$(detect_ext)"
  VERSION="${JK_VERSION:-$(fetch_text "$RELEASES_URL/latest/VERSION" | tr -d '[:space:]')}"
  [ -n "$VERSION" ] || die "could not resolve the latest jk version from $RELEASES_URL/latest/VERSION"
  ARCHIVE_URL="$RELEASES_URL/$VERSION/jk-$TARGET.$EXT"
  infer_decompress "$ARCHIVE_URL"
fi

# ---- download & install ----------------------------------------------------

TMPDIR_JK="$(mktemp -d "${TMPDIR:-/tmp}/jk-install.XXXXXX")"
cleanup() { rm -rf "$TMPDIR_JK"; }
trap cleanup EXIT

if [ -n "$LOCAL_FILE" ]; then
  ARCHIVE_FILE="$LOCAL_FILE"
  info "Installing jk from $LOCAL_FILE"
else
  ARCHIVE_FILE="$TMPDIR_JK/jk.archive"
  info "Downloading jk from $ARCHIVE_URL"
  download "$ARCHIVE_URL" "$ARCHIVE_FILE" \
    || die "failed to download $ARCHIVE_URL"
fi

info "Installing into $INSTALL_DIR"
mkdir -p "$INSTALL_DIR"

JK_BIN="$INSTALL_DIR/jk"
# A prior install leaves bin/jk as a symlink into ~/.jk/versions/<v>/ — remove
# it first so decompress writes a fresh file instead of THROUGH the link into
# the immutable versions tree.
rm -f "$JK_BIN"
decompress "$ARCHIVE_FILE" "$JK_BIN" \
  || die "failed to install jk"
chmod +x "$JK_BIN"

note "Installed $JK_BIN"

# `jkx` — uvx-style alias for `jk tool run`, shipped as a real executable so
# `#!/usr/bin/env jkx` shebangs and CI steps work without shell integration.
# A hardlink to the jk binary (argv[0] dispatch; zero extra disk); `ln -f`
# also refreshes a stale jkx left by a previous install. Falls back to an
# exec shim when the filesystem refuses hardlinks.
JKX_BIN="$INSTALL_DIR/jkx"
rm -f "$JKX_BIN"
if ln -f "$JK_BIN" "$JKX_BIN" 2>/dev/null; then
  note "Installed $JKX_BIN"
else
  printf '#!/bin/sh\n# jkx — `jk tool run` launcher (generated by jk; do not edit)\nexec "%s" tool run "$@"\n' "$JK_BIN" > "$JKX_BIN" \
    || die "failed to install jkx"
  chmod +x "$JKX_BIN"
  note "Installed $JKX_BIN (shim)"
fi

# The engine ships as a single fat jar, jk-engine-<version>.jar (see
# docs/engine.md "Two artifacts"; the engine is a JVM app, not a second
# binary). It lives ONLY in the side-by-side version layout,
# ~/.jk/versions/<v>/lib/jk-engine.jar — materialized below for local dists;
# download installs self-fetch it on first engine spawn.
if [ -n "$LOCAL_FILE" ]; then
  SRC_LIB="$(cd "$(dirname "$LOCAL_FILE")" && pwd)/lib"
fi

# ---- side-by-side version layout (docs/engine-versioning-plan.md R2) --------
#
# Local dist installs (binary + engine jar together) also materialize
# ~/.jk/versions/<v>/ — through the client itself (`jk self materialize`), which
# ingests both artifacts into the CAS first. The CAS stays the single source of
# truth: a pruned version re-materializes from its blobs, offline. (A hand-rolled
# shell copy here once skipped the CAS and left pruned local installs
# unrecoverable.) Download installs skip this: the client self-fetches its
# engine jar on first spawn and materializes then. Best-effort by design.
if [ -n "$LOCAL_FILE" ]; then
  ENGINE_JAR=""
  for f in "$SRC_LIB"/jk-engine-*.jar; do
    [ -f "$f" ] && ENGINE_JAR="$f" && break
  done
  if [ -n "$ENGINE_JAR" ]; then
    if run_jk self materialize "$JK_BIN" "$ENGINE_JAR" >/dev/null 2>&1; then
      note "Materialized $JK_HOME/versions/$(run_jk --version 2>/dev/null | awk '{print $2}')"
    else
      note "versions/ materialization skipped (jk self materialize failed; the client re-fetches on demand)"
    fi
  fi
fi

# ---- activate --------------------------------------------------------------

info "Running jk activate"
# Best-effort: activate needs a tty to pick a shell (CI/pipe installs have
# none) and its failure must not abort the warm-up and bin/ consolidation
# below — the install itself is already complete.
run_jk activate || note "'jk activate' failed (no tty?); run 'jk activate <shell>' manually."

# ---- warm the engine -------------------------------------------------------
#
# Pre-pay the engine's cold-start costs now so the first real build doesn't:
# `jk engine start` installs the JDK that hosts the engine when none
# qualifies, and on a download install triggers the client's own engine-jar
# fetch (which also completes ~/.jk/versions/<v>/ — jar, manifest, AND this
# client binary). The engine serves immediately and manages its own AOT
# training sidecar off to the side (docs/engine.md), so ONE start is the
# whole warm-up — no stop/restart dance. Best-effort by design: a failed
# warm-up never fails the install (the engine starts lazily on first use
# either way). Skipped only for a local dist install that carried no engine
# jar — a -SNAPSHOT client won't self-fetch.
if [ -z "$LOCAL_FILE" ] || [ -n "${ENGINE_JAR:-}" ]; then
  info "Warming up the build engine (may download the engine and a JDK on first run)"
  if run_jk engine start >/dev/null 2>&1; then
    note "Engine started (AOT cache trains in the background)"
  else
    note "Engine warm-up skipped; it will start on first build"
  fi
fi

# ---- one home for the bits ---------------------------------------------------
#
# Every installed jk — including this one — lives in ~/.jk/versions/<v>/
# (materialized above for local dists, by the engine-jar fetch during warm-up
# for downloads). bin/jk and bin/jkx become SYMLINKS to the current version's
# binary — the same end state `jk self update` leaves with its atomic symlink
# flip, so the initial install, a pin, and an update are one consistent story.
# Falls back to keeping the real copies when versions/ didn't materialize or
# the filesystem refuses symlinks; the first `jk self update` converges it.
VERSION="$(run_jk --version 2>/dev/null | awk '{print $2}')"
VBIN="$JK_HOME/versions/${VERSION:-none}/bin/jk"
if [ -n "$VERSION" ] && [ -x "$VBIN" ]; then
  if ln -sfn "$VBIN" "$INSTALL_DIR/.jk-new" 2>/dev/null \
      && mv -f "$INSTALL_DIR/.jk-new" "$JK_BIN" 2>/dev/null \
      && ln -sfn "$VBIN" "$INSTALL_DIR/.jkx-new" 2>/dev/null \
      && mv -f "$INSTALL_DIR/.jkx-new" "$JKX_BIN" 2>/dev/null; then
    note "Linked $JK_BIN -> $VBIN"
  else
    rm -f "$INSTALL_DIR/.jk-new" "$INSTALL_DIR/.jkx-new"
    note "bin/ kept as real copies (no symlink support); 'jk self update' converges it"
  fi
fi

printf '\n%s%sjk installed successfully.%s\n\n' "$BOLD" "$GREEN" "$RESET"

# ---- restart shell ---------------------------------------------------------
#
# Three cases:
#   1. Real interactive shell (fd 0 + fd 1 are ttys): exec $SHELL directly.
#   2. `curl … | bash` in a terminal (fd 0 is the pipe, but /dev/tty is
#      reachable → INTERACTIVE): don't hijack the terminal — ask first, and if
#      yes exec with stdin rebound to the terminal so the new shell is usable.
#   3. No terminal (CI, Docker build, cron, NO_COLOR pipe): just print how to
#      reload; exec'ing here would hang or detach.
RELOAD_HINT="Open a new terminal or run 'exec \$SHELL' to start using jk."

if [ -t 0 ] && [ -t 1 ] && [ -n "${SHELL:-}" ]; then
  note "Restarting your shell ($SHELL) to apply changes..."
  exec "$SHELL"
elif [ "$INTERACTIVE" = 1 ] && [ -n "${SHELL:-}" ]; then
  printf '%s    Reload your shell (%s) now to apply changes? [Y/n] %s' "$DIM" "$SHELL" "$RESET"
  read -r reply </dev/tty || reply=""
  case "$reply" in
    [Nn]*) note "$RELOAD_HINT" ;;
    *)     note "Restarting your shell ($SHELL)..."; exec "$SHELL" </dev/tty ;;
  esac
else
  note "$RELOAD_HINT"
fi
