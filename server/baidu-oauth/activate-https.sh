#!/bin/sh
# Run as root after a production IP certificate named musicbox-ip has been issued.
set -eu

CERT_DIR=/etc/letsencrypt/live/musicbox-ip
SOURCE=/opt/musicbox-auth/index.php
WEB_ROOT=/var/www/musicbox-secure
SITE=/etc/apache2/sites-available/musicbox-ssl.conf

test "$(id -u)" -eq 0
test -f "$CERT_DIR/fullchain.pem"
test -f "$CERT_DIR/privkey.pem"
test -f "$SOURCE"
test -f /etc/musicbox-auth.php
php -l "$SOURCE"
php -l /etc/musicbox-auth.php
chown root:www-data /etc/musicbox-auth.php
chmod 0640 /etc/musicbox-auth.php

install -d -m 0755 "$WEB_ROOT/musicbox-auth"
install -m 0644 "$SOURCE" "$WEB_ROOT/musicbox-auth/index.php"
install -d -m 0700 -o www-data -g www-data /var/lib/musicbox-auth

cat > "$SITE" <<'APACHE'
<VirtualHost *:443>
    ServerName 106.14.212.171
    DocumentRoot /var/www/musicbox-secure
    SSLEngine on
    SSLCertificateFile /etc/letsencrypt/live/musicbox-ip/fullchain.pem
    SSLCertificateKeyFile /etc/letsencrypt/live/musicbox-ip/privkey.pem
    <Directory /var/www/musicbox-secure>
        Options -Indexes
        AllowOverride None
        Require all granted
    </Directory>
</VirtualHost>
APACHE

a2enmod ssl
a2ensite musicbox-ssl
apache2ctl configtest
systemctl reload apache2

install -d -m 0755 /etc/letsencrypt/renewal-hooks/deploy
cat > /etc/letsencrypt/renewal-hooks/deploy/reload-musicbox-apache.sh <<'HOOK'
#!/bin/sh
systemctl reload apache2
HOOK
chmod 0755 /etc/letsencrypt/renewal-hooks/deploy/reload-musicbox-apache.sh

echo 'HTTPS vhost activated. Verify https://106.14.212.171/musicbox-auth/index.php with a POST request.'
