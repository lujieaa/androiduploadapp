#!/bin/bash
# 启动上传服务，安装并打开平板上的拍照应用。
# 用法: ./启动.sh [照片保存目录]
# 不传目录时，照片保存到本项目下的 uploads。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
SAVE_DIR="$(realpath -m "${1:-$ROOT/uploads}")"
PORT=8080
PKG="com.local.photoupload"
APK="$ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
if [[ ! -x "${JAVA_HOME:-}/bin/jlink" && -x "$HOME/.local/jdks/jdk-21/bin/jlink" ]]; then
  export JAVA_HOME="$HOME/.local/jdks/jdk-21"
  export PATH="$JAVA_HOME/bin:$PATH"
fi

mkdir -p "$SAVE_DIR" "$ROOT/server"
printf 'sdk.dir=%s\n' "$SDK" > "$ROOT/android/local.properties"

LAN_IP="$(ip -4 route get 1.1.1.1 2>/dev/null | awk '{for (i=1; i<=NF; i++) if ($i=="src") {print $(i+1); exit}}' || true)"
if [[ -z "$LAN_IP" ]]; then
  LAN_IP="$(hostname -I 2>/dev/null | awk '{for (i=1; i<=NF; i++) if ($i !~ /^127\./) {print $i; exit}}' || true)"
fi
if [[ -z "$LAN_IP" ]]; then
  echo "无法自动获取服务器局域网 IP，请检查网络连接"
  exit 1
fi
SERVER_URL="http://${LAN_IP}:${PORT}/upload"

if ss -ltn | awk '{print $4}' | grep -Eq "(^|:)${PORT}$"; then
  echo "端口 ${PORT} 已在监听，沿用现有上传服务"
else
  nohup python3 "$ROOT/server/upload_server.py" --host 0.0.0.0 --port "$PORT" --dir "$SAVE_DIR" \
    >> "$ROOT/server/server.log" 2>&1 &
  echo $! > "$ROOT/server/server.pid"
  sleep 0.4
fi

echo "局域网上传地址: ${SERVER_URL}"
DEVICE=""
if command -v adb >/dev/null 2>&1; then
  DEVICE="$(adb devices | awk 'NR>1 && $2=="device" {print $1; exit}')"
fi
if [[ -z "${DEVICE}" ]]; then
  echo "没有连接 Android 设备。上传服务已启动，照片目录: ${SAVE_DIR}"
  echo "在平板应用的上传地址中填写 ${SERVER_URL}；同一局域网即可上传，无需 USB。"
  exit 0
fi

if adb -s "$DEVICE" shell pm path "$PKG" 2>/dev/null | grep -q '^package:'; then
  echo "平板已安装应用，跳过编译和安装"
else
  echo "正在编译应用"
  (cd "$ROOT/android" && ./gradlew assembleDebug --no-daemon)
  adb -s "$DEVICE" install -r "$APK"
fi
adb -s "$DEVICE" shell pm grant "$PKG" android.permission.CAMERA || true
adb -s "$DEVICE" shell input keyevent KEYCODE_WAKEUP || true
adb -s "$DEVICE" shell am start -n "$PKG/.MainActivity" --es server_url "$SERVER_URL"
echo "已打开拍照上传并设置局域网地址。照片目录: ${SAVE_DIR}"
