<?php

final class PortalAuth
{
    private string $publicKeyPem;

    public function __construct(string $publicKeyPem) {
        $this->publicKeyPem = $publicKeyPem;
    }

    public function requireUser(string $jwt): array
    {
        [$h, $p, $s] = explode('.', $jwt);
        $header = json_decode($this->b64url_decode($h), true);
        $payload = json_decode($this->b64url_decode($p), true);
        $sig = $this->b64url_decode($s);

        if (!is_array($header) || !is_array($payload) || empty($header['alg'])) {
            throw new RuntimeException("Bad token");
        }

        $alg = $header['alg'];
        $signed = $h . "." . $p;

        if ($alg === 'RS256') {
            $ok = openssl_verify($signed, $sig, $this->publicKeyPem, OPENSSL_ALGO_SHA256) === 1;
        } elseif ($alg === 'HS256') {
            
            $mac = hash_hmac('sha256', $signed, $this->publicKeyPem, true);
            $ok = hash_equals($mac, $sig);
        } else {
            throw new RuntimeException("Unsupported alg");
        }

        if (!$ok) throw new RuntimeException("Invalid signature");
        if (($payload['exp'] ?? 0) < time()) throw new RuntimeException("Expired");

        return $payload;
    }

    private function b64url_decode(string $s): string
    {
        $s = strtr($s, '-_', '+/');
        return base64_decode($s . str_repeat('=', (4 - strlen($s) % 4) % 4));
    }
}

function listOrders(PDO $pdo, string $partnerId, string $sortKey, string $dir): array
{
    $dir = strtolower($dir) === 'asc' ? 'ASC' : 'DESC';

    $sortMap = [
        'created' => 'created_at',
        'amount'  => 'total_cents',
        'status'  => 'status',
    ];

    $col = $sortMap[$sortKey] ?? $sortMap['created'];

    $sql = "SELECT id, total_cents, status, created_at
            FROM orders
            WHERE partner_id = :pid
            ORDER BY {$col} {$dir}
            LIMIT 50";

    $st = $pdo->prepare($sql);
    $st->execute([':pid' => $partnerId]);
    return $st->fetchAll(PDO::FETCH_ASSOC);
}
