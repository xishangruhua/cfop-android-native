# CFOP 速拧实验室 · 原生 Android Studio 工程 (Kotlin + CameraX + OpenCV + min2phase)

本工程严格基于 Android 官方 Jetpack 架构构建，彻底废弃浏览器实时抽帧识别，采用成熟原生视觉与求解链路：
- **CameraX (`1.4.0`)**：纯硬件 `PreviewView` + 低延迟 `ImageCapture` 单帧抓拍，拍完立即 `image.close()` + `bitmap.recycle()`。
- **OpenCV Android AAR (`org.opencv:opencv:4.12.0`)**：`CubeSquareDetector.kt` 实现 Canny 边缘 + 四边形方格轮廓寻方 + `warpPerspective` 透视拉正裁剪为 300×300 正方形 + 去高光截尾采色，所有 `Mat` 在 `finally` 中立即释放。
- **CIEDE2000 全局解色器**：`RubiksColorResolver.kt` 参考 `dwalton76/rubiks-color-resolver`，用 6 个真实中心块做环境光校准并进行 8×6 均衡分配。
- **min2phase 极速求解器**：`CubeSolverBridge.kt` 直连 `cs0x7f/min2phase` 纯 Java 两阶段算法，毫秒级输出 ~20 步最优解并回传 3D 魔方演示。

## 详细操作步骤

1. **安装开发工具**：下载并安装 [Android Studio](https://developer.android.com/studio)（内置 JDK 17 与 Android SDK）。
2. **解压并拉取开源求解器**：解压本压缩包后，在工程根目录运行：
   - Windows：双击运行 `scripts\fetch-deps.bat`
   - macOS / Linux：终端执行 `bash scripts/fetch-deps.sh`
   （会自动把 `cs0x7f/min2phase` 的 5 个 Java 文件下载到 `app/src/main/java/cs/min2phase/`）
3. **导入 Android Studio**：打开 Android Studio → **Open** → 选中解压后的 `cfop-android-native` 文件夹，等待 Gradle 自动同步下载 CameraX 与 OpenCV AAR 依赖（首次约 1–3 分钟）。
4. **真机调试或导出 APK**：
   - 手机开启「开发者选项 → USB 调试」连接电脑，点击顶部绿色 **Run ▶** 按钮直接安装到手机；
   - 或点击顶部菜单 **Build → Build Bundle(s) / APK(s) → Build APK(s)** 生成 `.apk` 安装包（输出在 `app/build/outputs/apk/debug/app-debug.apk`）。
