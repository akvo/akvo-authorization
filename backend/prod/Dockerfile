FROM openjdk:11.0.3-jre-slim

RUN set -ex; apt-get update && \
    mkdir -p /usr/share/man/man7/ && \
    apt-get -qq install -y --no-install-recommends --no-install-suggests \
    postgresql-client && \
    rm -rf /var/cache/apt/* /usr/share/man/*

WORKDIR /app
COPY prod/start-prod-env.sh /app/start-prod-env.sh
COPY target/akvo-authz.jar /app/akvo-authz.jar

CMD ["/app/start-prod-env.sh"]
