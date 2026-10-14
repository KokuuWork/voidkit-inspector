#!/usr/bin/env bash
# Voidkit Inspector —— Forge 1.21.1 端到端验证（发布态 jar + 真服务端）
#
# 覆盖四件事，每一件都对应一个**编译过但真机才炸**的坑：
#   1. 发布态 jar 在真正的 Forge 服务端上能否被识别为合法 mod
#      （防 mods.toml 被剥掉 / 写错导致**静默跳过** —— 服务端照常启动，
#       就是没这个 mod，极易误判成 E2E 通过）
#   2. jarJar 内嵌 sqlite 原生库在 JPMS 模块路径下不炸（DB 能建起来）
#   3. voidkit_core 的 versionRange 真的匹配得上（用 core_version 独立声明，
#      早前误用 mod_version 会得到永不匹配的区间 → mod 被跳过）
#   4. 事件桥真的被调用（建库 + 落表 = InspectorFeature 已注册）
#
# 用法：
#   tools/e2e-forge-server.sh                    # 默认 build/e2e-forge-1211
#   SERVER_DIR=/path tools/e2e-forge-server.sh   # 指定已装好的 Forge 服务端目录
#
# 前置：目录里已 `java -jar forge-installer.jar --installServer .`
set -u

cd "$(dirname "$0")/.."
PROJECT="$(pwd)"

SERVER_DIR="${SERVER_DIR:-$PROJECT/build/e2e-forge-1211}"
LOG="$SERVER_DIR/server.log"

case "$SERVER_DIR" in
  /*) SERVER_ABS="$SERVER_DIR" ;;
  *)  SERVER_ABS="$PROJECT/$SERVER_DIR" ;;
esac

# 1.21.1 必须 JDK 21
JAVA_BIN=""
for c in "C:/Users/26703/.jdks/jdk-21.0.12.1+1/bin/java.exe" \
         "C:/Users/26703/.jdks/jdk-21.0.12.1+1/bin/java" java; do
  if [ -f "$c" ] || command -v "$c" >/dev/null 2>&1; then JAVA_BIN="$c"; break; fi
done
if [ -z "$JAVA_BIN" ]; then
  echo "[e2e] 找不到 java（1.21.1 需要 JDK 21）" >&2
  exit 2
fi

if [ ! -d "$SERVER_ABS" ]; then
  echo "[e2e] 目录不存在：$SERVER_ABS" >&2
  echo "      先装服务端：java -jar forge-installer.jar --installServer ." >&2
  exit 2
fi

# Forge 1.21.1 的启动参数文件（不是 NeoForge 的 win_args.txt）
ARGS="$SERVER_ABS/libraries/net/minecraftforge/forge/1.21.1-52.1.19/unix_args.txt"
if [ ! -f "$ARGS" ]; then
  ARGS="$SERVER_ABS/libraries/net/minecraftforge/forge/1.21.1-52.1.19/win_args.txt"
fi
if [ ! -f "$ARGS" ]; then
  echo "[e2e] 找不到 Forge 启动参数：$SERVER_ABS/libraries/net/minecraftforge/forge/1.21.1-52.1.19/*_args.txt（服务端没装好？）" >&2
  exit 2
fi

# ---------- 前置检查：mods/ 必须是 2 个「发布态」jar ----------
echo "[e2e] 服务端目录：$SERVER_ABS"
echo "[e2e] 启动参数：$(basename "$ARGS")"
for want in voidkit_core voidkit_inspector; do
  n=$(ls "$SERVER_ABS"/mods/${want}-*.jar 2>/dev/null | wc -l)
  echo "[e2e] mods/ ${want}: $n 个"
  if [ "$n" != "1" ]; then
    echo "[e2e] ❌ ${want} jar 数不对（应为 1，检查是否混入旧版本）" >&2
    exit 2
  fi
done

# 结构校验：inspector jar 必须同时含 mod 元数据 + jarJar payload + 自己的类。
# 三项缺任意一项都说明构建产物损坏。
if command -v unzip >/dev/null 2>&1; then
  PROBE=$(ls "$SERVER_ABS"/mods/voidkit_inspector-*.jar | head -1)
  for must in META-INF/mods.toml \
              org/voidkit/inspector/InspectorFeature.class \
              org/voidkit/inspector/platform/forge/InspectorEvents.class \
              META-INF/jarjar/metadata.json; do
    if unzip -l "$PROBE" "$must" >/dev/null 2>&1; then
      echo "[e2e] ✅ inspector jar 含 $must"
    else
      echo "[e2e] ❌ inspector jar 缺少 $must（构建产物不完整，禁止起服）" >&2
      exit 2
    fi
  done
  # ⚠️ 这条分支的 jar **绝不能**含 NeoForge 的元数据与平台层 ——
  #    两个加载器同时装（或 Forge 装了 NeoForge 的 jar）会被静默跳过。
  if unzip -l "$PROBE" "META-INF/neoforge.mods.toml" >/dev/null 2>&1; then
    echo "[e2e] ❌ Forge 版 jar 里混进了 neoforge.mods.toml（分支串了）" >&2
    exit 2
  fi
  if unzip -l "$PROBE" "org/voidkit/inspector/platform/neoforge/InspectorEvents.class" >/dev/null 2>&1; then
    echo "[e2e] ❌ Forge 版 jar 里混进了 neoforge 平台层（分支串了）" >&2
    exit 2
  fi
  # core 的版本区间必须是 core_version，不能是本插件自己的版本号
  echo "[e2e] --- inspector 的 mods.toml 依赖声明 ---"
  unzip -p "$PROBE" META-INF/mods.toml | grep -A3 'modId = "voidkit_core"' || true
fi

# ---------- 启动 ----------
[ -f "$SERVER_ABS/eula.txt" ] || printf 'eula=true\n' > "$SERVER_ABS/eula.txt"
: > "$LOG"
echo "[e2e] 启动服务端…"
cd "$SERVER_ABS"
"$JAVA_BIN" -Xmx2G @user_jvm_args.txt "@$ARGS" nogui > "$LOG" 2>&1 &
SRV_PID=$!
cd "$PROJECT"
echo "[e2e] 服务端 pid=$SRV_PID"

READY=0
CRASH=0
ROUND=0
for i in $(seq 1 48); do
  ROUND=$i
  sleep 5
  if grep -qa 'Done (' "$LOG" 2>/dev/null; then READY=1; break; fi
  if grep -qaE 'Failed to start|Encountered an unexpected exception|\[Server thread/FATAL\]|Mod loading has failed|A fatal error|NoClassDefFoundError|NoSuchMethodError|ResolutionException' "$LOG" 2>/dev/null; then
    CRASH=1
    break
  fi
done
echo "[e2e] 等待 $((ROUND * 5))s：ready=$READY crash=$CRASH"

echo ""
echo "=================== 模组清单（必须含 VoidkitInspector）==================="
grep -aE 'Voidkit|voidkit' "$LOG" 2>/dev/null | head -25

echo ""
echo "=================== 致命错误（应为空）==================="
if grep -qaE 'NoSuchMethodError|NoClassDefFoundError|InvalidModFileException|ModLoadingException|Failed to create mod instance|ResolutionException|not a valid mod file|\[Server thread/FATAL\]|Encountered an unexpected exception' "$LOG" 2>/dev/null; then
  grep -aE 'NoSuchMethodError|NoClassDefFoundError|InvalidModFileException|ModLoadingException|Failed to create mod instance|ResolutionException|not a valid mod file|\[Server thread/FATAL\]|Encountered an unexpected exception' "$LOG" | head -20
  CRASH=1
else
  echo "（无）"
fi

# ---------- 强判据：inspector 必须真的被 core 识别为已注册模块 ----------
#
# ⚠️ **Forge 52 的服务端日志不打印模组清单**（NeoForge 会打
#    `VoidkitInspector <版本> (voidkit_inspector)`，Forge 没有），
#    所以不能照抄 guard 的 NeoForge 判据 —— 那样会永远假失败。
#
# 用两条**只能由 inspector 真的跑起来**才会出现的日志：
#   1. core 在注册命令时报告 active 模块含 `inspector`
#   2. InspectorFeature 启动时打印「审计数据库就绪」
# 反面情形（mods.toml 写错被静默跳过）这两条都不会出现 ——
# 实测踩过：Skip 发生时只有 core 的日志，Done 照样出现，最容易误判成通过。
if ! grep -qaE '注册命令，active 模块 = \[[^]]*inspector' "$LOG" 2>/dev/null; then
  echo "[e2e] ❌ core 的 active 模块里没有 inspector（mod 被加载器跳过？）" >&2
  CRASH=1
else
  echo "[e2e] ✅ core 已把 inspector 识别为已注册模块"
fi

if ! grep -qa '审计数据库就绪' "$LOG" 2>/dev/null; then
  echo "[e2e] ❌ 日志里没有「审计数据库就绪」（InspectorFeature 没跑到建库？）" >&2
  CRASH=1
else
  echo "[e2e] ✅ 审计数据库已就绪（jarJar 的 SQLite 原生库可用）"
fi

# 事件桥是否注册（间接证明：模块已启用 + 命令已注册）
if grep -qaE 'voidkit_inspector\(1\)' "$LOG" 2>/dev/null; then
  echo "[e2e] ✅ 模块统计里 voidkit_inspector 计数为 1"
fi

# 功能判据：inspector 启动时应落配置 + 建 SQLite 库
if [ ! -d "$SERVER_ABS/voidkit/inspector" ]; then
  echo "[e2e] ❌ 未生成 voidkit/inspector 数据目录（配置/库未落盘，模块没起来？）" >&2
  CRASH=1
else
  echo "[e2e] ✅ voidkit/inspector 数据目录已生成"
  ls -la "$SERVER_ABS/voidkit/inspector" | sed 's/^/[e2e]   /'
fi

# ---------- 收尾 ----------
echo ""
echo "=================== 收尾 ==================="
if taskkill //F //PID "$SRV_PID" >/dev/null 2>&1; then
  echo "已终止服务端 pid=$SRV_PID"
else
  kill "$SRV_PID" >/dev/null 2>&1 && echo "已终止服务端 pid=$SRV_PID"
  echo "（pid=$SRV_PID 已不在，可能已自行退出）"
fi

echo ""
if [ "$READY" != "1" ] || [ "$CRASH" = "1" ]; then
  echo "[e2e] ❌ 失败：正式服未正常启动或出现致命错误（见上）"
  exit 1
fi
echo "[e2e] ✅ 通过：发布产物在真正的 Forge 1.21.1 服务端上加载正常，审计库已建"
