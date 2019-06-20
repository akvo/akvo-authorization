#!/usr/bin/env bash

set -eu

function log {
   echo "$(date +"%T") - INFO - $*"
}

export PROJECT_NAME=akvo-lumen

if [[ "${TRAVIS_BRANCH}" != "master" ]] && [[ ! "${TRAVIS_TAG:-}" =~ promote-.* ]]; then
    exit 0
fi

if [[ "${TRAVIS_PULL_REQUEST}" != "false" ]]; then
    exit 0
fi

log Making sure gcloud and kubectl are installed and up to date
gcloud components install kubectl
gcloud components update
gcloud version
which gcloud kubectl

log Authentication with gcloud and kubectl
gcloud auth activate-service-account --key-file ci/gcloud-service-account-akvo-lumen-96c2ba29ad63.json
gcloud config set project akvo-lumen
gcloud config set container/cluster europe-west1-d
gcloud config set compute/zone europe-west1-d
gcloud config set container/use_client_certificate True

ENVIRONMENT=test
if [[ "${TRAVIS_TAG:-}" =~ promote-.* ]]; then
    log Environment is production
    gcloud container clusters get-credentials production
    ENVIRONMENT=production
    POD_CPU_REQUESTS="400m"
    POD_CPU_LIMITS="2000m"
    POD_MEM_REQUESTS="512Mi"
    POD_MEM_LIMITS="512Mi"
else
    log Environment is test
    gcloud container clusters get-credentials test
    POD_CPU_REQUESTS="200m"
    POD_CPU_LIMITS="400m"
    POD_MEM_REQUESTS="300Mi"
    POD_MEM_LIMITS="300Mi"

    log Pushing images
    gcloud auth configure-docker
    docker push eu.gcr.io/${PROJECT_NAME}/akvo-authz
fi

log Deploying

sed -e "s/\$TRAVIS_COMMIT/$TRAVIS_COMMIT/" \
  -e "s/\${ENVIRONMENT}/${ENVIRONMENT}/" \
  -e "s/\${POD_CPU_REQUESTS}/${POD_CPU_REQUESTS}/" \
  -e "s/\${POD_MEM_REQUESTS}/${POD_MEM_REQUESTS}/" \
  -e "s/\${POD_CPU_LIMITS}/${POD_CPU_LIMITS}/" \
  -e "s/\${POD_MEM_LIMITS}/${POD_MEM_LIMITS}/" \
  ci/k8s/akvo-unilog.yaml > final-akvo-authz.yaml

kubectl apply -f final-akvo-authz.yaml

ci/k8s/wait-for-k8s-deployment-to-be-ready.sh

log Done