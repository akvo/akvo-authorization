#!/usr/bin/env bash

set -eu

MAX_ATTEMPTS=120
ATTEMPTS=0

echo "Waiting for PostgreSQL ..."

while [[ ! -f "${PGSSLROOTCERT}" && "${ATTEMPTS}" -lt "${MAX_ATTEMPTS}" ]]; do
    echo "Waiting for certificate to be generated..."
    sleep 1
    let ATTEMPTS+=1
done

if [[ ! -d "/root/.postgresql" ]]; then
     mkdir /root/.postgresql
     cp "${PGSSLROOTCERT}" /root/.postgresql/root.crt
fi

ATTEMPTS=0
PG=""
SQL="SELECT 1"

while [[ -z "${PG}" && "${ATTEMPTS}" -lt "${MAX_ATTEMPTS}" ]]; do
    export PGPASSWORD=authzpasswd
    PG=$( (psql --username=authzuser --host=postgres --dbname=authzdb --command "${SQL}" 2>&1 | grep "(1 row)") || echo "")
    let ATTEMPTS+=1
    sleep 1
done

if [[ -z "${PG}" ]]; then
    echo "PostgreSQL is not available"
    exit 1
fi

echo "PostgreSQL is ready!"
