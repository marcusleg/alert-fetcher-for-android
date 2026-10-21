#!/usr/bin/env python3
"""Send realistic Alertmanager webhook notifications to the Android receiver.

Useful to try the app without a Prometheus stack, and to produce the README screenshots.

    ./scripts/send-demo-alerts.py http://192.168.1.50:9095/webhook --token <bearer token>
    ./scripts/send-demo-alerts.py http://192.168.1.50:9095/webhook --token <t> --resolve-all
"""
import argparse
import base64
import datetime as dt
import hashlib
import json
import sys
import urllib.error
import urllib.request

EXTERNAL_URL = "http://alertmanager.home.arpa:9093"
PROMETHEUS_URL = "http://prometheus.home.arpa:9090"


def ago(minutes: float) -> str:
    t = dt.datetime.now(dt.timezone.utc) - dt.timedelta(minutes=minutes)
    return t.isoformat(timespec="milliseconds").replace("+00:00", "Z")


def alert(labels, annotations, started_min_ago, status="firing", ended_min_ago=None):
    fp = hashlib.sha256(json.dumps(labels, sort_keys=True).encode()).hexdigest()[:16]
    return {
        "status": status,
        "labels": labels,
        "annotations": annotations,
        "startsAt": ago(started_min_ago),
        "endsAt": ago(ended_min_ago) if ended_min_ago is not None else "0001-01-01T00:00:00Z",
        "generatorURL": f"{PROMETHEUS_URL}/graph?g0.expr=up&g0.tab=1",
        "fingerprint": fp,
    }


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
        started_min_ago=300, status="resolved", ended_min_ago=40,
    ),
    alert(
        {"alertname": "HostHighCpuLoad", "severity": "warning", "instance": "proxmox-1.home.arpa:9100", "job": "node-exporter"},
        {"summary": "Host high CPU load (instance proxmox-1.home.arpa:9100)",
         "description": "CPU load is > 80% for 10 minutes."},
        started_min_ago=200, status="resolved", ended_min_ago=150,
    ),
]


def payload(alerts):
    return {
        "version": "4",
        "groupKey": "{}:{}",
        "truncatedAlerts": 0,
        "status": "firing" if any(a["status"] == "firing" for a in alerts) else "resolved",
        "receiver": "android",
        "groupLabels": {},
        "commonLabels": {},
        "commonAnnotations": {},
        "externalURL": EXTERNAL_URL,
        "alerts": alerts,
    }


def post(url, body, args):
    req = urllib.request.Request(url, data=json.dumps(body).encode(), method="POST",
                                 headers={"Content-Type": "application/json"})
    if args.token:
        req.add_header("Authorization", f"Bearer {args.token}")
    elif args.basic:
        req.add_header("Authorization", "Basic " + base64.b64encode(args.basic.encode()).decode())
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            return resp.status
    except urllib.error.HTTPError as e:
        return e.code


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("url", help="webhook URL, e.g. http://192.168.1.50:9095/webhook")
    p.add_argument("--token", help="bearer token")
    p.add_argument("--basic", metavar="USER:PASS", help="basic auth credentials")
    p.add_argument("--resolve-all", action="store_true", help="send every demo alert as resolved")
    args = p.parse_args()

    alerts = DEMO
    if args.resolve_all:
        alerts = [dict(a, status="resolved", endsAt=ago(0)) for a in DEMO]
    # One notification per alert, like Alertmanager with group_by: [alertname].
    failed = 0
    for a in alerts:
        status = post(args.url, payload([a]), args)
        print(f"{status}  {a['status']:8}  {a['labels']['alertname']}")
        failed += status >= 300
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
