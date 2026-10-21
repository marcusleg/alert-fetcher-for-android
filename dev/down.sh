#!/usr/bin/env bash
# Stops the test environment started by up.sh (the CA in dev/.state is kept).
ENGINE="${CONTAINER_ENGINE:-podman}"
"$ENGINE" rm -f amr-alertmanager amr-dex amr-oauth2-proxy amr-caddy >/dev/null 2>&1 || true
