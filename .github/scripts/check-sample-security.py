#!/usr/bin/env python3
"""Exercise the servlet sample's passive Security advisor, including native-image field metadata."""

import http.cookiejar
import json
import sys
import urllib.parse
import urllib.request


def check(base_url):
    cookies = http.cookiejar.CookieJar()
    client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cookies))
    endpoint = base_url.rstrip("/") + "/bootui/api/security"

    def read(request):
        with client.open(request, timeout=60) as response:
            return json.load(response)

    read(endpoint)
    token = next((cookie.value for cookie in cookies if cookie.name == "XSRF-TOKEN"), None)
    assert token, "The servlet sample must issue its CSRF cookie"
    report = read(
        urllib.request.Request(
            endpoint + "/scan",
            data=b"{}",
            headers={
                "Content-Type": "application/json",
                "Origin": base_url.rstrip("/"),
                "X-XSRF-TOKEN": urllib.parse.unquote(token),
            },
        )
    )
    assert report["filterChainsAnalyzed"] == 3, "Application filter-chain inventory was not read"
    assert report["evidence"]["usable"] is True, "No usable security evidence was observed"
    assert report["analysisErrors"] == [], "Security rule evaluation failed"
    assert report["scan"]["status"] == "PARTIAL", "Custom sample security remains partly unobserved"
    assert report["evidence"]["coverageComplete"] is False, "Do not invent complete sample coverage"
    assert report["evidence"]["limitations"], "The sample must retain its real observation limits"
    assert not any(
        reason.startswith("Filter chains:") for reason in report["evidence"]["limitations"]
    ), "Framework chain metadata could not be read"
    assert read(endpoint) == report, "Passive reads must retain the completed assessment"
    print("OK: Security observed all 3 sample chains and retained usable, honestly partial evidence.")


if __name__ == "__main__":
    check(sys.argv[1])
