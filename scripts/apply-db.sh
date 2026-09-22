#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
DOCKER=${DOCKER:-docker}
$DOCKER compose exec -T postgres psql -v ON_ERROR_STOP=1 -U postgres -d information_systems < db/schema.sql
echo "Database schema, constraints and Lab 1 functions applied successfully."
