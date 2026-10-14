#!/usr/bin/env bash
# Voidkit Inspector —— NeoForge 1.21.1 端到端验证（发布态 jar + 真服务端）
#
# 与 tools/e2e-forge-server.sh 对应，覆盖 main 分支。
# ⚠️ **判据与 Forge 版不同**：NeoForge 服务端日志**会**打印模组清单
#    `VoidkitInspector <版本> (voidkit_inspector)`，可以用它做强判据；
#    Forge 52 不打这份清单（见 forge 脚本里的说明）。
#
# 用法：
#   tools/e2e-neoforge-server.sh                    # 默认 build/e2e-1211
#   SERVER_DIR=/path tools/e2e-neoforge-server.sh
set -u

cd "$(dirname "$0")/.."
PROJECT="$(pwd)"

SERVER_DIR="${SERVER_DIR:-$PROJECT/build/e2e-1211}"
LOG="$SERVER_DIR/server.log"

# ⚠️ 绝对路径判定要同时认 POSIX（/xxx）与 Windows 盘符（C:/xxx）——
#    Git Bash 下 SERVER_DIR 两种形态都可能出现，只判 `/` 会把盘符路径
#    拼成「$PROJECT/C:/...」然后报「目录不存在」。
case "$SERVER_DIR" in
  /*|[A-Za-z]:/*|[A-Za-z]:\\*) SERVER_ABS="$SERVER_DIR" ;;
  *)                        SERVER_ABS="$PROJECT/$SERVER_DIR" ;;
esac

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
  echo "      先装服务端：java -jar neoforge-installer.jar --installServer ." >&2
  exit 2
fi

# NeoForge 用 win_args.txt（Windows 上 classpath 分隔符是 ;，unix_args.txt 会炸）
ARGS="$SERVER_ABS/libraries/net/neoforged/neoforge/21.1.209/win_args.txt"
if [ ! -f "$ARGS" ]; then
  echo "[e2e] 找不到 NeoForge 启动参数：$ARGS（服务端没装好？）" >&2
  exit 2
fi

echo "[e2e] 服务端目录：$SERVER_ABS"
for want in voidkit_core voidkit_inspector; do
  n=$(ls "$SERVER_ABS"/mods/${want}-*.jar 2>/dev/null | wc -l)
  echo "[e2e] mods/ ${want}: $n 个"
  if [ "$n" != "1" ]; then
    echo "[e2e] ❌ ${want} jar 数不对（应为 1，检查是否混入旧版本）" >&2
    exit 2
  fi
done

if command -v unzip >/dev/null 2>&1; then
  PROBE=$(ls "$SERVER_ABS"/mods/voidkit_inspector-*.jar | head -1)
  for must in META-INF/neoforge.mods.toml \
              org/voidkit/inspector/InspectorFeature.class \
              org/voidkit/inspector/platform/neoforge/InspectorEvents.class \
              META-INF/jarjar/metadata.json; do
    if unzip -l "$PROBE" "$must" >/dev/null 2>&1; then
      echo "[e2e] ✅ inspector jar 含 $must"
    else
      echo "[e2e] ❌ inspector jar 缺少 $must（构建产物不完整，禁止起服）" >&2
      exit 2
    fi
  done
  # 反向校验：NeoForge 版 jar 里不能混进 Forge 的产物
  if unzip -l "$PROBE" "org/voidkit/inspector/platform/forge/InspectorEvents.class" >/dev/null 2>&1; then
    echo "[e2e] ❌ NeoForge 版 jar 里混进了 forge 平台层（分支串了）" >&2
    exit 2
  fi
  echo "[e2e] --- inspector 的 mods.toml 依赖声明 ---"
  unzip -p "$PROBE" META-INF/neoforge.mods.toml | grep -A3 'modId = "voidkit_core"' || true
fi

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
echo "=================== Voidkit 相关日志 ==================="
grep -aE 'Voidkit|voidkit' "$LOG" 2>/dev/null | grep -avE '> >' | head -25

echo ""
echo "=================== 致命错误（应为空）==================="
if grep -qaE 'NoSuchMethodError|NoClassDefFoundError|InvalidModFileException|ModLoadingException|Failed to create mod instance|ResolutionException|not a valid mod file|\[Server thread/FATAL\]|Encountered an unexpected exception' "$LOG" 2>/dev/null; then
  grep -aE 'NoSuchMethodError|NoClassDefFoundError|InvalidModFileException|ModLoadingException|Failed to create mod instance|ResolutionException|not a valid mod file|\[Server thread/FATAL\]|Encountered an unexpected exception' "$LOG" | head -20
  CRASH=1
else
  echo "（无）"
fi

# ---------- 强判据 ----------
# ① 模组清单里有 VoidkitInspector
# ⚠️ **这一条单独用会假阳性**：mod 进 ERROR 状态时清单里**照样出现**它
#    （清单 = 发现阶段的结果，不等于加载成功）。实测踩过：
#    `Failed to create mod instance` 与清单里的 `VoidkitInspector ... (voidkit_inspector)`
#    同时出现。所以它只能当"至少被发现了"的必要条件，
#    真判据是下面 ②③（core 认到模块 + 审计库建起来）。
HAVE_ENTRY=0
if grep -qaE 'VoidkitInspector [0-9][^ ]* \(voidkit_inspector\)' "$LOG" 2>/dev/null; then
  echo "[e2e] ✅ 模组清单里有 VoidkitInspector（仅代表被发现，不代表加载成功）"
  HAVE_ENTRY=1
else
  echo "[e2e] ❌ 模组清单里没有 VoidkitInspector（jar 都没被发现）" >&2
  CRASH=1
fi

# ⚠️ 反向判据：清单里出现但同时有加载失败 = ERROR 状态，直接判负
if grep -qa 'voidkit_inspector.*ERROR' "$LOG" 2>/dev/null; then
  echo "[e2e] ❌ 模组清单里 inspector 状态是 ERROR（被发现了但加载失败）" >&2
  CRASH=1
fi

# ② 功能判据
#
# ⚠️ **判据必须只用 ASCII 锚点**。服务端日志的中文编码不固定：同一个 MC 版本，
#    NeoForge 台服常按 GBK 输出、Forge 台服常按 UTF-8，脚本 grep 中文会随机匹配不上
#    （实测踩过：功能明明正常，判据却报 ❌）。所以只用模块计数 `voidkit_inspector(1)`
#    这类纯 ASCII 标记 + 文件系统实证，中文一律不作为判据。
if grep -qaE 'voidkit_inspector\(1\)' "$LOG" 2>/dev/null; then
  echo "[e2e] ✅ core 的模块统计里 voidkit_inspector 计数为 1（模块已启用）"
else
  echo "[e2e] ❌ core 的模块统计里没有 voidkit_inspector（模块没真起来）" >&2
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
    echo "[e2e] ✅ 审计库已建且有写入（db + ${WALSZ}B WAL，说明 SQLite 原生库可用）"
  else
    echo "[e2e] ⚠️ inspector.db 存在但 WAL 为空（建库了，但没有记录写入）"
  fi
  ls -la "$SERVER_ABS/voidkit/inspector" | sed 's/^/[e2e]   /'
fi

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
echo "[e2e] ✅ 通过：发布产物在真正的 NeoForge 1.21.1 服务端上加载正常，审计库已建"
