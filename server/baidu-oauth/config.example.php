<?php
/* Copy to /etc/musicbox-auth.php. Never put the real secret under DocumentRoot. */
return [
    'app_key' => 'REPLACE_WITH_BAIDU_APP_KEY',
    'secret_key' => 'REPLACE_WITH_BAIDU_SECRET_KEY',
    'state_dir' => '/var/lib/musicbox-auth',
];
