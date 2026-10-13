#!/usr/bin/env bash
# 从 voidkit 仓库的 GitHub Release 下载 voidkit_core 前置 jar 到 libs/
#
# 用法：
#   ./fetch-core.sh                          # 默认 1.0.0 / mc1.21.1 / neoforge
#   ./fetch-core.sh 1.0.0                    # 指定模组版本
#   ./fetch-core.sh 1.0.0 1.21.1 neoforge    # 模组版本 + MC 版本 + 加载器
#
# voidkit 的产物命名规格是「模组名-模组版本+mc版本-加载器」，
# 所以按精确文件名下载，避免多版本线并存时误取。
set -e
cd "$(dirname "$0")"

CORE_VERSION="${1:-1.0.0}"
MC_VERSION="${2:-1.21.1}"
LOADER="${3:-neoforge}"

JAR_NAME="voidkit_core-${CORE_VERSION}+mc${MC_VERSION}-${LOADER}.jar"
mkdir -p libs

if command -v gh >/dev/null 2>&1; then
  echo "[fetch-core] 下载 ${JAR_NAME}（release v${CORE_VERSION}）..."
  gh release download "v${CORE_VERSION}" \
    --repo KokuuWork/voidkit \
    --pattern "${JAR_NAME}" \
    --dir libs/ \
    --clobber
else
  echo "[fetch-core] 需要 gh CLI（或用浏览器从 voidkit Release 手动下载 core jar 到 libs/）"
  exit 1
fi

echo "[fetch-core] 完成："
ls -la libs/
