#!/usr/bin/env bash
#
# jk installer
#
# Usage:
#   curl -fsSL https://jkbuild.dev/install.sh | bash
#   wget -qO- https://jkbuild.dev/install.sh | bash
#   bash install.sh [/path/to/jk[.xz|.gz]]
#
# Environment variables:
#   JK_ARCHIVE_URL  Override the archive URL to download. Supports .xz, .gz,
#                   or a plain binary (no extension). Defaults to the latest
#                   release matching the compressor available on this machine.
#   JK_INSTALL_DIR  Override the install directory (default: ~/.jk/bin).
#
set -euo pipefail

JK_HOME="${JK_HOME:-$HOME/.jk}"
INSTALL_DIR="${JK_INSTALL_DIR:-$JK_HOME/bin}"
BASE_URL="https://jkbuild.dev/releases/latest"

# Optional positional argument: local path to jk, jk.xz, or jk.gz.
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
  elif have wget; then
    download() { wget -q "$1" -O "$2"; }
  else
    die "neither curl nor wget found on PATH; cannot download jk."
  fi
fi

# Preferred extension for auto URL resolution: xz > gz > plain binary.
if have xz; then
  EXT="xz"
elif have gunzip; then
  EXT="gz"
else
  EXT=""
fi

# ---- resolve source (URL or local file) ------------------------------------

# Sets decompress() based on the file/URL extension.
# Plain binary (no .xz/.gz) is installed with cp.
infer_decompress() {
  case "$1" in
    *.xz)
      have xz || die "'$1' is a .xz file but xz is not installed."
      decompress() { xz -dc "$1" > "$2"; } ;;
    *.gz)
      have gunzip || die "'$1' is a .gz file but gunzip is not installed."
      decompress() { gunzip -c "$1" > "$2"; } ;;
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
  ARCHIVE_URL="$BASE_URL/jk${EXT:+.$EXT}"
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

# ---- activate --------------------------------------------------------------

info "Running jk activate"
if "$JK_BIN" activate; then
  printf '\n%s%sjk installed successfully.%s\n\n' "$BOLD" "$GREEN" "$RESET"
else
  die "'jk activate' failed; not restarting shell."
fi

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
