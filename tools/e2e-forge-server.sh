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

# ⚠️ 绝对路径判定要同时认 POSIX（/xxx）与 Windows 盘符（C:/xxx）——
#    Git Bash 下 SERVER_DIR 两种形态都可能出现，只判 `/` 会把盘符路径
#    拼成「$PROJECT/C:/...」然后报「目录不存在」。
case "$SERVER_DIR" in
  /*|[A-Za-z]:/*|[A-Za-z]:\\*) SERVER_ABS="$SERVER_DIR" ;;
  *)                        SERVER_ABS="$PROJECT/$SERVER_DIR" ;;
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

# Forge 1.21.1 的启动参数文件（不是 NeoForge 那种单一 win_args.txt）
#
# ⚠️ **必须优先 win_args.txt**：本脚本只在 Windows 上跑（本机没 WSL），
#    而 `unix_args.txt` 里的 classpath 用 `:` 分隔，Windows 的 java 认 `;`，
#    拿 unix_args 启动会在 boot layer 就炸：
#      `java.nio.file.InvalidPathException: Illegal char <:> at index 70: ...`
#    —— 服务端**根本没起来**，但错误发生在 mod 加载之前，
#    很容易误判成「模组有问题」。两个文件都存在时一律选 win。
FORGE_DIR="$SERVER_ABS/libraries/net/minecraftforge/forge/1.21.1-52.1.19"
ARGS="$FORGE_DIR/win_args.txt"
if [ ! -f "$ARGS" ]; then
  ARGS="$FORGE_DIR/unix_args.txt"
fi
if [ ! -f "$ARGS" ]; then
  echo "[e2e] 找不到 Forge 启动参数：$FORGE_DIR/*_args.txt（服务端没装好？）" >&2
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
# ⚠️ **判据一律只用 ASCII 锚点**：服务端日志的中文编码不固定
#    （同版本下 GBK / UTF-8 都见过），grep 中文会随机匹配不上。
#    用 core 的模块计数 `voidkit_inspector(1)` + 审计库文件实证。
#
# 反面情形实测踩过两次：
#   - mods.toml 写错 → mod 被**静默跳过**，Done 照样出现，只有 core 的日志
#   - mod 进 ERROR 状态 → 模组清单里**照样出现**它（清单只是发现阶段结果）
if grep -qaE 'voidkit_inspector\(1\)' "$LOG" 2>/dev/null; then
  echo "[e2e] ✅ core 的模块统计里 voidkit_inspector 计数为 1（模块已启用）"
else
  echo "[e2e] ❌ core 的模块统计里没有 voidkit_inspector（mod 被跳过或加载失败？）" >&2
  CRASH=1
fi

# 审计库实证：文件存在 + WAL 有实际写入（不是空壳）
DB="$SERVER_ABS/voidkit/inspector/inspector.db"
if [ ! -f "$DB" ]; then
  echo "[e2e] ❌ 未生成 inspector.db（InspectorFeature 没跑到建库？）" >&2
  CRASH=1
else
  WAL="$DB-wal"
  WALSZ=$(wc -c < "$WAL" 2>/dev/null || echo 0)
  if [ "$WALSZ" -gt 0 ] 2>/dev/null; then
    echo "[e2e] ✅ 审计库已建且有写入（db + ${WALSZ}B WAL，jarJar 的 SQLite 原生库可用）"
  else
    echo "[e2e] ⚠️ inspector.db 存在但 WAL 为空（建库了，但没有记录写入）"
  fi
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
