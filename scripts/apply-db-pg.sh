#!/usr/bin/env bash
set -euo pipefail

: "${DB_USER:?Set DB_USER to your department server login}"
: "${DB_PASSWORD:?Set DB_PASSWORD to your department server password}"

DB_HOST="${DB_HOST:-pg}"
DB_PORT="${DB_PORT:-5432}"
DB_NAME="${DB_NAME:-studs}"

export PGPASSWORD="$DB_PASSWORD"
psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" \
  -v ON_ERROR_STOP=1 -f db/schema.sql
