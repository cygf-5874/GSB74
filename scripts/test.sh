#!/usr/bin/env bash
# 跑既有用例 src/test/java/objcodec/CodecTest.java。起点应 12/12 全绿。
set -euo pipefail

cd "$(dirname "$0")/.."

bash scripts/build.sh >/dev/null

java -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 \
    -cp out objcodec.CodecTest
