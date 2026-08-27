#!/bin/bash
###############################################################################
#  WallpaperExtend — 一键打包脚本  (build_apk.sh)
#  用法:
#     1) 先配置好 JDK 17 + Android SDK (见下方 ENV 段)
#     2) chmod +x build_apk.sh
#     3) ./build_apk.sh            # 打 Debug APK (无需签名, 可直接安装)
#        ./build_apk.sh release    # 打 Release APK (需先配好签名, 见下方)
#
#  产物位置:
#     app/build/outputs/apk/debug/app-debug.apk
#     app/build/outputs/apk/release/app-release.apk
###############################################################################
set -e

# ============================== ENV (按需修改) ==============================
# JDK 17 路径 (AGP 8.2 / Gradle 8.2 强制要求 JDK 17)
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
# Android SDK 路径
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
# ==========================================================================

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$PROJECT_DIR"

MODE="${1:-debug}"

echo "====== WallpaperExtend APK 打包 ======"
echo "[*] 模式: $MODE"
echo "[*] JAVA_HOME = $JAVA_HOME"
echo "[*] ANDROID_HOME = $ANDROID_HOME"

# ---- 环境检查 -------------------------------------------------------------
command -v java >/dev/null 2>&1 || { echo "[!] 未找到 java, 请设置 JAVA_HOME 指向 JDK 17"; exit 1; }
java -version 2>&1 | head -1
command -v sdkmanager >/dev/null 2>&1 || command -v adb >/dev/null 2>&1 || \
    echo "[!] 未检测到 Android SDK, 请设置 ANDROID_HOME"
echo "----------------------------------------"

# ---- 首次构建前确认 Gradle Wrapper 可执行 ---------------------------------
chmod +x gradlew 2>/dev/null || true

# ---- 确认 wrapper 存在 (没有则提示) ---------------------------------------
if [ ! -f "gradlew" ]; then
    echo "[!] 未找到 gradlew, 请确保在项目根目录执行"; exit 1
fi

# ---- 构建 -----------------------------------------------------------------
if [ "$MODE" = "release" ]; then
    echo "[*] 构建 Release APK ..."
    ./gradlew assembleRelease "$@"
    OUT="app/build/outputs/apk/release"
else
    echo "[*] 构建 Debug APK ..."
    ./gradlew assembleDebug "$@"
    OUT="app/build/outputs/apk/debug"
fi

# ---- 拷贝到项目根目录, 方便取用 -------------------------------------------
if [ -d "$OUT" ]; then
    cp "$OUT"/*.apk . 2>/dev/null || true
    echo ""
    echo "====== 构建完成 ======"
    ls -lh "$OUT"/*.apk 2>/dev/null
    echo ""
    echo "APK 已复制到项目根目录, 可直接安装:"
    ls -lh ./*.apk 2>/dev/null
fi
