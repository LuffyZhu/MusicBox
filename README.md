# MusicBox

MusicBox 是面向 Android 手机和平板的音乐播放器，基于 [TVBoxOS-Mobile](https://github.com/XiaoRanLiu3119/TVBoxOS-Mobile) 改造。应用默认不附带 TVBox 订阅、站点清单或第三方爬虫 JAR。用户可在「设置 → 配置源」填写自己的 TVBox 配置地址；搜索结果按纯音乐与 MV 展示，播放列表、歌词、个人网盘和 YouTube 音频/MV 功能由应用提供。

## 下载与更新

APK 在本仓库的 Releases 发布。应用内「设置 → 在线升级」读取仓库根目录的 `update.json`，核对版本、文件大小及 SHA-256 后交给 Android 系统确认安装。GitHub 下载在某些网络下可能较慢；更新地址可在应用中自行更换。

## 构建

使用 JDK 17 和 Android SDK Platform 33，在仓库根目录运行 `./gradlew :app:assembleDebug`（Windows 为 `gradlew.bat :app:assembleDebug`）。本地 SDK 路径写入未纳入仓库的 `local.properties`。生成的 APK 位于 `app/build/outputs/apk/debug/`。

## 项目来源与许可

项目保留 TVBoxOS-Mobile 的 AGPL-3.0 许可证，详情见 [LICENSE](LICENSE) 和 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。外部配置及第三方服务由用户自行选择；本仓库不发布订阅地址或网盘登录凭据。
