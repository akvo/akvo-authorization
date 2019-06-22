FROM patrickdappollonio/docker-http-server as http-server

FROM alpine:3.6

COPY --from=http-server /http-server /http-server