# akvo-authorization

Service that does all the authorization related logic for Lumen and Flow.

## Architecture

Authz consumes all the authorization related data from Unilog and stores it in a Postgres DB.

We store in the same DB information from all tenants, so that we can do cross-instance queries in an efficient way.

We are using [Postgres ltree](https://www.postgresql.org/docs/9.1/ltree.html) to store and efficiently query the Flow folder structure.

### Deployment

![setup](http://www.plantuml.com/plantuml/proxy?cache=no&src=https://raw.githubusercontent.com/akvo/akvo-authorization/master/doc/architecture.puml)

The application is divided in two pods:

1. API POD:
    * Contains the client facing APIs. 
    * It is HA to avoid any downtime.
    * The nginx proxies take care of the JWT validation, like in the Flow API.
2. Unilog Consumer POD:
    * Talks with the Unilog
    * Just one instance, during deployments it is ok if it has some downtime as it is not noticeable by clients

There are two reasons why we split the consumer from the api:

1. It is not easy to have multiple Unilog consumers. They will require some additional coordination mechanism.
2. Consumer is CPU heavy, so we want to isolate it from the client facing API to not affect the latency.

### Out of order message in Unilog

Given the hierarchical structure of the Flow folders, and specially of the way we populate Unilog, we expects Unilog messages to be out of order. For example, we expect that some folders will appear in the Unilog before their parent shows up. Lets call these messages incomplete.

We deal with this out of order by storing all the messages in a temporal table (in the authz db) and after processing a batch of messages we try to reprocess all incomplete messages.

If any of the incomplete messages becomes complete, then we reprocess all the incomplete messages again until there are 0 completed messages.

The only out of order not handled is the scenario were a delete appears before a create/update, as we dont think this is possible. To handle this case, we would need to keep a tombstone record with all objects deleted.

## Developing

    docker-compose up --build

HTTP server will be running on port 3000.

REPL will be running on port 47480.

On development, both the API and the Unilog consumer are running in the same process. This is so that the development process is easier as there is just one REPL to connect to.

To mitigate any possible issues due to this, the integration tests actually run the API and the Unilog consumer in different processes, as per production.

### Testing

Testing is fastest through the REPL, as you avoid environment startup
time.

```clojure
dev=> (test)
...
```

But you can also run tests through Leiningen.

```sh
lein test
```