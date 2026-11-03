#!/usr/bin/env python3
"""Two-phase HTTP regression. Restart ALL test Payara JVMs between prepare and verify.
Only loopback endpoints are allowed; use an isolated database and disposable account.
State stores test payloads/results, never the password or cookies.
"""
import argparse
import http.cookiejar
import json
import os
from pathlib import Path
import urllib.error
import urllib.parse
import urllib.request
import uuid

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("phase", choices=("prepare", "verify"))
parser.add_argument("state", type=Path)
args = parser.parse_args()
base = os.environ["TEST_BASE_URL"].rstrip("/")
if urllib.parse.urlparse(base).hostname not in ("127.0.0.1", "localhost", "::1"):
    parser.error("This destructive fixture test is restricted to an isolated loopback test application")
username = os.environ["TEST_AUTH_USER"]
client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
csrf = None


def request(method, route, body=None, key=None, expected=200):
    headers = {"Content-Type": "application/json"}
    if csrf:
        headers["X-CSRF-Token"] = csrf
    if key:
        headers["X-Idempotency-Key"] = key
    data = None if body is None else body.encode("utf-8")
    call = urllib.request.Request(base + route, data=data, headers=headers, method=method)
    try:
        response = client.open(call, timeout=20)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        raw = response.read().decode("utf-8")
        assert response.status == expected, (method, route, response.status, raw)
        return json.loads(raw) if raw else None


login = request("POST", "/api/auth/login", json.dumps({"username": username, "password": os.environ["TEST_AUTH_PASSWORD"]}))
csrf = login["csrf"]

if args.phase == "prepare":
    if args.state.exists():
        parser.error("State already exists; use a new path, do not overwrite unfinished fixtures")
    marker = "restart-" + str(uuid.uuid4())
    actions = []

    def perform(route, value, status=200):
        body = json.dumps(value, ensure_ascii=False) if value is not None else None
        key = str(uuid.uuid4())
        result = request("POST", route, body, key, status)
        actions.append({"route": route, "body": body, "key": key, "status": status, "result": result})
        return result

    def fixture(suffix, count):
        return {"name": marker + suffix, "annualTurnover": 10, "employeesCount": str(count), "rating": 1,
                "type": "COMMERCIAL", "coordinates": {"x": 1, "y": 2},
                "officialAddress": {"zipCode": "", "town": {"name": marker, "x": 1, "y": 2, "z": 3}}}

    first = perform("/api/organizations", fixture("-first", 4), 201)
    second = perform("/api/organizations", fixture("-second", 6), 201)
    perform(f"/api/special-operations/{first['id']}/add-employee", None)
    merged = perform("/api/special-operations/merge", {
        "firstOrganizationId": first["id"], "secondOrganizationId": second["id"],
        "name": marker + "-merged", "officialAddress": {"id": first["officialAddress"]["id"]}})
    assert merged["employeesCount"] == "11"
    args.state.parent.mkdir(parents=True, exist_ok=True)
    args.state.write_text(json.dumps({"username": username, "marker": marker, "actions": actions}, ensure_ascii=False, indent=2), encoding="utf-8")
    print("PREPARED: create/addEmployee/merge committed; stop every Payara JVM, keep PostgreSQL and accounts, then run verify.")
else:
    state = json.loads(args.state.read_text(encoding="utf-8"))
    assert state["username"] == username, "Replay must use the same authenticated owner after a NEW login"
    for action in state["actions"]:
        actual = request("POST", action["route"], action["body"], action["key"], action["status"])
        assert actual == action["result"], ("Response changed after process restart", action["route"])
    first, second, _, merged = (action["result"] for action in state["actions"])
    request("GET", f"/api/organizations/{first['id']}", expected=404)
    request("GET", f"/api/organizations/{second['id']}", expected=404)
    assert request("GET", f"/api/organizations/{merged['id']}") == merged
    listing = request("GET", "/api/organizations?filterValue=" + urllib.parse.quote(state["marker"]))
    assert listing["total"] == 1 and listing["items"][0]["id"] == merged["id"], "Replay duplicated a mutation"
    request("DELETE", f"/api/organizations/{merged['id']}", expected=204)
    for kind, ids in (
        ("addresses", {first["officialAddress"]["id"], second["officialAddress"]["id"]}),
        ("coordinates", {first["coordinates"]["id"], second["coordinates"]["id"]}),
        ("locations", {first["officialAddress"]["town"]["id"], second["officialAddress"]["town"]["id"]}),
    ):
        for identifier in ids:
            request("DELETE", f"/api/reference/{kind}/{identifier}", expected=204)
    print("PASS: all four original responses replayed after restart/new login, no duplicate writes, fixtures removed.")
request("POST", "/api/auth/logout", expected=204)
