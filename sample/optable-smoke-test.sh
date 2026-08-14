#!/usr/bin/env bash
#
# Smoke test for the optable-targeting module against the live Optable edge.
#
# Runs three happy-path scenarios, each starting its own Prebid Server:
#   1. na.edge.optable.co  - bidders enriched with eids AND id5_signature returned in passthrough
#   2. ca.edge.optable.co  - bidders enriched with eids AND adserver targeting keywords on the
#                            bids, but no id5_signature (that tenant has no id5 there)
#   3. legacy execution plan (processed-auction-request stage) - bidders still enriched
#
# The two edges hold different data for the prebidtest tenant, which is why the expectations
# differ rather than being uniform: na resolves an id5 signature but returns an empty audience
# (so no keywords), ca returns three audience segments but no id5. Between them the two
# scenarios cover both halves of what the module renders.
#
# Scenarios 1 and 2 use the current split plan (raw-auction-request + bidder-request), which
# samples per bidder, so they also assert that pubmatic - configured at 0% enrichment in
# sample-app-settings-optable.yaml - is never enriched.
#
# Usage: ./sample/optable-smoke-test.sh   (run from the repository root)

set -uo pipefail

BUNDLE=extra/bundle/target/prebid-server-bundle.jar
REQUEST=sample/requests/optable-app-smoke.json
PORT=8090
ADMIN_PORT=8061
# The Optable edge derives its privacy jurisdiction from the forwarded device IP.
# device.ip in the request is deliberately non-EU: an EU address makes the edge
# return refs without a signature, which would fail scenario 1 for the wrong reason.

if [ ! -f "$BUNDLE" ]; then
    echo "FAIL: $BUNDLE not found - build it first:"
    echo "  mvn clean package -Dmaven.test.skip=true -Ddocker.skip=true"
    exit 1
fi

server_pid=""
cleanup() { [ -n "$server_pid" ] && kill "$server_pid" 2>/dev/null; }
trap cleanup EXIT

start_server() {
    local config=$1 log=$2
    java -jar "$BUNDLE" \
        --spring.config.additional-location="$config" \
        --server.http.port=$PORT --admin.port=$ADMIN_PORT > "$log" 2>&1 &
    server_pid=$!

    for _ in $(seq 1 60); do
        if curl -sf -o /dev/null "http://localhost:$PORT/status"; then return 0; fi
        sleep 2
    done
    echo "FAIL: server did not come up, see $log"
    return 1
}

stop_server() {
    [ -z "$server_pid" ] && return 0
    kill "$server_pid" 2>/dev/null
    wait "$server_pid" 2>/dev/null
    server_pid=""
    # A scenario whose server is still holding the port would silently be answered by
    # the previous scenario's server, which passes for entirely the wrong reason.
    for _ in $(seq 1 30); do
        curl -sf -o /dev/null "http://localhost:$PORT/status" || return 0
        sleep 1
    done
    echo "FAIL: port $PORT still in use after shutdown"
    return 1
}

auction() {
    curl -s -X POST "http://localhost:$PORT/openrtb2/auction" \
        -H "Content-Type: application/json" --data @"$REQUEST"
}

# Writes an auction response that shows enrichment into $1. The live Optable edge
# intermittently drops a call issued back-to-back with the previous one, which enriches
# nobody; that is a transport hiccup rather than a module failure, so retry a few times
# before letting the assertions call it a real miss.
auction_with_enrichment() {
    local out=$1
    for _ in 1 2 3; do
        auction > "$out"
        if grep -q '"eids"' "$out"; then return 0; fi
        sleep 2
    done
    return 0
}

# $1 = scenario name, $2 = config, $3 = expect_id5 (yes|no),
# $4 = per-bidder sampling (yes|no), $5 = expect adserver keywords (yes|no)
run_scenario() {
    local name=$1 config=$2 expect_id5=$3 per_bidder=$4 expect_keywords=$5
    local log response status
    log=$(mktemp)
    response=$(mktemp)

    echo "=== $name ($config)"
    start_server "$config" "$log" || return 1

    # The first auction after startup pays for lazy init (TLS handshake, DNS, class loading)
    # and routinely exceeds the hook timeout, so it is a warm-up rather than the assertion.
    auction > /dev/null
    sleep 2
    auction_with_enrichment "$response"

    stop_server || { rm -f "$log" "$response"; return 1; }

    EXPECT_ID5="$expect_id5" PER_BIDDER="$per_bidder" EXPECT_KEYWORDS="$expect_keywords" \
        python3 - "$response" <<'PY'
import json, os, sys

response = json.load(open(sys.argv[1]))
expect_id5 = os.environ["EXPECT_ID5"] == "yes"
per_bidder = os.environ["PER_BIDDER"] == "yes"
expect_keywords = os.environ["EXPECT_KEYWORDS"] == "yes"
ext = response.get("ext", {})
failures = []

# Enrichment: the bidder request carries the eids Optable resolved.
http_calls = ext.get("debug", {}).get("httpcalls", {})
enriched = sorted(
    bidder for bidder, calls in http_calls.items()
    if any("eids" in (call.get("requestbody") or "") for call in calls)
)
if enriched:
    print("  enriched bidders: " + ", ".join(enriched))
else:
    failures.append("no bidder request contained eids (called: %s)" % sorted(http_calls))

# pubmatic is pinned to 0% in sample-app-settings-optable.yaml. Only the split plan
# samples per bidder; the legacy plan enriches the whole request, so it is exempt.
if per_bidder:
    if "pubmatic" not in http_calls:
        failures.append("pubmatic was not called, cannot verify it is left unenriched")
    elif "pubmatic" in enriched:
        failures.append("pubmatic is configured at 0% enrichment but was enriched")
    else:
        print("  pubmatic (0% enrichment): not enriched, as expected")

# adserver-targeting is on for this account, so the audience Optable returns must surface
# as ad server keywords on the bids. Anything that is not an hb_* key was put there by the
# module (the keyspace itself is tenant-owned, so it is not hardcoded here).
keywords = {}
for seatbid in response.get("seatbid") or []:
    for bid in seatbid.get("bid") or []:
        targeting = (bid.get("ext") or {}).get("prebid", {}).get("targeting") or {}
        keywords.update({k: v for k, v in targeting.items() if not k.startswith("hb_")})

if expect_keywords and not keywords:
    failures.append("expected adserver targeting keywords on a bid, got none")
elif not expect_keywords and keywords:
    failures.append("expected no targeting keywords, got %s" % json.dumps(keywords))
else:
    print("  adserver targeting keywords: %s"
          % (json.dumps(keywords) if keywords else "none (tenant has no segments on this edge)"))

signature = (
    ext.get("prebid", {}).get("passthrough", {}) or {}
).get("optable", {}).get("id5_signature")

if expect_id5 and not signature:
    failures.append("expected id5_signature in ext.prebid.passthrough, got none")
elif not expect_id5 and signature:
    failures.append("expected no id5_signature, got %s..." % signature[:32])
else:
    print("  id5_signature: %s" % (signature[:40] + "..." if signature else "absent (as expected)"))

if failures:
    for failure in failures:
        print("  FAIL: " + failure)
    sys.exit(1)
print("  PASS")
PY
    status=$?
    rm -f "$log" "$response"
    return $status
}

overall=0
run_scenario "na - enrichment + id5" \
    sample/configs/prebid-config-with-optable.yaml yes yes no || overall=1
run_scenario "ca - enrichment + adserver keywords, no id5" \
    sample/configs/prebid-config-with-optable-ca.yaml no yes yes || overall=1
run_scenario "legacy execution plan - enrichment" \
    sample/configs/prebid-config-with-optable-legacy-plan.yaml yes no no || overall=1

echo
[ $overall -eq 0 ] && echo "ALL SMOKE TESTS PASSED" || echo "SMOKE TESTS FAILED"
exit $overall
