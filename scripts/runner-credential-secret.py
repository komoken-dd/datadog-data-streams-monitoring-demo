#!/usr/bin/env python3
"""Project only the operator credential into the selected runner namespace."""
import base64
import json
import re
import sys

namespace, name = sys.argv[1:]
if not all(re.fullmatch(r"[a-z0-9]([-a-z0-9]*[a-z0-9])?", v) for v in (namespace, name)):
    raise SystemExit("Invalid runner Secret destination")
source = json.load(sys.stdin)
token = base64.b64decode(source["data"]["operator-token"], validate=True).decode("ascii")
if not re.fullmatch(r"[A-Za-z0-9_-]{43,128}", token):
    raise SystemExit("Invalid operator credential")
credential = {"auth_type": "Token Auth", "credentials": [
    {"tokenName": "Authorization", "tokenValue": "Bearer " + token}
]}
print(json.dumps({"apiVersion": "v1", "kind": "Secret", "metadata": {"namespace": namespace, "name": name},
                  "type": "Opaque", "stringData": {"http-token.json": json.dumps(credential)}}))
