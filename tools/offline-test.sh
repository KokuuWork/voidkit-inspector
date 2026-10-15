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

# ---- JDK 与 MC 制品：按分支一起定（1.20.1 线 = JDK 17 + Forge，
#      1.21.x 线 = JDK 21 + NeoForge）。顺序不能颠倒：TARGET 在下面才算出来。
TARGET=21
MC_JAR=""; FML=""; FML_LANG=""; FML_LOW=""; FML_SJH=""; BUS=""
if grep -q "mcVersion = '1.20.1'" "$PROJECT/build.gradle" 2>/dev/null \
   && ls "$PROJECT"/build/moddev/artifacts/forge-1.20.1-*-merged.jar >/dev/null 2>&1; then
  TARGET=17
fi
# ---- 编译与运行**一律用 JDK 21**（即使当前分支是 1.20.1 线）。
#
#🚨 为什么不能用分支自己的 JDK（1.20.1 线是 17）：
  #    ① 编译：libs/ 里往往只有某一条版本线的 core jar（本机是 1.21.1 线，
  #       字节码版本 65）。用 17 编译会报
  #       `cannot inline bytecode built with JVM target 21 into ... target 17`。
  #    ② 运行：编译产物是 65，用 17 跑会报
  #       `UnsupportedClassVersionError: class file version 65.0 ... up to 61.0`。
  #    这两条都是**编译/运行期**限制，与被测逻辑无关。本脚本只回答
  #    「代码能不能编译 + 纯计算测试过不过」，发布 jar 的jvm-target 由
  #    Gradle toolchain 负责，与本脚本无关。
JAVA="C:/Users/26703/.jdks/jdk-21.0.12.1+1/bin/java.exe"
JAVAC="C:/Users/26703/.jdks/jdk-21.0.12.1+1/bin/javac.exe"
[ -f "$JAVA" ] || { echo "[test] 找不到 JDK 21：$JAVA" >&2; exit 2; }

GC="$HOME/.gradle/caches"
work() { cygpath -w "$1"; }

# ---- 编译器 classpath ----
GCP=$(find "$GC" \( -name "kotlin-compiler-embeddable-2.1.0.jar" -o -name "kotlin-stdlib-2.1.0.jar" \
  -o -name "kotlin-reflect-2.1.0.jar" -o -name "kotlin-script-runtime-2.1.0.jar" \
  -o -name "kotlin-daemon-embeddable-2.1.0.jar" -o -name "trove4j-*.jar" \
  -o -name "kotlinx-coroutines-core-jvm-1.8.0.jar" -o -name "annotations-13.0.jar" \) 2>/dev/null \
  | while read -r f; do work "$f"; done | paste -sd ';' -)

# ---- 依赖 classpath：**一个版本都不许混** ----
# 1.20.1 线用 forge-*-merged.jar + FML 1.x，1.21.x 线用 neoforge-*-merged.jar + FML 4.x
if [ "$TARGET" = "17" ]; then
  MC_JAR=$(ls "$PROJECT"/build/moddev/artifacts/forge-1.20.1-*-merged.jar | head -1)
  FML=$(ls "$GC"/modules-2/files-2.1/net.minecraftforge/fmlcore/1.20.1-*/fmlcore-1.20.1-*.jar 2>/dev/null | grep -v sources | tail -1)
  # 🚨 `net.minecraftforge.fml.common.Mod` 注解在 **javafmllanguage** 里，
  #    不在 fmlcore / fmlloader —— 漏了它会报 `unresolved reference 'Mod'`。
  FML_LANG=$(ls "$GC"/modules-2/files-2.1/net.minecraftforge/javafmllanguage/1.20.1-*/*/javafmllanguage-1.20.1-*.jar 2>/dev/null | grep -v sources | tail -1)
  FML_LOW=$(ls "$GC"/modules-2/files-2.1/net.minecraftforge/lowcodelanguage/*/*/lowcodelanguage-*.jar 2>/dev/null | grep -v sources | tail -1)
  FML_SJH=$(ls "$GC"/modules-2/files-2.1/net.minecraftforge/securejarhandler/*/*/securejarhandler-*.jar 2>/dev/null | grep -v sources | tail -1)
  BUS=$(ls "$GC"/modules-2/files-2.1/net.minecraftforge/eventbus/*/*/eventbus-*.jar 2>/dev/null | grep -v sources | tail -1)
else
  MC_JAR=$(ls "$PROJECT"/build/moddev/artifacts/neoforge-*-merged.jar 2>/dev/null | head -1)
  FML=$(ls "$GC"/modules-2/files-2.1/net.neoforged.fancymodloader/loader/4.0.39/*/loader-4.0.39.jar 2>/dev/null)
  BUS=$(ls "$GC"/modules-2/files-2.1/net.neoforged/bus/*/*/bus-*.jar 2>/dev/null | grep -v sources | head -1)
fi
[ -z "$MC_JAR" ] && { echo "[test] 找不到 MC 制品，先跑一次 ./build.sh build" >&2; exit 2; }
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
    # 加载器相关的 5 个包：走变量，避免 NeoForge FML 4.x 与 Forge FML 1.x 混进同一条命令
    echo "$BUS"; echo "$FML"; echo "$FML_LANG"; echo "$FML_LOW"; echo "$FML_SJH"
    ls "$GC"/modules-2/files-2.1/org.xerial/sqlite-jdbc/3.5*/*/sqlite-jdbc-3.5*.jar 2>/dev/null | grep -v sources | head -1
  } 2>/dev/null | grep -v '^$' | sort -u | while read -r f; do work "$f"; done | paste -sd ';' -
)
DEPS="$DEPS;$(ls "$GC"/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.1.0/*/kotlin-stdlib-2.1.0.jar 2>/dev/null | head -1 | while read -r f; do work "$f"; done)"

if [ "${1:-}" = "--print-cp" ]; then echo "$DEPS"; exit 0; fi

#🚨 JUnit 必须**每个 artifact 只取一个版本**。缓存里常同时存在 5.10.1 与 5.10.2，
  #   混着上classpath 会让 Launcher 静默扫不到任何测试（表现为
  #   「containers found = 0 / tests found = 0」，没有报错，最难查）。
  #   sort -u 只按文件名去重，同一 artifact 的不同版本仍是多行 —— 所以要按
  #   「artifact 名」分组取版本号最大的那个。
JUNIT=$(
  find "$GC"/modules-2 \( -name "junit-jupiter-api-5*.jar" -o -name "junit-jupiter-engine-5*.jar" \
    -o -name "junit-platform-commons-1*.jar" -o -name "junit-platform-engine-1*.jar" \
    -o -name "junit-platform-launcher-1*.jar" -o -name "opentest4j-1*.jar" \
    -o -name "apiguardian-api-1*.jar" \) 2>/dev/null | grep -v sources \
  | awk -F/ '{f=$NF; sub(/-[0-9][^-]*(-sources)?\.jar$/,"",f); print f"\t"$0}' \
  | sort -t"$(printf '\t')" -k1,1 -k2,2V \
  | awk -F'\t' '{a[$1]=$2} END {for (k in a) print a[k]}' \
  | while read -r f; do work "$f"; done | paste -sd ';' -
)

OUT="$PROJECT/build/offline-test"
# 🚨 **所有传给 java/kotlinc/javac 的路径都必须是 Windows 形式**。
  #    `-d "$OUT/test"` 这类输出目录参数在 Git Bash 下能工作（编译器自己认 POSIX），
  #    但 `-Xfriend-paths` / `-cp` 里混进 POSIX 路径会让 kotlinc 静默生成空的
  #    classpath 条目 —— 表现是「编译通过、跑起来 0 个测试」，最难查。
  #    这里统一转换一次，后面全部用 $W_OUT。
rm -rf "$OUT"; mkdir -p "$OUT/main" "$OUT/test"
W_MAIN="$(work "$OUT/main")"; W_TEST="$(work "$OUT/test")"; W_OUT="$(work "$OUT")"
find "$MAIN_SRC" -name "*.kt" | while read -r f; do work "$f"; done > "$OUT/main.txt"
find "$TEST_SRC" -name "*.kt" | while read -r f; do work "$f"; done > "$OUT/test.txt"

echo "[test] 编译 main…"
# 🚨 编译一律用 `-jvm-target 21`，即使当前分支是 1.20.1 线（正式产物该用 17）。
  #    原因：libs/ 里往往只有某一条版本线的 core jar（本机当前是 1.21.1 线，字节码是
  #    JVM 21），用 17 编译会因为「cannot inline bytecode built with JVM target 21」
  #    直接失败 —— 那是**编译期**限制，与被测逻辑无关。
  #    本脚本只做「代码能不能编译 + 纯计算测试过不过」的验证，
  #    发布 jar 的 jvm-target 由 Gradle 的 toolchain 负责，与本脚本无关。
"$JAVA" -cp "$GCP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib \
  -jvm-target 21 \
  -Xjvm-default=all -classpath "$DEPS" -d "$W_MAIN" "@$OUT/main.txt" 2>&1 \
  | grep -vE "^warning:|is deprecated|^info:" | head -20
[ -d "$W_MAIN/org" ] || { echo "[test] main 编译失败" >&2; exit 1; }

echo "[test] 编译 test…"
"$JAVA" -cp "$GCP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib \
  -jvm-target 21 -Xjvm-default=all -Xfriend-paths="$W_MAIN" \
  -classpath "$DEPS;$JUNIT;$W_MAIN" -d "$W_TEST" "@$OUT/test.txt" 2>&1 \
  | grep -vE "^warning:|is deprecated|^info:" | head -20

cat > "$OUT/Runner.java" <<'JAVA'
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import java.io.PrintWriter;
import java.nio.file.Paths;
import java.util.Set;
public class Runner {
    public static void main(String[] a) {
        var l = new SummaryGeneratingListener();
        // ⚠️ 用 selectClasspathRoots 显式指目录，**不要用 selectPackage**。
        //    selectPackage 依赖「包目录恰好在 classpath 根下」的推导，
        //    在 Windows + Git Bash 混合路径下会静默扫不到任何类：
        //    表现为 containers found=1（engine 自己）但 tests found=0，
        //    **没有任何报错**。显式给目录就没有这个歧义。
        var req = LauncherDiscoveryRequestBuilder.request()
                .selectors(DiscoverySelectors.selectClasspathRoots(Set.of(Paths.get(a[0]))))
                .build();
        LauncherFactory.create().execute(req, l);
        var s = l.getSummary();
        var w = new PrintWriter(System.out); s.printTo(w); s.printFailuresTo(w);
        System.out.println("TESTS found=" + s.getTestsFoundCount()
            + " ok=" + s.getTestsSucceededCount() + " fail=" + s.getTestsFailedCount());
        System.exit(s.getTestsFailedCount() > 0 ? 1 : 0);
    }
}
JAVA
# ⚠️ `-encoding UTF-8` 不能省：JDK 17 的 javac 默认按**系统编码（GBK）**读源文件，
#    这里生成的是含中文注释的 .java，会直接报「不可映射为 GBK 的字符 0xA8」编译失败。
#    JDK 21 默认 UTF-8，所以这个坑只在 1.20.1 线（JDK 17）出现。
"$JAVAC" -encoding UTF-8 -cp "$JUNIT" -d "$W_OUT" "$OUT/Runner.java" 2>&1 | head -5

echo "[test] 运行…"
# `-Dfile.encoding=UTF-8` 同理：测试方法名是中文，不指定时输出全是乱码
"$JAVA" -Dfile.encoding=UTF-8 -cp "$W_OUT;$W_TEST;$W_MAIN;$DEPS;$JUNIT" Runner "$W_TEST" 2>&1 \
  | grep -vE "^SLF4J"