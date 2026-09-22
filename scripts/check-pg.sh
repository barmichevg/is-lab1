#!/usr/bin/env bash
set -euo pipefail

: "${DB_USER:?Set DB_USER to your department server login}"
: "${DB_PASSWORD:?Set DB_PASSWORD to your department server password}"

DB_HOST="${DB_HOST:-pg}"
DB_PORT="${DB_PORT:-5432}"
DB_NAME="${DB_NAME:-studs}"

export PGPASSWORD="$DB_PASSWORD"

psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -v ON_ERROR_STOP=1 <<'SQL'
SELECT current_database() AS database, current_user AS db_user, current_schema() AS current_schema;
SHOW search_path;
SELECT to_regclass('organizations') AS organizations_table;
SELECT proname
FROM pg_proc
WHERE proname LIKE 'lab1_%'
ORDER BY proname;
SQL
