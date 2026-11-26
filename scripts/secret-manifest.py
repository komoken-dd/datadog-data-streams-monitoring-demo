#!/usr/bin/env python3
"""Emit one Kubernetes Secret to a pipeline; resolved secrets never enter argv."""
import hashlib
import json
import os
import re
import secrets
import sys


def manifest(kind, namespace, name):
    if not all(re.fullmatch(r"[a-z0-9]([-a-z0-9]*[a-z0-9])?", value) for value in (namespace, name)):
        raise ValueError("Invalid Kubernetes namespace or Secret name")
    if kind == "api":
        data = {}
        for identity in ("operator", "simulator", "order", "notification"):
            token = secrets.token_hex(32)
            data[identity + "-token"] = token
            data[identity + "-token-sha256"] = hashlib.sha256(token.encode("ascii")).hexdigest()
    elif kind == "database":
        data = {"runtime-password": secrets.token_hex(32), "migration-password": secrets.token_hex(32)}
    elif kind == "postgresql":
        value = os.environ.get("POSTGRES_PASSWORD")
        if not value:
            raise ValueError("POSTGRES_PASSWORD is required")
        data = {"password": value}
    else:
        raise ValueError("Unsupported Secret kind")
    return {"apiVersion": "v1", "kind": "Secret", "metadata": {"namespace": namespace, "name": name},
            "type": "Opaque", "stringData": data}


if __name__ == "__main__":
    if len(sys.argv) != 4:
        raise SystemExit("Usage: secret-manifest.py <api|database|postgresql> <namespace> <name>")
    print(json.dumps(manifest(*sys.argv[1:])))
