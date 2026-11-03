#!/bin/sh
set -eu
umask 077

APP_DIR=$(CDPATH= cd -P "$(dirname "$0")" && pwd)
DB_HOST=${DB_HOST:-pg}
DB_PORT=${DB_PORT:-5432}
DB_NAME=${DB_NAME:-studs}
DB_USER=${DB_USER:-s465142}
DB_SCHEMA=${DB_SCHEMA:-$DB_USER}
HTTP_PORT=${HTTP_PORT:-46514}
PGPASSFILE=${PGPASSFILE:-$HOME/.pgpass}
AUTH_USERS_FILE=${AUTH_USERS_FILE:-$APP_DIR/accounts/users.properties}

fail() { printf '%s\n' "$*" >&2; exit 1; }
command -v awk >/dev/null 2>&1 || fail 'awk is not available in PATH.'
command -v stat >/dev/null 2>&1 || fail 'stat is not available in PATH.'
command -v psql >/dev/null 2>&1 || fail 'psql is not available in PATH.'
if [ -z "${JAVA_BIN:-}" ]; then
    if [ -x /usr/local/openjdk17/bin/java ]; then
        JAVA_BIN=/usr/local/openjdk17/bin/java
    else
        JAVA_BIN=java
    fi
fi
command -v "$JAVA_BIN" >/dev/null 2>&1 || fail 'Java 17 or 21 is required; set JAVA_BIN.'
JAVA_MAJOR=$("$JAVA_BIN" -XshowSettings:properties -version 2>&1 | awk '/java.specification.version =/ {print $3; exit}')
case "$JAVA_MAJOR" in 17|21) ;; *) fail 'Use Java 17 or 21 (set JAVA_BIN to its java executable).' ;; esac
for file in is-lab1.war schema.sql payara-micro.jar postgresql.jar; do
    [ -r "$APP_DIR/$file" ] || fail "Missing deployment file: $APP_DIR/$file"
done
case "$DB_SCHEMA" in ''|*[!a-z0-9_]*) fail 'DB_SCHEMA must contain only lowercase letters, digits and underscores.' ;; esac
case "$DB_SCHEMA" in [a-z_]*) ;; *) fail 'DB_SCHEMA must start with a lowercase letter or underscore.' ;; esac
case "$HTTP_PORT" in ''|*[!0-9]*) fail 'HTTP_PORT must be an integer.' ;; esac
[ "$HTTP_PORT" -ge 1024 ] && [ "$HTTP_PORT" -le 65535 ] || fail 'HTTP_PORT must be between 1024 and 65535.'

[ -f "$PGPASSFILE" ] && [ -r "$PGPASSFILE" ] || fail 'Cannot read the configured .pgpass file.'
if PGPASS_META=$(stat -Lf '%u %Lp' "$PGPASSFILE" 2>/dev/null); then
    :
else
    PGPASS_META=$(stat -Lc '%u %a' "$PGPASSFILE" 2>/dev/null) || fail 'Cannot check .pgpass permissions.'
fi
PGPASS_OWNER=${PGPASS_META%% *}
PGPASS_MODE=${PGPASS_META#* }
[ "$PGPASS_OWNER" = "$(id -u)" ] || fail 'The .pgpass file must belong to the current user.'
case "$PGPASS_MODE" in ''|*[!0-7]*) fail 'Cannot check .pgpass permissions.' ;; esac
[ "$((0$PGPASS_MODE & 077))" -eq 0 ] || fail 'The .pgpass file needs permissions 0600: chmod 600 ~/.pgpass.'

DB_PASSWORD=$(LC_ALL=C awk -v host="$DB_HOST" -v port="$DB_PORT" -v db="$DB_NAME" -v user="$DB_USER" '
    function matches(value, wanted) { return value == "*" || value == wanted }
    /^#/ { next }
    {
        sub(/\r$/, "")
        if (length($0) == 0) next
        for (key in field) delete field[key]
        n = 1; field[n] = ""; escaped = 0
        for (i = 1; i <= length($0); i++) {
            c = substr($0, i, 1)
            if (escaped) { field[n] = field[n] c; escaped = 0 }
            else if (c == "\\") escaped = 1
            else if (c == ":") { n++; field[n] = "" }
            else field[n] = field[n] c
        }
        if (escaped || n != 5) next
        if (matches(field[1], host) && matches(field[2], port) &&
            matches(field[3], db) && matches(field[4], user)) {
            printf "%s", field[5]; found = 1; exit
        }
    }
    END {
        if (!found) {
            print "No matching pgpass entry for the configured pg/studs/user connection." > "/dev/stderr"
            exit 1
        }
    }
' < "$PGPASSFILE")
unset PGPASSWORD
export DB_HOST DB_PORT DB_NAME DB_USER DB_SCHEMA DB_PASSWORD PGPASSFILE AUTH_USERS_FILE
DB_URL="jdbc:postgresql://$DB_HOST:$DB_PORT/$DB_NAME?currentSchema=$DB_SCHEMA"
export DB_URL

db_psql() {
    psql -X -w -h "$DB_HOST" -p "$DB_PORT" -d "$DB_NAME" -U "$DB_USER" \
        -v ON_ERROR_STOP=1 "$@"
}
show_schema_access() {
    db_psql -v app_schema="$DB_SCHEMA" <<'SQL' >&2
SELECT nspname AS schema, pg_get_userbyid(nspowner) AS owner,
       has_schema_privilege(oid, 'USAGE') AS usage_ok,
       has_schema_privilege(oid, 'CREATE') AS create_ok
FROM pg_namespace
WHERE nspname = :'app_schema'
   OR (nspname NOT LIKE 'pg_%' AND nspname <> 'information_schema'
       AND has_schema_privilege(oid, 'USAGE'))
ORDER BY nspname;
SQL
}

printf 'Preparing database %s/%s, schema %s for %s\n' "$DB_HOST" "$DB_NAME" "$DB_SCHEMA" "$DB_USER"
SCHEMA_STATUS=$(db_psql -At -v app_schema="$DB_SCHEMA" <<'SQL'
SELECT COALESCE((
    SELECT CASE WHEN has_schema_privilege(oid, 'USAGE')
                     AND has_schema_privilege(oid, 'CREATE')
                THEN 'ready' ELSE 'denied' END
    FROM pg_namespace WHERE nspname = :'app_schema'
), 'missing');
SQL
)
case "$SCHEMA_STATUS" in
    ready) ;;
    missing)
        CAN_CREATE_SCHEMA=$(db_psql -At <<'SQL'
SELECT has_database_privilege(current_database(), 'CREATE');
SQL
)
        if [ "$CAN_CREATE_SCHEMA" != t ]; then
            show_schema_access
            fail "Schema $DB_SCHEMA does not exist and this user cannot create schemas. Set DB_SCHEMA to your existing permitted schema or ask the database administrator."
        fi
        db_psql -v app_schema="$DB_SCHEMA" -v app_user="$DB_USER" <<'SQL'
CREATE SCHEMA IF NOT EXISTS :"app_schema" AUTHORIZATION :"app_user";
SQL
        ;;
    denied)
        show_schema_access
        fail "Schema $DB_SCHEMA needs USAGE and CREATE permissions for this user; migration was not started."
        ;;
    *) fail 'Cannot determine schema access; migration was not started.' ;;
esac
PGOPTIONS="-c search_path=$DB_SCHEMA" db_psql -f "$APP_DIR/schema.sql"

mkdir -p "$(dirname "$AUTH_USERS_FILE")" "$APP_DIR/payara-runtime"
chmod 700 "$(dirname "$AUTH_USERS_FILE")" "$APP_DIR/payara-runtime"
[ ! -f "$AUTH_USERS_FILE" ] || chmod 600 "$AUTH_USERS_FILE"
printf '%s\n' \
    'set configs.config.server-config.network-config.network-listeners.network-listener.http-listener.address=127.0.0.1' \
    > "$APP_DIR/helios-preboot.txt"
printf 'Starting HTTP on 127.0.0.1:%s; context /is-lab1\n' "$HTTP_PORT"
exec "$JAVA_BIN" -Xms128m -Xmx512m -jar "$APP_DIR/payara-micro.jar" \
    --nohazelcast --disablephonehome \
    --port "$HTTP_PORT" --contextroot /is-lab1 \
    --rootdir "$APP_DIR/payara-runtime" \
    --prebootcommandfile "$APP_DIR/helios-preboot.txt" \
    --addlibs "$APP_DIR/postgresql.jar" \
    --deploy "$APP_DIR/is-lab1.war"
