#!/usr/bin/env bash

set -e

if [[ "${WAIT_FOR_DEPS:=false}" = "true" ]]; then
  /app/dev/wait-for-dependencies.sh
else
  mkdir /root/.postgresql
  cp /etc/ssl/certs/ca-certificates.crt /root/.postgresql/root.crt
fi

_term() {
  echo "Caught SIGTERM signal!"
  kill -TERM "$child" 2>/dev/null
}

trap _term SIGTERM

java -jar akvo-authz.jar &

child=$!
wait "$child"