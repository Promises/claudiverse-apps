#!/bin/sh
# Send one command to a Claudiverse built with the harness (build.sh --harness)
# and print its JSON reply. See Claudiverse/Harness.swift for the commands.
#
# Usage:  macos/harness.sh '{"cmd":"state"}'
#         CLAUDIVERSE_COPY=e2e macos/harness.sh ...   the copy built as tech.henning.claudiverse.e2e
set -eu
folder="Claudiverse${CLAUDIVERSE_COPY:+ $CLAUDIVERSE_COPY}"
sock="$HOME/Library/Application Support/$folder/harness.sock"
[ -S "$sock" ] || { echo "no harness socket: is a --harness build running?" >&2; exit 1; }
printf '%s\n' "$1" | nc -U "$sock"
