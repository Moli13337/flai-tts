# 蝴蝶体育朗读

[Token来源](https://github.com/Moli13337/flai-key)

FLAI（Flutter 语音陪聊 App）TTS 协议的第三方小说朗读客户端，Android 14+。

基于对官方 APK 的逆向分析实现：AES-256-CBC 会话加密、RSA token 头、tinyproxy CONNECT 隧道、PCM 16kHz 合成、libmp3lame 本地编码缓存。

## 功能

- token 号池：多 token 轮询，自动校验剔除失效 token
- TXT 小说自动分段朗读（单段 ≤250 中文字符，支持续读，进度持久化）
- MP3 缓存（libmp3lame 静态编译，缓存永不删除）
- 流水线预取（默认播放过半预取下一段，可调 0~1）
- 前台服务保活（mediaPlayback 类型 + 媒体通知 + 唤醒锁）
- 梦境声音预览：声音列表（头像、简介、code 一键复制、点选即用）
- 代理：内置 `127.0.0.1:8899`，可在设置页改远程 `host:port`
- 全部设置持久化

## 构建

无 Android Studio 依赖，命令行即可：

- JDK 17+（开发时使用 Temurin 21）
- Android SDK：platform 35、build-tools 34.0.0
- NDK 27.2.12479018、CMake 3.22.1

```bash
./gradlew :app:assembleDebug
# 产物: app/build/outputs/apk/debug/app-debug.apk
```

## 使用

1. 安装 APK（Android 14+）
2. 设置页粘贴 uToken（JWT，从官方 app 抓包或 PC 端工具获取），点「校验号池」
3. 代理：默认 `127.0.0.1:8899`（适合手机本地转发，如 Termux `socat TCP-LISTEN:8899,fork TCP:<远程代理>:8899`）；也可在设置页直接填远程 `host:port`
4. 朗读页选书（SAF 或路径），输入音色 code（或去「声音」页点选），播放
5. 需要本地文件访问时，首次选书会跳转系统授权页

## 目录

```
app/src/main/java/flai/app/
  Net.kt        协议层：AES/RSA/隧道/API/号池
  Tts.kt        分段/进度/MP3缓存/合成/Mp3Player
  Lame.kt       libmp3lame JNI
  ReaderService.kt  朗读引擎（前台服务，完整持有播放循环）
  VoicePage.kt  声音列表（分页/头像加载/复制/选声）
  MainActivity.kt   三页 UI（朗读/声音/设置）
app/src/main/cpp/   lame 3.100 源码 + JNI 封装
tools/relay.py      PC 端代理转发脚本（模拟器调试用）
```

## 免责声明

本项目仅供学习与研究，请于 24 小时内自行删除。使用本项目产生的任何账号风险、服务端后果由使用者自行承担。token 与代理凭据请勿公开传播。
