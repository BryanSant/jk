#!/usr/bin/env bash
#
# jk installer
#
# Usage:
#   curl -fsSL https://jkbuild.dev/install.sh | bash
#   wget -qO- https://jkbuild.dev/install.sh | bash
#
# Environment variables:
#   JK_ARCHIVE_URL  Override the archive URL to download. When set, the
#                   compression format is inferred from the file extension
#                   (.xz or .gz). Defaults to the latest release matching the
#                   compressor available on this machine.
#   JK_INSTALL_DIR  Override the install directory (default: ~/.jk/bin).
#
set -euo pipefail

JK_HOME="${JK_HOME:-$HOME/.jk}"
INSTALL_DIR="${JK_INSTALL_DIR:-$JK_HOME/bin}"
BASE_URL="https://jkbuild.dev/releases/latest"

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

# Pick a downloader.
if have curl; then
  download() { curl -fsSL "$1" -o "$2"; }
elif have wget; then
  download() { wget -q "$1" -O "$2"; }
else
  die "neither curl nor wget found on PATH; cannot download jk."
fi

# Pick a decompressor. Prefer xz; fall back to gunzip.
if have xz; then
  COMPRESSOR="xz"
  EXT="xz"
  decompress() { xz -dc "$1" > "$2"; }
elif have gunzip; then
  COMPRESSOR="gunzip"
  EXT="gz"
  decompress() { gunzip -c "$1" > "$2"; }
else
  die "neither xz nor gunzip found on PATH; cannot decompress the archive."
fi

# ---- resolve archive URL ---------------------------------------------------

if [ -n "${JK_ARCHIVE_URL:-}" ]; then
  ARCHIVE_URL="$JK_ARCHIVE_URL"
  case "$ARCHIVE_URL" in
    *.xz)
      have xz || die "JK_ARCHIVE_URL points at a .xz archive but xz is not installed."
      decompress() { xz -dc "$1" > "$2"; } ;;
    *.gz)
      have gunzip || die "JK_ARCHIVE_URL points at a .gz archive but gunzip is not installed."
      decompress() { gunzip -c "$1" > "$2"; } ;;
    *)
      note "Unknown archive extension; using $COMPRESSOR to decompress." ;;
  esac
else
  ARCHIVE_URL="$BASE_URL/jk.$EXT"
fi

# ---- download & install ----------------------------------------------------

TMPDIR_JK="$(mktemp -d "${TMPDIR:-/tmp}/jk-install.XXXXXX")"
cleanup() { rm -rf "$TMPDIR_JK"; }
trap cleanup EXIT

ARCHIVE_FILE="$TMPDIR_JK/jk.archive"

info "Downloading jk from $ARCHIVE_URL"
download "$ARCHIVE_URL" "$ARCHIVE_FILE" \
  || die "failed to download $ARCHIVE_URL"

info "Installing into $INSTALL_DIR"
mkdir -p "$INSTALL_DIR"

JK_BIN="$INSTALL_DIR/jk"
decompress "$ARCHIVE_FILE" "$JK_BIN" \
  || die "failed to decompress archive with $COMPRESSOR"
chmod +x "$JK_BIN"

note "Installed $JK_BIN"

# ---- install a default JDK -------------------------------------------------
#
# Pull the latest JDK and mark it the system-wide default so the freshly
# activated shell has a working `java` immediately. Done before `jk activate`
# so the shim and PATH wiring land on top of a populated default.

info "Installing the latest JDK and setting it as default"
if "$JK_BIN" jdk install latest --make-default; then
  note "Default JDK ready"
else
  die "'jk jdk install latest --make-default' failed."
fi

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
