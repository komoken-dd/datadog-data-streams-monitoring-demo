#!/usr/bin/env python3
"""Bind a saved plan to its selected backend, inputs, site, and exact bytes.

Only input hashes are persisted; variable values and credentials are never logged.
The shell wrapper holds the per-state lock for every operation.
"""

import hashlib
import json
import os
from pathlib import Path
import sys


def canonical(value):
    return str(Path(value).expanduser().resolve())


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def identity(directory, state, variables, site, kind):
    directory = Path(directory)
    paths = {Path(variables).resolve()}
    for pattern in ("*.tf", "*.tf.json", ".terraform.lock.hcl", "terraform.tfvars", "terraform.tfvars.json", "*.auto.tfvars", "*.auto.tfvars.json"):
        paths.update(p.resolve() for p in directory.glob(pattern) if p.is_file())
    # Terraform consumes TF_VAR_* and CLI arguments in addition to tfvars files.
    # CLI overrides are forbidden by the wrapper; hash TF_VAR values without saving them.
    env = {k: v for k, v in os.environ.items() if k.startswith("TF_VAR_")}
    return {
        "schema_version": 1,
        "state": canonical(state),
        "variables": canonical(variables),
        "site": site,
        "kind": kind,
        "inputs": {str(p): digest(p) for p in sorted(paths)},
        "environment_hash": hashlib.sha256(json.dumps(env, sort_keys=True).encode()).hexdigest(),
        "credential_hash": hashlib.sha256(json.dumps(
            {k: os.environ.get(k, "") for k in ("DD_API_KEY", "DD_APP_KEY")}, sort_keys=True,
        ).encode()).hexdigest(),
    }


def main():
    command, *args = sys.argv[1:]
    if command == "canonical":
        print(canonical(args[0]))
        return
    if command == "scope":
        print(hashlib.sha256(canonical(args[0]).encode()).hexdigest()[:24])
        return
    directory, state, variables, site, kind, plan, manifest = args
    expected = identity(directory, state, variables, site, kind)
    expected["plan_sha256"] = digest(plan)
    if command == "record":
        # `show -json` is provided through stdin, never written to a file: it can
        # contain provider-sensitive values even when this project's vars do not.
        planned = json.load(sys.stdin)
        effective = planned.get("variables", {})
        planned_site = effective.get("datadog_site", {}).get("value")
        environment = effective.get("environment", {}).get("value")
        if planned_site != site or not isinstance(environment, str) or not environment:
            raise ValueError("The saved plan's site/environment does not match the selected deployment")
        expected["environment"] = environment
        Path(manifest).write_text(json.dumps(expected, indent=2) + "\n")
    elif command == "verify":
        saved = json.loads(Path(manifest).read_text())
        environment = saved.pop("environment", None)
        if not environment or saved != expected:
            raise ValueError("Saved plan identity or inputs changed; generate and review a new plan")
        print(f"Verified saved {kind} plan for environment: {environment}")
    else:
        raise ValueError("Unknown plan guard operation")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, KeyError) as error:
        # Deliberately do not render parsed variable content in failure diagnostics.
        print(f"Refusing to use saved Terraform plan: {error}", file=sys.stderr)
        sys.exit(1)
