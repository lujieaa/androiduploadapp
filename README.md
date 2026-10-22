# 拍照上传

使用 Android 手机拍摄照片，并通过 HTTP 上传到 Ubuntu 电脑或 Android 平板。Android 应用包含拍照上传端和平板接收端；接收端将图片保存到系统相册，可供相册应用直接查看。

## 功能

- CameraX 实时取景、拍照并上传 JPEG，也可重试上传最近一张照片。
- 拍照使用圆形快门按钮，并可在预览中切换前后摄像头。
- 可在应用设置中填写上传地址和可选子目录。
- 同一个 APK 可作为平板接收端，支持局域网和 Tailscale 网络。
- 平板接收端使用前台服务运行，默认监听 TCP `8080` 端口。
- 平板收到的照片保存在系统相册的 `Pictures/拍照上传`；设置子目录后保存到对应下级目录。
- Ubuntu 可继续运行 Python 接收服务，将照片保存到指定磁盘目录。

## Android 平板接收照片

1. 在平板安装本项目构建的 APK，并打开“拍照上传”。
2. 点击“设置”→“本机接收端”。
3. 点击“启动接收服务”，按系统提示允许通知权限。服务启动后会显示本机可用的 IPv4 上传地址，并在通知栏显示运行状态。
4. 在拍照手机的“设置”中，把上传地址填写为平板显示的地址，例如：

   ```text
   http://192.168.1.25:8080/upload
   ```
5. 两台设备连接同一局域网后即可拍照上传。上传完成后，在平板相册的“拍照上传”目录查看照片。

平板接收服务只在用户点击启动后运行。停止服务可在接收端页面点击“停止接收服务”；Android 可能会在系统设置中提供额外的电池优化限制选项，长期接收时可按设备系统提示允许应用在后台运行。

### 通过 Tailscale 连接

1. 在手机和平板上安装并登录 Tailscale，确认两台设备属于同一个 tailnet，且 tailnet ACL 允许手机访问平板。
2. 在平板接收端页面启动服务。
3. 在手机上传地址中填写平板的 Tailscale IPv4 地址，例如：

   ```text
   http://100.x.y.z:8080/upload
   ```

Tailscale 地址不一定会出现在接收端页面的自动检测列表中；此时可在 Tailscale 应用中查看平板地址，再手动填写。远程连接时不需要端口转发，但要确认设备在线且 ACL 允许访问 `8080` 端口。

## Ubuntu 接收服务

Ubuntu 服务使用 Python 标准库，无需安装额外 Python 包。默认把图片保存到项目的 `uploads/` 目录：

```bash
python3 server/upload_server.py
```

指定保存目录、监听地址和端口：

```bash
python3 server/upload_server.py --dir /path/to/photos --host 0.0.0.0 --port 8080
```

随后在拍照端设置中填写 Ubuntu 主机的地址，例如 `http://192.168.1.10:8080/upload`。Ubuntu 主机和手机应能互相访问；如系统防火墙启用，请允许 TCP `8080` 入站连接。

### 一键启动脚本

也可以使用项目脚本启动服务：

```bash
./startuploadserver.sh
```

默认保存到项目的 `uploads/`。也可传入照片保存目录：

```bash
./startuploadserver.sh /path/to/photos
```

脚本会显示 Ubuntu 主机的局域网上传地址。如果检测到已通过 ADB 连接的 Android 设备，还会在应用未安装时构建并安装 APK，然后打开拍照页面并填入该地址；没有连接设备时，服务仍会启动，可在手机上手动填写地址。

### Windows 接收服务

Windows 启动脚本使用项目根目录下 `.winvenv` 虚拟环境中的 Python（`.winvenv\Scripts\python.exe`），不依赖系统 PATH。请先创建该环境。然后在 PowerShell 中从项目目录运行（如果系统执行策略阻止脚本，可用此命令绕过当前进程的策略，不会修改系统设置）：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\startuploadserver.ps1
```

也可以指定照片保存目录：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\startuploadserver.ps1 -SaveDir "D:\Photos"
```

脚本会在后台启动 Python 接收服务，日志写入 `server\server.log`；错误输出写入 `server\server-error.log`。如检测到已连接的 ADB 设备，脚本会尝试启动已安装的应用；如需自动构建 APK，还需要项目中有 `gradlew.bat` 或系统已安装 Gradle。

如果环境目录还叫 `.winvevn`（字母顺序与 `.winvenv` 不同），脚本会暂时兼容并发出提示；建议将环境目录改名为 `.winvenv`。

设置为当前 Windows 用户登录后自动启动（照片默认保存到项目的 `uploads` 目录）：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\startuploadserver.ps1 -InstallStartupTask
```

自定义目录也可用于自动启动：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\startuploadserver.ps1 -SaveDir "D:\Photos" -InstallStartupTask
```

自动启动任务在用户登录时运行，不需要管理员权限。移除任务：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\startuploadserver.ps1 -UninstallStartupTask
```

首次使用时，如果 Windows 防火墙拦截连接，请允许 Python 或 TCP `8080` 入站访问；只应在可信局域网中开放此端口。

## 构建 APK

需要 JDK 17、Android SDK（含 Android 14 / API 34 平台）和可用的 Gradle Wrapper。Linux/macOS 下在项目根目录运行：

```bash
cd android
./gradlew assembleDebug
```

Debug APK 输出位置：

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

也可以通过 ADB 安装到已连接的设备：

```bash
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

首次安装或 Android 系统策略要求时，请在设备上确认安装提示。应用包名为 `com.local.photoupload`，最低支持 Android 8.0（API 26）。

## 上传接口

Android 平板和 Ubuntu 服务均兼容以下接口：

| 用途     | 请求                            |
| -------- | ------------------------------- |
| 健康检查 | `GET /health`                 |
| 上传照片 | `POST /upload?dir=可选子目录` |

上传请求正文为 JPEG 原始字节，`Content-Type` 为 `image/jpeg`，需要提供 `Content-Length`。单张照片最大为 20 MiB。`dir` 是相对于接收端相册目录或 Ubuntu 指定保存目录的子目录；省略时保存到默认目录。

## 网络与隐私

接收端当前使用未加密的 HTTP，且没有账号或上传令牌。请只在可信局域网或受 ACL 保护的 Tailscale 网络中开放访问，不要将端口直接暴露到公网。上传图片会写入接收设备的公共相册或 Ubuntu 指定目录。
