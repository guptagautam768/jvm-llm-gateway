#!/usr/bin/env bash
# Exercises chat, streaming, an exact cache hit, PII redaction, a rate limit, and
# embeddings. WireMock stands in for Ollama, so this does not download a model and
# does not call a paid provider. Postgres and Redis are the real Compose services.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if ! command -v docker >/dev/null 2>&1; then
  echo "docker is required" >&2
  exit 1
fi

KEY_ID="$(python3 -c 'import os; print(os.urandom(4).hex())')"
KEY_SECRET="$(python3 -c 'import os,base64; print(base64.urlsafe_b64encode(os.urandom(32)).decode().rstrip("="))')"
export GATEWAY_BOOTSTRAP_KEY_ID="$KEY_ID"
export GATEWAY_BOOTSTRAP_KEY_SECRET="$KEY_SECRET"
export GATEWAY_RATE_CAPACITY=8
export GATEWAY_RATE_REFILL_PER_SECOND=0.001
TOKEN="lgw_${KEY_ID}_${KEY_SECRET}"

COMPOSE=(docker compose -f docker-compose.yml -f docker-compose.demo.yml)

cleanup() {
  "${COMPOSE[@]}" down --remove-orphans >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "Starting Postgres, Redis, WireMock, and the gateway..."
"${COMPOSE[@]}" up -d --build

echo "Waiting for the management health endpoint..."
ready=0
for _ in $(seq 1 90); do
  if curl -sf "http://127.0.0.1:8081/actuator/health" >/dev/null; then
    ready=1
    break
  fi
  sleep 2
done
if [[ "$ready" -ne 1 ]]; then
  echo "gateway did not become ready" >&2
  "${COMPOSE[@]}" logs gateway >&2 || true
  exit 1
fi

auth=(-H "Authorization: Bearer ${TOKEN}" -H "Content-Type: application/json")

echo
echo "== chat =="
curl -sf "${auth[@]}" http://127.0.0.1:8080/v1/chat/completions \
  -d '{"model":"fast","messages":[{"role":"user","content":"Say hello."}]}'
echo

echo
echo "== streaming =="
curl -sfN "${auth[@]}" http://127.0.0.1:8080/v1/chat/completions \
  -H "Accept: text/event-stream" \
  -d '{"model":"fast","stream":true,"messages":[{"role":"user","content":"Count."}]}'
echo

echo
echo "== exact cache =="
cache_body='{"model":"fast","temperature":0,"messages":[{"role":"user","content":"Cache this sentence."}]}'
curl -sD - -o /tmp/demo-cache-1.json "${auth[@]}" http://127.0.0.1:8080/v1/chat/completions -d "$cache_body" | tr -d '\r' | awk 'BEGIN{IGNORECASE=1} /^x-cache:/ {print}'
curl -sD - -o /tmp/demo-cache-2.json "${auth[@]}" http://127.0.0.1:8080/v1/chat/completions -d "$cache_body" | tr -d '\r' | awk 'BEGIN{IGNORECASE=1} /^x-cache:/ {print}'
if ! grep -qi '^x-cache: exact' <(curl -sD - -o /dev/null "${auth[@]}" http://127.0.0.1:8080/v1/chat/completions -d "$cache_body"); then
  echo "expected X-Cache: exact on the repeated chat call" >&2
  exit 1
fi
echo "cache hit confirmed"

echo
echo "== embeddings and PII redaction =="
curl -sf "${auth[@]}" http://127.0.0.1:8080/v1/embeddings \
  -d '{"model":"embed","input":"mail ada@example.com please"}'
echo
journal="$(curl -sf http://127.0.0.1:8089/__admin/requests)"
if ! grep -q '\[EMAIL_1\]' <<<"$journal"; then
  echo "provider did not see the redacted email placeholder" >&2
  exit 1
fi
if grep -q 'ada@example.com' <<<"$journal"; then
  echo "provider saw the raw email address" >&2
  exit 1
fi
echo "PII redacted before the provider"

echo
echo "== rate limit =="
limited=0
for _ in $(seq 1 20); do
  code="$(curl -s -o /tmp/demo-limit.json -w '%{http_code}' "${auth[@]}" http://127.0.0.1:8080/v1/embeddings \
    -d '{"model":"embed","input":"again"}')"
  if [[ "$code" == "429" ]]; then
    limited=1
    break
  fi
done
if [[ "$limited" -ne 1 ]]; then
  echo "expected a 429 rate_limit_exceeded" >&2
  exit 1
fi
python3 -c 'import json; body=json.load(open("/tmp/demo-limit.json")); assert body["error"]["code"]=="rate_limit_exceeded"'
echo "429 rate_limit_exceeded confirmed"
echo
echo "Demo finished."
