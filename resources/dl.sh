#!/usr/bin/env bash

set -eo pipefail

if [ $# -ne 1 ] && [ $# -ne 2 ]
then
    echo "usage: $0 URL [OUTPUT_DIR]"
    exit 1
fi

URL="$1"
OUTPUT_PATH="${2-.}"

if [[ "$URL" =~ soundcloud\.com/[^/]*/sets ]]; then
    >&2 echo "$0: downloading entire playlists is not supported yet"
    exit 1
fi

WORKDIR_LINK="${TMPDIR:-/tmp}/ytdl-workdir"

download() {
    REUSE_WORKDIR="$1"

    if [ "$REUSE_WORKDIR" -eq 1 ] && [ -d "$WORKDIR_LINK" ]; then
        echo "reusing existing workdir"
        pushd "$WORKDIR_LINK" > /dev/null
    else
        echo "using a fresh workdir"

        if [ ! -z "$(readlink $WORKDIR_LINK)" ]; then
            rm -rf "$(readlink $WORKDIR_LINK)"
        fi

        rm -f "$WORKDIR_LINK"
        ln -s "$(mktemp --directory --tmpdir ytdl-workdir-XXXXXXX)" "$WORKDIR_LINK"

        pushd "$WORKDIR_LINK" > /dev/null

        python3 -m venv e
        e/bin/pip --no-color --no-cache-dir --disable-pip-version-check install --progress-bar=off yt-dlp

        REUSE_WORKDIR=0
    fi

    echo "timestamp is $(date -Is)"
    if e/bin/yt-dlp --flat-playlist -x --audio-quality=0 --no-simulate -O 'id: %(id)s' -O 'title: %(title)s' -O 'original_url: %(original_url)s' --exec echo --newline --no-colors "$URL" | tee log; then
        echo "yt-dlp finished successfully"
    else
        if [ "$REUSE_WORKDIR" -eq 1 ]; then
            echo "yt-dlp failed, retrying with a fresh Python env"
            popd > /dev/null
            download 0
        else
            echo "yt-dlp failed unrecoverably"
            exit 1
        fi
    fi

    OUTFILE=$(tail -n 1 log)

    popd > /dev/null

    mv "$OUTFILE" "$OUTPUT_PATH"

    basename "$OUTFILE"
}

download 1
