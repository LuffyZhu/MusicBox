# 百度网盘 OAuth 服务

`index.php` 是 MusicBox 设备码授权和 Token 续期的服务端接口。它仅接受 HTTPS POST JSON，请求 `action` 为 `start`、`poll` 或 `refresh`。客户端只需公开的服务地址；AppKey 和 SecretKey 均由服务端配置。服务端不长期保存用户 Token，设备码状态最多保存 10 分钟。接口按来源 IP 对各类请求做每分钟限流。

部署前提：公网域名或可签发证书的公网 IP，Apache 已启用有效 HTTPS 证书，PHP 7+、OpenSSL 和 `allow_url_fopen` 可用。若装有 PHP cURL 扩展会优先使用它。IP 地址证书有效期较短，必须先配置自动续期。先在百度开放平台确认应用授权能力及使用范围。

1. 将 `index.php` 放到 HTTPS 站点的 `/musicbox-auth/index.php`。不要把示例配置文件放进网站根目录。
2. 在网站根目录外新建 `/etc/musicbox-auth.php`，按 `config.example.php` 填入真实 AppKey 和 SecretKey，文件权限仅允许服务管理员和 Web 服务进程读取。
3. 新建 `/var/lib/musicbox-auth`，仅允许 Web 服务进程读写；禁止通过网站访问该目录。
4. 先验证 HTTP 请求被拒绝，再验证 HTTPS 的 `POST {"action":"start"}` 能返回 `handle`、`user_code` 和 `verification_url`。扫码后通过 `poll` 换 Token；到期时通过 `refresh` 续期。
5. 取得证书后，运行 `activate-https.sh` 配置独立 HTTPS 站点。此脚本要求生产证书名为 `musicbox-ip`，并要求私有配置文件已经填好。
6. Android 客户端接入该 HTTPS 地址并发布新版 APK 后，用户只需自己的百度账号授权，不必填写开发者密钥。

不要把真实 SecretKey、access token 或 refresh token 提交到 GitHub、安装包或普通服务器日志。上线前还应配置限流和访问日志脱敏。
