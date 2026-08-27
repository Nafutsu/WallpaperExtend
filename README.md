# WallpaperExtend — iOS 17 风格壁纸延展 App

将任意图片一键延展为适配手机屏幕的壁纸，模拟 iOS 17 的 **Extend Wallpaper** 效果，并可**保存到相册 / 下载分享**。

## ✨ 功能

- 🖼️ **选择图片**：从相册选取，或从其他 App「分享到」本 App
- 🎨 **智能延展**：自动判断上下 / 左右延展方向
- 🌫️ **边缘模糊**：对超出屏幕的边缘做高斯模糊填充
- 🪶 **羽化过渡**：清晰主体与模糊延展区平滑融合
- 🎚️ **实时调节**：模糊半径 / 延展比例 / 羽化宽度
- 💾 **保存到相册**：一键下载到 `Pictures/WallpaperExtend/`
- 📤 **分享**：保存后可直接分享

## 🏗️ 技术要点

| 模块 | 实现 |
|------|------|
| 延展算法 | `WallpaperProcessor.kt` — Cover 缩放 + 边缘采样 + Stack Blur |
| 图片加载 | `ImageLoader.kt` — 采样缩放 + EXIF 旋转修正 |
| 保存到相册 | `ImageSaver.kt` — MediaStore（Android 10+）/ 文件（Android 9-） |
| 主界面 | `MainActivity.kt` — 选图 → 调节 → 预览 → 下载/分享 |

## 🚀 使用方式

1. Android Studio 打开本项目
2. Sync Gradle → Run
3. 选图 → 调节参数 → 预览 → **「下载 / 保存到相册」**

## 📱 适配

- minSdk 21 (Android 5.0)
- targetSdk 34
- 存储：Android 13+ 无需权限；Android 9- 需授权存储

## 📂 目录结构

```
app/src/main/java/com/wallpaperextend/
├── ui/MainActivity.kt
├── processor/WallpaperProcessor.kt
└── util/
    ├── ImageLoader.kt
    └── ImageSaver.kt
```

## 🔧 在手机上编译的替代方案

如果没有电脑，可在手机上：
- 使用 **Termux**（`pkg install openjdk-17 gradle` + Android SDK cmdline-tools）
- 或使用 **GitHub Codespaces / GitPod** 在线构建，下载 APK

## 📄 License

MIT
