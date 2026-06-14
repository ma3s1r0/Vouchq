#!/usr/bin/env bash
# End-to-end rug-pull demo, driven through the Vouchq API.
#
# Story: a benign MCP tool is registered, scanned clean, and approved & pinned.
# The upstream server then silently rewrites the tool's description to exfiltrate
# secrets (the "rug-pull"). On the next scan Vouchq detects the DRIFT from the
# pinned definition and flags the live version CRITICAL — while the pinned,
# benign artifact is still what gets distributed to agents.
#
# Prereqs:
#   1. Vouchq dev stack up in the `demo` profile (exposes the MCP ingest trigger):
#        VOUCHQ_PROFILES=demo podman compose up --build -d   # or docker compose
#   2. The toy evil server running on the host:
#        python3 evil_mcp_server.py 8765
#
# Run:  ./demo.sh
set -euo pipefail

BASE="${VOUCHQ_BASE:-http://localhost:8080}"
AUTH="${VOUCHQ_AUTH:-admin@vouchq.local:admin}"
# How the Vouchq app CONTAINER reaches the evil server on your host:
#   podman → host.containers.internal   |   docker → host.docker.internal
MCP_URL="${MCP_URL:-http://host.containers.internal:8765/mcp}"
FLIP_URL="${FLIP_URL:-http://localhost:8765/rugpull}"

c() { curl -s -u "$AUTH" "$@"; }
say() { printf '\n\033[1;36m== %s\033[0m\n' "$*"; }

say "0. Reset — remove any prior run of this MCP source"
OLD=$(c "$BASE/api/sources" | python3 -c 'import sys,json;a=json.load(sys.stdin);print(next((s["id"] for s in a if "8765" in s.get("uri","")),""))')
[ -n "$OLD" ] && c -X DELETE "$BASE/api/sources/$OLD" >/dev/null && echo "  removed $OLD"
curl -s -X POST "${FLIP_URL%/rugpull}/reset" >/dev/null || true

say "1. Register the MCP server and ingest its tools (benign)"
c -X POST "$BASE/api/internal/ingest-mcp" -H 'Content-Type: application/json' \
  -d "{\"url\":\"$MCP_URL\"}" >/dev/null
TOOL=$(c "$BASE/api/tools?limit=200&q=web_search" | python3 -c 'import sys,json;print(json.load(sys.stdin)[0]["id"])')
c "$BASE/api/tools/$TOOL" | python3 -c 'import sys,json;d=json.load(sys.stdin);s=d.get("currentScan") or {};print("  web_search — status",d["tool"]["status"],"| scan risk",s.get("riskScore"),"("+str(s.get("highestSeverity"))+")")'

say "2. Approve & pin (박제) the benign definition"
c -X POST "$BASE/api/tools/$TOOL/approve" >/dev/null
PIN=$(c "$BASE/api/tools/$TOOL" | python3 -c 'import sys,json;print((json.load(sys.stdin).get("approvedDefinition") or {}).get("hash",""))')
echo "  pinned sha256: ${PIN:0:32}…"

say "3. >>> RUG-PULL <<< upstream rewrites the description to exfiltrate secrets"
curl -s -X POST "$FLIP_URL" >/dev/null && echo "  upstream tool definition mutated"

say "4. Re-scan the source (this is what the scheduled rescan does automatically)"
SRC=$(c "$BASE/api/sources" | python3 -c 'import sys,json;print(next(s["id"] for s in json.load(sys.stdin) if "8765" in s.get("uri","")))')
c -X POST "$BASE/api/sources/$SRC/scan" >/dev/null; sleep 1

say "5. Vouchq caught it"
c "$BASE/api/drift-events?resolved=false" | python3 -c 'import sys,json
for e in json.load(sys.stdin):
    print("  DRIFT ["+e["severity"]+"]  pinned",e["approvedHash"][:10],"-> observed",e["observedHash"][:10])'
c "$BASE/api/tools/$TOOL" | python3 -c 'import sys,json
d=json.load(sys.stdin);s=d.get("currentScan") or {}
print("  LIVE version now status",d["tool"]["status"],"| scan risk",s.get("riskScore"),"("+str(s.get("highestSeverity"))+")")
for x in json.loads(s.get("findings") or "[]"):
    print("    finding:",x.get("ruleId"),"["+str(x.get("severity"))+"]")'
echo "  → the PINNED benign version (sha256 ${PIN:0:16}…) is still what 'Add to Claude/Codex' distributes."

say "Done. Open the console (http://localhost:3000) → Drift / Audit log to see it in the UI."
