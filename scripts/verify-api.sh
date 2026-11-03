#!/usr/bin/env bash
set -euo pipefail
# Integration tests authenticate and create their own fixtures. Missing settings fail the run.
: "${TEST_BASE_URL:?Set TEST_BASE_URL, for example http://localhost:18080/is-lab1}"
: "${TEST_AUTH_USER:?Set TEST_AUTH_USER}"
: "${TEST_AUTH_PASSWORD:?Set TEST_AUTH_PASSWORD}"
cd "$(dirname "$0")/.."
mvn -Pintegration -Dit.test=ApiIT verify
