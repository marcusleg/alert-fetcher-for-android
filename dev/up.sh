#!/usr/bin/env bash
# Starts a local, TLS-only test environment for the app:
#
#   https://localhost:9443   Alertmanager behind basic auth (user "phone", password "phone")
#   https://localhost:8443   Alertmanager behind oauth2-proxy, accepting OIDC bearer tokens
#   https://localhost:5556   Dex OIDC provider (admin@example.com / password)
#   http://127.0.0.1:9093    Alertmanager itself, only for injecting test alerts from this machine
#
# A throwaway CA is created in dev/.state/ca.crt; install it on the phone or emulator as a user CA.
# With the emulator, forward the ports so that "localhost" works on the device as well:
#   adb reverse tcp:9443 tcp:9443 && adb reverse tcp:8443 tcp:8443 && adb reverse tcp:5556 tcp:5556
set -euo pipefail

cd "$(dirname "$0")"
ENGINE="${CONTAINER_ENGINE:-podman}"
STATE="$PWD/.state"
mkdir -p "$STATE"

if [[ ! -f "$STATE/ca.crt" ]]; then
  echo "Creating test CA and certificate for localhost"
  openssl req -x509 -newkey rsa:2048 -nodes -days 365 -subj "/CN=Alertmanager Receiver test CA" \
    -addext "basicConstraints=critical,CA:TRUE" -addext "keyUsage=critical,keyCertSign,cRLSign" \
    -keyout "$STATE/ca.key" -out "$STATE/ca.crt" 2>/dev/null
  openssl req -newkey rsa:2048 -nodes -subj "/CN=localhost" -keyout "$STATE/tls.key" -out "$STATE/tls.csr" 2>/dev/null
  openssl x509 -req -in "$STATE/tls.csr" -CA "$STATE/ca.crt" -CAkey "$STATE/ca.key" -CAcreateserial -days 365 \
    -extfile <(printf "subjectAltName=DNS:localhost,IP:127.0.0.1\nextendedKeyUsage=serverAuth") \
    -out "$STATE/tls.crt" 2>/dev/null
  chmod 644 "$STATE"/tls.key
fi

hash() { "$ENGINE" run --rm docker.io/library/caddy:2.10 caddy hash-password --plaintext "$1"; }
DEX_PASSWORD_HASH="$(hash password)"
BASIC_PASSWORD_HASH="$(hash phone)"
sed "s|\${DEX_PASSWORD_HASH}|$DEX_PASSWORD_HASH|" dex.yaml > "$STATE/dex.yaml"

"$ENGINE" rm -f amr-alertmanager amr-dex amr-oauth2-proxy amr-caddy >/dev/null 2>&1 || true

"$ENGINE" run -d --name amr-alertmanager --network host \
  -v "$PWD/alertmanager.yml:/etc/alertmanager/alertmanager.yml:ro,Z" \
  quay.io/prometheus/alertmanager:v0.34.1 \
  --config.file=/etc/alertmanager/alertmanager.yml --web.listen-address=127.0.0.1:9093 \
  --cluster.listen-address= --web.external-url=https://localhost:9443 >/dev/null

"$ENGINE" run -d --name amr-dex --network host -v "$STATE:/state:ro,Z" --tmpfs /var/dex:mode=1777 \
  ghcr.io/dexidp/dex:v2.45.1 dex serve /state/dex.yaml >/dev/null

"$ENGINE" run -d --name amr-caddy --network host -v "$STATE:/state:ro,Z" -v "$PWD/Caddyfile:/etc/caddy/Caddyfile:ro,Z" \
  -e BASIC_PASSWORD_HASH="$BASIC_PASSWORD_HASH" docker.io/library/caddy:2.10 >/dev/null

# Wait for Dex: oauth2-proxy discovers it on startup.
for _ in $(seq 30); do curl -sf --cacert "$STATE/ca.crt" https://localhost:5556/dex/.well-known/openid-configuration >/dev/null && break; sleep 1; done

"$ENGINE" run -d --name amr-oauth2-proxy --network host -v "$STATE:/state:ro,Z" -e SSL_CERT_FILE=/state/ca.crt \
  quay.io/oauth2-proxy/oauth2-proxy:v7.15.5 \
  --provider=oidc --oidc-issuer-url=https://localhost:5556/dex \
  --client-id=oauth2-proxy --client-secret=oauth2-proxy-secret \
  --cookie-secret=0123456789abcdef0123456789abcdef --email-domain='*' \
  --upstream=http://127.0.0.1:9093 --http-address= --https-address=0.0.0.0:8443 \
  --tls-cert-file=/state/tls.crt --tls-key-file=/state/tls.key \
  --redirect-url=https://localhost:8443/oauth2/callback --skip-provider-button \
  --skip-jwt-bearer-tokens=true --extra-jwt-issuers=https://localhost:5556/dex=alertmanager-receiver >/dev/null

echo "Test environment is up. CA certificate: $STATE/ca.crt"
