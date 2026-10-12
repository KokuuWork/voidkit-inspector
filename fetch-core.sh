#!/usr/bin/env bash
# 从 voidkit 仓库的 GitHub Release 下载 voidkit_core 前置 jar 到 libs/
#
# 用法：./fetch-core.sh [版本]     # 默认 1.2.0
set -e
cd "$(dirname "$0")"

CORE_VERSION="${1:-1.2.0}"
mkdir -p libs

if command -v gh >/dev/null 2>&1; then
  echo "[fetch-core] 用 gh 下载 voidkit_core ${CORE_VERSION} ..."
  gh release download "v${CORE_VERSION}" \
    --repo KokuuWork/voidkit \
    --pattern 'voidkit_core-*.jar' \
    --dir libs/ \
    --clobber
else
  echo "[fetch-core] 需要 gh CLI（或用浏览器从 voidkit Release 手动下载 core jar 到 libs/）"
  exit 1
fi

echo "[fetch-core] 完成："
ls -la libs/
