#!/usr/bin/env python3
import ipaddress
import json
import sys
from urllib.parse import urlsplit


url = sys.argv[1]
api_path = sys.argv[2]
try:
    parsed = urlsplit(url)
    host = parsed.hostname
    port = parsed.port
    if parsed.scheme not in ("http", "https") or parsed.username or parsed.password:
        raise ValueError
    if parsed.query or parsed.fragment or parsed.path not in ("", "/"):
        raise ValueError
    address = ipaddress.ip_address(host)
    if not address.is_loopback:
        raise ValueError
    if port is None or not 1 <= port <= 65535:
        raise ValueError
    if not api_path.startswith("/") or "?" in api_path or "#" in api_path or "\\" in api_path:
        raise ValueError
except (TypeError, ValueError):
    print(
        json.dumps(
            {
                "valid": False,
                "error": "url must use an explicit literal loopback address and port; api-path must be a relative path.",
            }
        )
    )
    raise SystemExit(1)

print(
    json.dumps(
        {
            "valid": True,
            "scheme": parsed.scheme,
            "host": host,
            "port": port,
            "url": url.rstrip("/"),
            "apiPath": api_path.rstrip("/"),
        }
    )
)
