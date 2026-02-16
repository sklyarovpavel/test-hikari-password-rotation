#!/bin/sh
set -eu
BASE_URL="${1:-http://localhost:8080}"
curl -sS -X POST "$BASE_URL/internal/config/override" \
  -H "Content-Type: application/json" \
  -d '{"spring.datasource.username":"app_user_a","spring.datasource.password":"app_pass_a"}'
echo
echo "Switched spring.datasource.* to app_user_a"

