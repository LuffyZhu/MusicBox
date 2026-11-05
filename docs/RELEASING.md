# 发布更新

1. 增加 `app/build.gradle` 中的 `versionCode` 和 `versionName`，构建 APK。
2. 使用**与上一版相同的签名证书**。现有 `0.3.36-alpha` 测试包采用本机 Android debug 证书；用其他机器新生成的 debug 证书签名会导致覆盖安装失败。正式发布应配置并妥善备份独立的发布证书，切换证书时用户需要重新安装。
3. 先把 APK 上传到 GitHub Releases，并确认下载链接可用。
4. 运行 `scripts/Publish-Update.ps1 -ApkPath <APK路径> -ApkUrl <Release的HTTPS下载链接> -Notes <更新说明>`，生成仓库根目录的 `update.json`。
5. 最后提交并推送 `update.json`。应用内「设置 → 在线升级」默认读取此文件，下载后会核对大小和 SHA-256，再交给 Android 系统确认安装。

仓库不存放签名证书、登录凭据、TVBox 订阅或站点 JAR。GitHub 下载在部分网络环境可能不稳定；应用允许用户改填其他公开的 HTTPS 更新清单地址。
