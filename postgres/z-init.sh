#!/usr/bin/env bash

set -eu

psql -c "CREATE ROLE authzuser WITH PASSWORD 'authzpasswd' CREATEDB LOGIN;"

psql -c "CREATE DATABASE authzdb WITH OWNER = authzuser TEMPLATE = template0 ENCODING = 'UTF8' LC_COLLATE = 'en_US.UTF-8' LC_CTYPE = 'en_US.UTF-8';"

psql -d authzdb -c "CREATE EXTENSION IF NOT EXISTS ltree WITH SCHEMA public;"
psql -d authzdb -c "CREATE EXTENSION IF NOT EXISTS hstore WITH SCHEMA public;"
