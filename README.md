# Audio Relay Android

手机系统音频实时转发到浏览器播放。

## 功能

- 🎙️ **系统内录** — 捕获手机正在播放的所有音频（音乐、游戏、视频等）
- 📡 **WebSocket 实时传输** — 低延迟音频流
- 🔊 **Opus 编码** — 高效压缩，节省带宽
- 🔒 **房间密码** — 可选密码保护

## 系统要求

- Android 10+ (API 29) — 需要 AudioPlaybackCapture 支持
- 网络连接

## 使用方法

1. 安装 APK，打开应用
2. 输入房间 ID（默认 `default`）
3. 点击「开始转发」，授权屏幕录制权限
4. 在浏览器打开 https://audio.zsxh.me ，输入相同房间 ID
5. 开始收听手机音频

## 构建

```bash
# 生成 Gradle Wrapper（如果没有）
gradle wrapper --gradle-version 8.10

# Debug 构建
./gradlew assembleDebug

# APK 位置
# app/build/outputs/apk/debug/app-debug.apk
```

## 技术栈

- **Kotlin** + ViewBinding
- **OkHttp** WebSocket
- **Concentus** (纯 Java Opus 编码器)
- **AudioPlaybackCapture** (Android 系统内录 API)
- **MediaProjection** + Foreground Service

## 协议

```
连接 → wss://audio.zsxh.me/ws
加入 → {"type":"join_producer","room":"id","password":""}
音频 → [0x01][seq_hi][seq_lo][opus_data...]
心跳 → {"type":"ping"} ↔ {"type":"pong"}
```
