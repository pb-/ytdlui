#!/usr/bin/env bash

set -eo pipefail

if [ $# -ne 2 ]
then
    echo "usage: $0 URL OUTPUT_PATH"
    exit 1
fi

URL="$1"
OUTPUT_PATH="$2"

if [[ "$URL" =~ soundcloud\.com/[^/]*/sets ]]
then
    >&2 echo "$0: downloading entire playlists is not supported yet"
    exit 1
fi

WORKDIR=$(mktemp -d)
trap "rm -rf $WORKDIR" EXIT

pushd "$WORKDIR" > /dev/null

echo "Timestamp: $(date -Is)"
python3 -m venv e
e/bin/pip --no-color --no-cache-dir --disable-pip-version-check install --progress-bar=off yt-dlp
e/bin/yt-dlp --flat-playlist -x --audio-quality=0 --no-simulate -O 'id: %(id)s' -O 'title: %(title)s' -O 'original_url: %(original_url)s' --exec echo --newline --no-colors "$URL" | tee log

OUTFILE=$(tail -n 1 log)

popd > /dev/null

mv "$OUTFILE" "$OUTPUT_PATH"

basename "$OUTFILE"
