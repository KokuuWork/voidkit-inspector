#!/usr/bin/env bash
# 在 Gradle 走不通时（createMinecraftArtifacts 卡在 NeoForm Runtime 下载）
# 用 kotlinc + JUnit Launcher 直接编译并跑测试。
#
# 为什么需要它：本机到 maven.neoforged.net 超时，而
# net.neoforged:minecraft-dependencies 这个 Gradle module-metadata 制品
# 在 neoforged.forgecdn.net 上是 404（只随 NeoForm 内部流程产出）。
# 文件其实已在本地缓存，只是 descriptor 索引被标成 missing，
# `--refresh-dependencies` / `--offline` 都救不回来。
#
# 🚨 **classpath 必须精确**：只能给目标版本的 MC jar + 对应 FML 版本，
#    混进任何其它版本（1.20.x / Forge 52 / fmlcore 1.x）都会让
#    编译器在 IR lowering阶段抛 BackendException，而不是报清晰的冲突。
#    这是本脚本存在的最大理由 —— 手写 classpath 时一定会踩。
#
# 用法：
#   tools/offline-test.sh              # 编译 + 跑当前工作树的测试
#   tools/offline-test.sh --print-cp   # 只打印 classpath，便于排查
set -u
cd "$(dirname "$0")/.."
PROJECT="$(pwd)"

MAIN_SRC="$PROJECT/src/main/kotlin"
TEST_SRC="$PROJECT/src/test/kotlin"

# ---- JDK：按项目需要选（1.20.1 线用 17，1.21.x 用 21）----
if [ -f "$PROJECT/gradle.properties" ] && grep -q "jdk-17" "$PROJECT/build.gradle" 2>/dev/null; then
  JDK="C:/Users/26703/.jdks/jdk-17.0.20.1+1"
else
  JDK="C:/Users/26703/.jdks/jdk-21.0.12.1+1"
fi
JAVA="$JDK/bin/java.exe"; JAVAC="$JDK/bin/javac.exe"
[ -f "$JAVA" ] || { echo "[test] 找不到 JDK：$JAVA" >&2; exit 2; }

GC="$HOME/.gradle/caches"
work() { cygpath -w "$1"; }

# ---- 编译器 classpath ----
GCP=$(find "$GC" \( -name "kotlin-compiler-embeddable-2.1.0.jar" -o -name "kotlin-stdlib-2.1.0.jar" \
  -o -name "kotlin-reflect-2.1.0.jar" -o -name "kotlin-script-runtime-2.1.0.jar" \
  -o -name "kotlin-daemon-embeddable-2.1.0.jar" -o -name "trove4j-*.jar" \
  -o -name "kotlinx-coroutines-core-jvm-1.8.0.jar" -o -name "annotations-13.0.jar" \) 2>/dev/null \
  | while read -r f; do work "$f"; done | paste -sd ';' -)

# ---- 依赖 classpath：**一个版本都不许混** ----
MC_JAR=$(ls "$PROJECT"/build/moddev/artifacts/*-merged.jar 2>/dev/null | grep -v 1\.20\.1 | head -1)
[ -z "$MC_JAR" ] && MC_JAR=$(ls "$PROJECT"/build/moddev/artifacts/*-merged.jar 2>/dev/null | head -1)
DEPS=$(
  { ls "$PROJECT"/libs/voidkit_core-*.jar 2>/dev/null
    echo "$MC_JAR"
    ls "$GC"/modules-2/files-2.1/com.mojang/brigadier/1.3.10/*/brigadier-1.3.10.jar 2>/dev/null
    ls "$GC"/modules-2/files-2.1/com.google.code.gson/gson/2.9.0/*/gson-2.9.0.jar 2>/dev/null
    ls "$GC"/modules-2/files-2.1/com.mojang/datafixerupper/6.0.8/*/datafixerupper-6.0.8.jar 2>/dev/null
    ls "$GC"/modules-2/files-2.1/com.mojang/authlib/*/*/authlib-*.jar 2>/dev/null | grep -v sources | head -1
    ls "$GC"/modules-2/files-2.1/org.slf4j/slf4j-api/2.0.*/*/slf4j-api-2.0.*.jar 2>/dev/null | grep -v sources | head -1
    ls "$GC"/modules-2/files-2.1/com.google.guava/guava/31.1-jre/*/guava-31.1-jre.jar 2>/dev/null
    ls "$GC"/modules-2/files-2.1/it.unimi.dsi/fastutil/*/*/fastutil-*.jar 2>/dev/null | grep -v sources | head -1
    ls "$GC"/modules-2/files-2.1/org.joml/joml/*/*/joml-*.jar 2>/dev/null | grep -v sources | head -1
    ls "$GC"/modules-2/files-2.1/org.apache.commons/commons-lang3/*/*/commons-lang3-*.jar 2>/dev/null | grep -v sources | head -1
    ls "$GC"/modules-2/files-2.1/net.neoforged/bus/*/*/bus-*.jar 2>/dev/null | grep -v sources | head -1
    ls "$GC"/modules-2/files-2.1/net.neoforged.fancymodloader/loader/4.0.39/*/loader-4.0.39.jar 2>/dev/null
    ls "$GC"/modules-2/files-2.1/org.xerial/sqlite-jdbc/3.5*/*/sqlite-jdbc-3.5*.jar 2>/dev/null | grep -v sources | head -1
  } 2>/dev/null | sort -u | while read -r f; do work "$f"; done | paste -sd ';' -
)
DEPS="$DEPS;$(ls "$GC"/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.1.0/*/kotlin-stdlib-2.1.0.jar 2>/dev/null | head -1 | while read -r f; do work "$f"; done)"

if [ "${1:-}" = "--print-cp" ]; then echo "$DEPS"; exit 0; fi

JUNIT=$(find "$GC"/modules-2 \( -name "junit-jupiter-api-5*.jar" -o -name "junit-jupiter-engine-5*.jar" \
  -o -name "junit-platform-commons-1*.jar" -o -name "junit-platform-engine-1*.jar" \
  -o -name "junit-platform-launcher-1*.jar" -o -name "opentest4j-1*.jar" \
  -o -name "apiguardian-api-1*.jar" \) 2>/dev/null | grep -v sources | sort -u \
  | while read -r f; do work "$f"; done | paste -sd ';' -)

OUT="$PROJECT/build/offline-test"
rm -rf "$OUT"; mkdir -p "$OUT/main" "$OUT/test"
find "$MAIN_SRC" -name "*.kt" | while read -r f; do work "$f"; done > "$OUT/main.txt"
find "$TEST_SRC" -name "*.kt" | while read -r f; do work "$f"; done > "$OUT/test.txt"

echo "[test] 编译 main…"
"$JAVA" -cp "$GCP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib \
  $([ -d "$OUT" ] && grep -q 1.20.1 "$MC_JAR" && echo "-jvm-target 17" || echo "-jvm-target 21") \
  -Xjvm-default=all -classpath "$DEPS" -d "$OUT/main" "@$OUT/main.txt" 2>&1 \
  | grep -vE "^warning:|is deprecated|^info:" | head -20
[ -d "$OUT/main/org" ] || { echo "[test] main 编译失败" >&2; exit 1; }

echo "[test] 编译 test…"
"$JAVA" -cp "$GCP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib \
  -Xjvm-default=all -Xfriend-paths="$(work "$OUT/main")" \
  -classpath "$DEPS;$JUNIT;$(work "$OUT/main")" -d "$OUT/test" "@$OUT/test.txt" 2>&1 \
  | grep -vE "^warning:|is deprecated|^info:" | head -20

cat > "$OUT/Runner.java" <<'JAVA'
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import java.io.PrintWriter;
public class Runner {
    public static void main(String[] a) {
        var l = new SummaryGeneratingListener();
        LauncherFactory.create().execute(
            LauncherDiscoveryRequestBuilder.request()
                .selectors(DiscoverySelectors.selectPackage("org.voidkit.inspector")).build(), l);
        var s = l.getSummary();
        var w = new PrintWriter(System.out); s.printTo(w); s.printFailuresTo(w);
        System.out.println("TESTS found=" + s.getTestsFoundCount()
            + " ok=" + s.getTestsSucceededCount() + " fail=" + s.getTestsFailedCount());
        System.exit(s.getTestsFailedCount() > 0 ? 1 : 0);
    }
}
JAVA
"$JAVAC" -cp "$JUNIT" -d "$OUT" "$OUT/Runner.java" 2>&1 | head -5

echo "[test] 运行…"
"$JAVA" -cp "$(work "$OUT");$(work "$OUT/test");$(work "$OUT/main");$DEPS;$JUNIT" Runner 2>&1 \
  | grep -vE "^SLF4J"