#!/usr/bin/env bash

set -eu

## Unilog event database
psql -c "CREATE ROLE uniloguser WITH PASSWORD 'uniloguserpassword' CREATEDB LOGIN;"
psql -c "CREATE DATABASE u_unilog_events WITH OWNER = uniloguser TEMPLATE = template0 ENCODING = 'UTF8' LC_COLLATE = 'en_US.UTF-8' LC_CTYPE = 'en_US.UTF-8';"

psql -d u_unilog_events -U uniloguser -c "CREATE TABLE IF NOT EXISTS event_log (id BIGSERIAL PRIMARY KEY, payload JSONB UNIQUE);"
psql -d u_unilog_events -U uniloguser -c "CREATE INDEX timestamp_idx ON event_log(cast(payload->'context'->>'timestamp' AS numeric));"

## Authz service database
psql -c "CREATE ROLE authzuser WITH PASSWORD 'authzpasswd' CREATEDB LOGIN;"

psql -c "CREATE DATABASE authzdb WITH OWNER = authzuser TEMPLATE = template0 ENCODING = 'UTF8' LC_COLLATE = 'en_US.UTF-8' LC_CTYPE = 'en_US.UTF-8';"