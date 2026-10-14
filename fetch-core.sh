#!/usr/bin/env bash
# 从 voidkit 仓库的 GitHub Release 下载 voidkit_core 前置 jar 到 libs/
#
# 用法：
#   ./fetch-core.sh                                # 按当前分支自动推断版本线
#   ./fetch-core.sh 0.1.0-beta.1 forge# 指定模组版本 + 加载器
#   ./fetch-core.sh 0.1.0-beta.1 1.20.1 forge      # 模组版本 + MC 版本 + 加载器
#
# ⚠️ 两个必须注意的事实（都踩过）：
#   1. core 的 release tag 是 **0.1.0-beta.1**，**没有 `v` 前缀**。
#      `gh release download v0.1.0-beta.1` 会报 "release not found"。
#   2. 产物命名规格是「模组名-模组版本+mc版本-加载器」，
#      按精确文件名下载，避免多版本线并存时误取。
#
# ⚠️ **加载器必须与当前分支匹配**：core 的 jar 内含加载器 API，
#    NeoForge 版与 Forge 版不能互相顶替（编译期就会炸）。
#    main → 1.21.1/neoforge；mc/1.21.1-forge → 1.21.1/forge；mc/1.20.1 → 1.20.1/forge。
#    下面按当前分支自动推断，手写参数可覆盖。
set -e
cd "$(dirname "$0")"

CORE_VERSION="${1:-0.1.0-beta.1}"

# 按分支推断默认的 MC 版本 + 加载器（显式传参优先）
BRANCH="$(git branch --show-current 2>/dev/null || echo main)"
case "$BRANCH" in
  mc/1.20.1)      DEF_MC="1.20.1"; DEF_LOADER="forge" ;;
  mc/1.21.1-forge) DEF_MC="1.21.1"; DEF_LOADER="forge" ;;
  *)             DEF_MC="1.21.1"; DEF_LOADER="neoforge" ;;
esac

LOADER="${2:-$DEF_LOADER}"
# 兼容三参写法：./fetch-core.sh <ver> <mc> <loader>
if [ "$#" -ge 3 ]; then
  MC_VERSION="$2"
  LOADER="$3"
else
  MC_VERSION="$DEF_MC"
fi

JAR_NAME="voidkit_core-${CORE_VERSION}+mc${MC_VERSION}-${LOADER}.jar"
mkdir -p libs

if command -v gh >/dev/null 2>&1; then
  echo "[fetch-core] 下载 ${JAR_NAME}（release ${CORE_VERSION}）..."
  gh release download "${CORE_VERSION}" \
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
