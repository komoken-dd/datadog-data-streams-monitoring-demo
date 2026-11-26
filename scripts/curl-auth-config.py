#!/usr/bin/env python3
"""Send headers to curl's stdin, never its command line or diagnostic output."""
import base64
import json
import os
import re
import sys

if sys.argv[1:] == ["datadog"]:
    headers = ["Accept: application/json"]
    for variable, header in (("DD_API_KEY", "DD-API-KEY"), ("DD_APP_KEY", "DD-APPLICATION-KEY")):
        value = os.environ.get(variable, "")
        if not re.fullmatch(r"[A-Za-z0-9_-]{16,256}", value):
            raise SystemExit("Missing or invalid Datadog credential")
        headers.append(header + ": " + value)
elif sys.argv[1:] == ["operator"]:
    value = os.environ.get("BROKERAGE_DEMO_OPERATOR_TOKEN")
    if not value:
        payload = json.load(sys.stdin)
        value = base64.b64decode(payload["data"]["operator-token"], validate=True).decode("ascii")
    if not re.fullmatch(r"[A-Za-z0-9_-]{43,128}", value):
        raise SystemExit("Missing or invalid operator token")
    headers = ["Authorization: Bearer " + value]
else:
    raise SystemExit("Expected datadog or operator authentication mode")

for header in headers:
    print("header = " + json.dumps(header))
