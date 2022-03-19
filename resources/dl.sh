#!/usr/bin/env bash

set -eo pipefail

if [ $# -ne 2 ]
then
    echo "usage: $0 URL OUTPUT_PATH"
    exit 1
fi

URL="$1"
OUTPUT_PATH="$2"

WORKDIR=$(mktemp -d)
trap "rm -rf $WORKDIR" EXIT

pushd "$WORKDIR" > /dev/null

python3 -m venv e
e/bin/pip --no-color --no-cache-dir --disable-pip-version-check install --progress-bar=off yt-dlp
e/bin/yt-dlp -x --audio-quality=0 --no-simulate -O 'id: %(id)s' -O 'title: %(title)s' -O 'original_url: %(original_url)s' --exec echo --newline --no-colors "$URL" | tee log

OUTFILE=$(tail -n 1 log)

popd > /dev/null

mv "$OUTFILE" "$OUTPUT_PATH"

basename "$OUTFILE"
