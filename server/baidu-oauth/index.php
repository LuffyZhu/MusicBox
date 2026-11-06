<?php
/* MusicBox Baidu device OAuth endpoint. Requires PHP 7+, OpenSSL, HTTPS. */
declare(strict_types=1);

header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');
header('X-Content-Type-Options: nosniff');
header('Referrer-Policy: no-referrer');
umask(0077);

function reply(int $status, array $body) {
    http_response_code($status);
    echo json_encode($body, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    exit;
}

if (($_SERVER['HTTPS'] ?? '') !== 'on') {
    reply(403, ['error' => 'https_required']);
}
if (($_SERVER['REQUEST_METHOD'] ?? '') !== 'POST') {
    header('Allow: POST');
    reply(405, ['error' => 'method_not_allowed']);
}
if (isset($_SERVER['CONTENT_LENGTH']) && (int) $_SERVER['CONTENT_LENGTH'] > 4096) {
    reply(413, ['error' => 'request_too_large']);
}

$configPath = '/etc/musicbox-auth.php';
if (!is_file($configPath)) reply(503, ['error' => 'not_configured']);
$config = require $configPath;
$appKey = $config['app_key'] ?? '';
$secretKey = $config['secret_key'] ?? '';
$stateDir = $config['state_dir'] ?? '/var/lib/musicbox-auth';
if (!is_string($appKey) || $appKey === '' || !is_string($secretKey) || $secretKey === '' || !is_dir($stateDir)) {
    reply(503, ['error' => 'not_configured']);
}

$input = json_decode(file_get_contents('php://input', false, null, 0, 4097), true);
if (!is_array($input)) reply(400, ['error' => 'invalid_json']);
$action = $input['action'] ?? '';

function baidu(string $path, array $query): array {
    $url = 'https://openapi.baidu.com' . $path . '?' . http_build_query($query, '', '&', PHP_QUERY_RFC3986);
    if (function_exists('curl_init')) {
        $curl = curl_init($url);
        curl_setopt_array($curl, [
            CURLOPT_RETURNTRANSFER => true,
            CURLOPT_CONNECTTIMEOUT => 8,
            CURLOPT_TIMEOUT => 12,
            CURLOPT_FOLLOWLOCATION => false,
            CURLOPT_SSL_VERIFYPEER => true,
            CURLOPT_SSL_VERIFYHOST => 2,
            CURLOPT_HTTPHEADER => ['Accept: application/json'],
        ]);
        $body = curl_exec($curl);
        $http = (int) curl_getinfo($curl, CURLINFO_HTTP_CODE);
        curl_close($curl);
    } else {
        $context = stream_context_create([
            'http' => ['method' => 'GET', 'timeout' => 12, 'follow_location' => 0,
                'ignore_errors' => true, 'header' => "Accept: application/json\r\n"],
            'ssl' => ['verify_peer' => true, 'verify_peer_name' => true],
        ]);
        $body = @file_get_contents($url, false, $context, 0, 65537);
        $http = 0;
        foreach (($http_response_header ?? []) as $line) {
            if (preg_match('/^HTTP\/\S+\s+(\d{3})\b/', $line, $match)) $http = (int) $match[1];
        }
    }
    if ($body === false || strlen($body) > 65536 || $http < 200 || $http >= 500) {
        reply(502, ['error' => 'baidu_unavailable']);
    }
    $data = json_decode($body, true);
    if (!is_array($data)) reply(502, ['error' => 'invalid_baidu_response']);
    return $data;
}

function stateFile(string $dir, string $handle): string {
    if (!preg_match('/\A[0-9a-f]{64}\z/D', $handle)) reply(400, ['error' => 'invalid_handle']);
    return rtrim($dir, '/') . '/' . $handle . '.json';
}

function checkRate(string $dir, string $action) {
    $ip = $_SERVER['REMOTE_ADDR'] ?? '';
    $key = hash('sha256', $ip . ':' . $action);
    $file = rtrim($dir, '/') . '/rate-' . $key;
    $stream = @fopen($file, 'c+');
    if ($stream === false || !flock($stream, LOCK_EX)) reply(503, ['error' => 'state_unavailable']);
    $state = json_decode(stream_get_contents($stream), true);
    $window = (int) floor(time() / 60);
    $count = is_array($state) && ($state['window'] ?? 0) === $window ? (int) $state['count'] : 0;
    $limit = $action === 'start' ? 12 : ($action === 'refresh' ? 30 : 90);
    if ($count >= $limit) {
        fclose($stream);
        header('Retry-After: 60');
        reply(429, ['error' => 'rate_limited']);
    }
    rewind($stream); ftruncate($stream, 0);
    fwrite($stream, json_encode(['window' => $window, 'count' => $count + 1]));
    fflush($stream); fclose($stream);
}

if ($action === 'start' || $action === 'poll' || $action === 'refresh') checkRate($stateDir, $action);

if ($action === 'start') {
    $device = baidu('/oauth/2.0/device/code', [
        'response_type' => 'device_code', 'openapi' => 'xpansdk',
        'client_id' => $appKey, 'scope' => 'basic,netdisk',
    ]);
    if (empty($device['device_code']) || empty($device['user_code']) || empty($device['verification_url'])) {
        reply(502, ['error' => 'device_code_failed']);
    }
    $handle = bin2hex(random_bytes(32));
    $interval = max(5, (int) ($device['interval'] ?? 5));
    $expires = time() + min(600, max(30, (int) ($device['expires_in'] ?? 300)));
    $file = stateFile($stateDir, $handle);
    $state = ['code' => $device['device_code'], 'expires' => $expires, 'interval' => $interval, 'next' => 0];
    if (file_put_contents($file, json_encode($state), LOCK_EX) === false) reply(503, ['error' => 'state_unavailable']);
    chmod($file, 0600);
    reply(200, [
        'handle' => $handle, 'user_code' => $device['user_code'],
        'verification_url' => $device['verification_url'],
        'expires_in' => $expires - time(), 'interval' => $interval,
    ]);
}

if ($action === 'poll') {
    $handle = $input['handle'] ?? '';
    if (!is_string($handle)) reply(400, ['error' => 'invalid_handle']);
    $file = stateFile($stateDir, $handle);
    $stream = @fopen($file, 'r+');
    if ($stream === false) reply(404, ['error' => 'unknown_handle']);
    if (!flock($stream, LOCK_EX)) reply(503, ['error' => 'state_unavailable']);
    $state = json_decode(stream_get_contents($stream), true);
    if (!is_array($state) || empty($state['code']) || time() >= (int) ($state['expires'] ?? 0)) {
        fclose($stream); @unlink($file); reply(410, ['error' => 'expired']);
    }
    if (time() < (int) ($state['next'] ?? 0)) {
        fclose($stream); reply(429, ['error' => 'slow_down']);
    }
    $state['next'] = time() + max(5, (int) $state['interval']);
    rewind($stream); ftruncate($stream, 0); fwrite($stream, json_encode($state)); fflush($stream);
    $token = baidu('/oauth/2.0/token', [
        'grant_type' => 'device_token', 'openapi' => 'xpansdk',
        'code' => $state['code'], 'client_id' => $appKey, 'client_secret' => $secretKey,
    ]);
    fclose($stream);
    if (!empty($token['access_token']) && !empty($token['refresh_token'])) {
        @unlink($file);
        reply(200, [
            'access_token' => $token['access_token'], 'refresh_token' => $token['refresh_token'],
            'expires_in' => (int) ($token['expires_in'] ?? 2592000),
        ]);
    }
    $error = $token['error'] ?? '';
    if ($error === 'authorization_pending' || $error === 'slow_down') reply(200, ['error' => $error]);
    @unlink($file);
    reply(400, ['error' => 'authorization_failed']);
}

if ($action === 'refresh') {
    $refresh = $input['refresh_token'] ?? '';
    if (!is_string($refresh) || strlen($refresh) < 8 || strlen($refresh) > 2048) {
        reply(400, ['error' => 'invalid_refresh_token']);
    }
    $token = baidu('/oauth/2.0/token', [
        'grant_type' => 'refresh_token', 'openapi' => 'xpansdk',
        'refresh_token' => $refresh, 'client_id' => $appKey, 'client_secret' => $secretKey,
    ]);
    if (empty($token['access_token']) || empty($token['refresh_token'])) {
        reply(400, ['error' => 'refresh_failed']);
    }
    reply(200, [
        'access_token' => $token['access_token'], 'refresh_token' => $token['refresh_token'],
        'expires_in' => (int) ($token['expires_in'] ?? 2592000),
    ]);
}

reply(400, ['error' => 'unknown_action']);
