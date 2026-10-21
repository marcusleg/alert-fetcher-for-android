#!/usr/bin/env python3
"""Inject realistic homelab alerts into an Alertmanager through its API.

Useful to try the app without a Prometheus stack, and to produce the README screenshots.
Works with the local test environment from dev/up.sh out of the box:

    ./scripts/send-demo-alerts.py                          # fire all demo alerts (and the Watchdog)
    ./scripts/send-demo-alerts.py --resolve KubeJobFailed  # resolve some of them
    ./scripts/send-demo-alerts.py --resolve-all
    ./scripts/send-demo-alerts.py --no-watchdog            # let the Watchdog heartbeat expire

Against another Alertmanager:

    ./scripts/send-demo-alerts.py --url https://alertmanager.example.com --user admin --password ... --cacert ca.crt
"""
import argparse
import base64
import datetime as dt
import json
import ssl
import sys
import urllib.error
import urllib.request

PROMETHEUS_URL = "https://prometheus.home.arpa"


def ago(minutes: float) -> str:
    t = dt.datetime.now(dt.timezone.utc) - dt.timedelta(minutes=minutes)
    return t.isoformat(timespec="milliseconds").replace("+00:00", "Z")


def alert(labels, annotations, started_min_ago):
    return {
        "labels": labels,
        "annotations": annotations,
        "startsAt": ago(started_min_ago),
        "generatorURL": f"{PROMETHEUS_URL}/graph?g0.expr=up&g0.tab=1",
    }


WATCHDOG = alert(
    {"alertname": "Watchdog", "severity": "none"},
    {"summary": "An alert that should always be firing to certify that Alertmanager is working properly."},
    started_min_ago=60 * 24 * 3,
)

DEMO = [
    alert(
        {"alertname": "NodeFilesystemSpaceFillingUp", "severity": "critical", "instance": "nas.home.arpa:9100",
         "job": "node-exporter", "mountpoint": "/mnt/tank", "device": "tank/media"},
        {"summary": "Filesystem /mnt/tank is predicted to run out of space within the next 4 hours.",
         "description": "Filesystem on tank/media at nas.home.arpa:9100 has only 3.2% available space left and is filling up.",
         "runbook_url": "https://runbooks.prometheus-operator.dev/runbooks/node/nodefilesystemspacefillingup"},
        started_min_ago=47,
    ),
    alert(
        {"alertname": "KubeNodeNotReady", "severity": "critical", "node": "pi-worker-3", "namespace": "kube-system",
         "job": "kube-state-metrics", "condition": "Ready"},
        {"summary": "Node is not ready.",
         "description": "pi-worker-3 has been unready for more than 15 minutes.",
         "runbook_url": "https://runbooks.prometheus-operator.dev/runbooks/kubernetes/kubenodenotready"},
        started_min_ago=18,
    ),
    alert(
        {"alertname": "KubePodCrashLooping", "severity": "warning", "namespace": "media",
         "pod": "jellyfin-7d9c8b6f4-x2x9k", "container": "jellyfin", "job": "kube-state-metrics"},
        {"summary": "Pod is crash looping.",
         "description": "Pod media/jellyfin-7d9c8b6f4-x2x9k (jellyfin) is in waiting state (reason: \"CrashLoopBackOff\").",
         "runbook_url": "https://runbooks.prometheus-operator.dev/runbooks/kubernetes/kubepodcrashlooping",
         "dashboard_url": "http://grafana.home.arpa/d/pods?var-namespace=media"},
        started_min_ago=134,
    ),
    alert(
        {"alertname": "CertificateExpiringSoon", "severity": "warning", "namespace": "ingress",
         "name": "wildcard-home-arpa", "issuer_name": "letsencrypt"},
        {"summary": "Certificate wildcard-home-arpa expires in 6 days.",
         "description": "cert-manager failed to renew the certificate; check the ACME DNS-01 solver."},
        started_min_ago=60 * 26,
    ),
    alert(
        {"alertname": "TargetDown", "severity": "warning", "namespace": "home-automation", "job": "home-assistant",
         "service": "home-assistant"},
        {"summary": "One or more targets are unreachable.",
         "description": "100% of the home-assistant/home-assistant targets in home-automation namespace are down."},
        started_min_ago=9,
    ),
    alert(
        {"alertname": "CPUThrottlingHigh", "severity": "info", "namespace": "monitoring",
         "pod": "prometheus-k8s-0", "container": "prometheus"},
        {"summary": "Processes experience elevated CPU throttling.",
         "description": "34% throttling of CPU in namespace monitoring for container prometheus in pod prometheus-k8s-0."},
        started_min_ago=72,
    ),
    alert(
        {"alertname": "KubeJobFailed", "severity": "warning", "namespace": "backup", "job_name": "restic-nightly-29348760",
         "job": "kube-state-metrics"},
        {"summary": "Job failed to complete.",
         "description": "Job backup/restic-nightly-29348760 failed to complete. Removing failed job after investigation should clear this alert."},
        started_min_ago=300,
    ),
    alert(
        {"alertname": "HostHighCpuLoad", "severity": "warning", "instance": "proxmox-1.home.arpa:9100", "job": "node-exporter"},
        {"summary": "Host high CPU load (instance proxmox-1.home.arpa:9100)",
         "description": "CPU load is > 80% for 10 minutes."},
        started_min_ago=200,
    ),
]



def post(args, alerts):
    body = json.dumps(alerts).encode()
    req = urllib.request.Request(args.url.rstrip("/") + "/api/v2/alerts", data=body, method="POST",
                                 headers={"Content-Type": "application/json"})
    if args.token:
        req.add_header("Authorization", f"Bearer {args.token}")
    elif args.user:
        credentials = f"{args.user}:{args.password or ''}".encode()
        req.add_header("Authorization", "Basic " + base64.b64encode(credentials).decode())
    context = ssl.create_default_context(cafile=args.cacert) if args.cacert else None
    try:
        with urllib.request.urlopen(req, timeout=10, context=context) as resp:
            return resp.status
    except urllib.error.HTTPError as e:
        return e.code


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--url", default="http://127.0.0.1:9093", help="Alertmanager base URL (default: the dev/up.sh instance)")
    p.add_argument("--user", help="basic auth user")
    p.add_argument("--password", help="basic auth password")
    p.add_argument("--token", help="bearer token")
    p.add_argument("--cacert", help="CA certificate to trust for https URLs")
    p.add_argument("--resolve", nargs="+", metavar="ALERTNAME", help="resolve these demo alerts instead of firing")
    p.add_argument("--resolve-all", action="store_true", help="resolve every demo alert")
    p.add_argument("--no-watchdog", action="store_true", help="don't (re)fire the Watchdog heartbeat")
    p.add_argument("--hours", type=float, default=1, help="how long fired alerts stay active (default 1)")
    args = p.parse_args()

    if args.resolve or args.resolve_all:
        names = {a["labels"]["alertname"] for a in DEMO} if args.resolve_all else set(args.resolve)
        alerts = [dict(a, endsAt=ago(0)) for a in DEMO if a["labels"]["alertname"] in names]
        action = "resolved"
    else:
        alerts = [dict(a, endsAt=ago(-60 * args.hours)) for a in DEMO]
        if not args.no_watchdog:
            alerts.append(dict(WATCHDOG, endsAt=ago(-60 * args.hours)))
        action = "firing"

    status = post(args, alerts)
    for a in alerts:
        print(f"{action:8}  {a['labels']['alertname']}")
    print(f"HTTP {status}")
    sys.exit(0 if status < 300 else 1)


if __name__ == "__main__":
    main()
