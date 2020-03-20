#!/usr/bin/env bash

set -eu

function log {
   echo "$(date +"%T") - INFO - $*"
}

export PROJECT_NAME=akvo-lumen

if [ -z "${TRAVIS_COMMIT:-}" ]; then
    export TRAVIS_COMMIT=local
fi

if [[ "${TRAVIS_TAG:-}" =~ promote-.* ]]; then
    log "Skipping build as it is a prod promotion"
    exit 0
fi

log Building backend dev container
docker build --rm=false -t akvo-authz-dev:develop backend -f backend/dev/Dockerfile-dev
log Building uberjar
docker run -v $HOME/.m2:/root/.m2 -v `pwd`/backend:/app akvo-authz-dev:develop lein uberjar

log Building production container
docker build --rm=false -t eu.gcr.io/${PROJECT_NAME}/akvo-authz:$TRAVIS_COMMIT backend -f backend/prod/Dockerfile

log Starting docker compose env
docker-compose -p akvo-authz-ci -f docker-compose.yml -f docker-compose.ci.yml up -d --build
log Running integration tests
docker-compose -p akvo-authz-ci -f docker-compose.yml -f docker-compose.ci.yml run --no-deps tests dev/start-dev-env.sh integration-test
log Done

log Check nginx configuration

log Build Auth0 nginx
(
    cd nginx-auth0
    docker build -t "akvo/akvo-auth:latest" -t "eu.gcr.io/${PROJECT_NAME}/akvo-authz-nginx-auth0:$TRAVIS_COMMIT" .
    docker run \
           --rm \
           --entrypoint /usr/local/openresty/bin/openresty \
           "akvo/akvo-auth:latest" -t -c /usr/local/openresty/nginx/conf/nginx.conf
)
