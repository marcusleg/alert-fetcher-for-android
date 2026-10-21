#!/usr/bin/env bash
# Stops the test environment started by up.sh (the CA in dev/.state is kept).
ENGINE="${CONTAINER_ENGINE:-podman}"
"$ENGINE" rm -f af-alertmanager af-dex af-oauth2-proxy af-caddy >/dev/null 2>&1 || true
