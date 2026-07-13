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

# ---- pretty output ---------------------------------------------------------

if [ -t 1 ] && [ -z "${NO_COLOR:-}" ]; then
  BOLD=$'\033[1m'; RED=$'\033[31m'; GREEN=$'\033[32m'; DIM=$'\033[2m'; RESET=$'\033[0m'
else
  BOLD=""; RED=""; GREEN=""; DIM=""; RESET=""
fi

info()  { printf '%s==>%s %s\n' "$GREEN" "$RESET" "$*"; }
note()  { printf '%s    %s%s\n' "$DIM" "$*" "$RESET"; }
err()   { printf '%serror:%s %s\n' "$RED" "$RESET" "$*" >&2; }
die()   { err "$@"; exit 1; }

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
if ln -f "$JK_BIN" "$JKX_BIN" 2>/dev/null; then
  note "Installed $JKX_BIN"
else
  printf '#!/bin/sh\n# jkx — `jk tool run` launcher (generated by jk; do not edit)\nexec "%s" tool run "$@"\n' "$JK_BIN" > "$JKX_BIN" \
    || die "failed to install jkx"
  chmod +x "$JKX_BIN"
  note "Installed $JKX_BIN (shim)"
fi

# The engine ships as a single fat jar, jk-engine-<version>.jar, installed to
# ~/.jk/lib (see docs/engine.md "Two artifacts"; the engine is a JVM app, not a
# second binary — the client only launches a jar whose version matches its
# own). A local install from a dist layout carries it along; the download flow
# doesn't fetch it here at all — the client downloads its own checksum-verified
# engine jar the first time it spawns one (the warm-up below triggers that).
if [ -n "$LOCAL_FILE" ]; then
  SRC_LIB="$(cd "$(dirname "$LOCAL_FILE")" && pwd)/lib"
  if compgen -G "$SRC_LIB/jk-engine-*.jar" > /dev/null; then
    mkdir -p "$JK_HOME/lib"
    rm -f "$JK_HOME/lib"/jk-engine-*.jar
    cp "$SRC_LIB"/jk-engine-*.jar "$JK_HOME/lib/"
    note "Installed $JK_HOME/lib/$(basename "$SRC_LIB"/jk-engine-*.jar)"
  fi
fi

# ---- side-by-side version layout (docs/engine-versioning-plan.md R2) --------
#
# Local dist installs (binary + engine jar together) also materialize
# ~/.jk/versions/<v>/ — the layout the client, `jk self update`, and the project
# wrapper share. Download installs skip this: the client self-fetches its engine
# jar on first spawn and materializes then. Best-effort by design.
JK_ACTUAL_VERSION="$("$JK_BIN" --version 2>/dev/null | awk '{print $2}')"
if [ -n "$JK_ACTUAL_VERSION" ] && compgen -G "$JK_HOME/lib/jk-engine-*.jar" > /dev/null; then
  VDIR="$JK_HOME/versions/$JK_ACTUAL_VERSION"
  VTMP="$JK_HOME/versions/.install-$$"
  sha256() { (sha256sum "$1" 2>/dev/null || shasum -a 256 "$1" 2>/dev/null) | awk '{print $1}'; }
  if mkdir -p "$VTMP/bin" "$VTMP/lib" 2>/dev/null \
      && cp "$JK_BIN" "$VTMP/bin/jk" && chmod +x "$VTMP/bin/jk" \
      && cp "$JK_HOME"/lib/jk-engine-*.jar "$VTMP/lib/jk-engine.jar"; then
    {
      echo "version = \"$JK_ACTUAL_VERSION\""
      echo "engine-sha256 = \"$(sha256 "$VTMP/lib/jk-engine.jar")\""
      echo "client-sha256 = \"$(sha256 "$VTMP/bin/jk")\""
      echo "protocol = 1"
    } > "$VTMP/manifest.toml"
    rm -rf "$VDIR"
    if mv "$VTMP" "$VDIR" 2>/dev/null; then
      note "Materialized $VDIR"
    fi
  fi
  rm -rf "$VTMP" 2>/dev/null || true
fi

# ---- activate --------------------------------------------------------------

info "Running jk activate"
"$JK_BIN" activate || die "'jk activate' failed; not restarting shell."

# ---- warm the engine -------------------------------------------------------
#
# Pre-pay the engine's cold-start costs now so the first real build doesn't:
# `jk engine start` installs the JDK that hosts the engine when none
# qualifies, and — with no AOT cache on disk yet — spawns the engine as a
# JEP 514 *training* run (-XX:AOTCacheOutput). The .aot file is assembled
# when that engine exits cleanly, so start → stop leaves
# ~/.jk/state/engine/engine-<key>.aot pre-prepared, and the final start
# leaves a warm resident engine already mapping it. On a download install
# the first start also triggers the client's own engine-jar fetch, so this
# one step materialises the jar, the JDK, and the AOT cache. Best-effort by
# design: a failed warm-up never fails the install (the engine starts
# lazily on first use either way). Skipped only for a local dist install
# that carried no engine jar — a -SNAPSHOT client won't self-fetch.
await_aot_cache() {
  # The .aot is assembled during the stopped engine's JVM shutdown; give it a
  # moment so the warm restart below maps the cache instead of retraining.
  for _ in $(seq 1 20); do
    compgen -G "${JK_STATE_DIR:-$JK_HOME/state}/engine/engine-*.aot" > /dev/null && return 0
    sleep 0.5
  done
  return 1
}

if [ -z "$LOCAL_FILE" ] || compgen -G "$JK_HOME/lib/jk-engine-*.jar" > /dev/null; then
  info "Warming up the build engine (may download the engine and a JDK on first run)"
  # The settle between start and stop matters: a stop issued within the
  # engine's first second yields an empty training dump and the assembly
  # child rejects it (verified against JDK 25).
  if "$JK_BIN" engine start >/dev/null 2>&1 \
      && sleep 3 \
      && "$JK_BIN" engine stop >/dev/null 2>&1 \
      && await_aot_cache \
      && "$JK_BIN" engine start >/dev/null 2>&1; then
    note "Engine started; AOT startup cache prepared"
  else
    note "Engine warm-up skipped; it will start on first build"
  fi
fi

printf '\n%s%sjk installed successfully.%s\n\n' "$BOLD" "$GREEN" "$RESET"

# ---- restart shell ---------------------------------------------------------
#
# Only exec a new shell when running interactively. When the script is piped
# from curl|bash in a non-interactive context, exec'ing $SHELL would hang or
# detach, so we just tell the user how to reload.

if [ -t 0 ] && [ -t 1 ] && [ -n "${SHELL:-}" ]; then
  note "Restarting your shell ($SHELL) to apply changes..."
  exec "$SHELL"
else
  note "Open a new terminal or run 'exec \$SHELL' to start using jk."
fi
