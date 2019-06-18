(ns akvo-authorization.unilog.consumer
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.string :as str]
            [integrant.core :as ig]
            [akvo-authorization.unilog.spec :as unilog-spec]
            [akvo-authorization.unilog.core]
            [jsonista.core :as json]
            [clojure.spec.alpha :as s]
            [hugsql.core :as hugsql]
            [hugsql-adapter-case.adapters :as adapter-case]
            [iapetos.core :as prometheus]))

(hugsql/def-db-fns "sql/offsets.sql" {:adapter (adapter-case/kebab-adapter)})

(defn event-log-spec [config]
  (assert (not (empty? config)) "Config map is empty")
  {:subprotocol "postgresql"
   :subname (format "//%s:%s/%s"
              (config :event-log-server)
              (config :event-log-port)
              (config :db-name))
   :user (config :event-log-user)
   :password (config :event-log-password)})

(def mapper
  (json/object-mapper {:decode-key-fn keyword}))

(defn last-unilog-id [db db-name]
  (or
    (:unilog-id (get-offset db {:db-name db-name}))
    -1))

(defn unilog-dbs [db db-prefix]
  (->>
    (jdbc/query db ["SELECT datname FROM pg_database WHERE datistemplate = false"])
    (map :datname)
    (filter (fn [db-name] (str/starts-with? db-name db-prefix)))
    set))

(defn record-number-of-queued-messages [{:keys [authz-db metrics-collector]} flow-instance]
  (prometheus/set metrics-collector :event/queued-up {:flow-instance flow-instance}
    (:count (first (jdbc/query authz-db ["select count(*) as count from process_later_messages where flow_instance=?" flow-instance])))))

(defn increment-metric-counter [metrics-collector flow-instance metric]
  (fn [x]
    (prometheus/inc metrics-collector metric {:flow-instance flow-instance})
    x))

(defn process-new-events [{:keys [authz-db metrics-collector] :as config} db-name reducible]
  (let [last-unilog-id (atom nil)
        store-offset! (fn []
                        (when @last-unilog-id
                          (upsert-offset! authz-db {:unilog-id (:unilog-id @last-unilog-id)
                                                    :db-name db-name})))
        pipeline (comp
                   (map (fn [x] (update x :payload json/read-value mapper)))
                   (map (increment-metric-counter metrics-collector db-name :event/total))
                   (map (fn [x]
                          (reset! last-unilog-id {:unilog-id (:id x)
                                                  :flow-instance (-> x :payload :orgId)})
                          x))
                   (filter (comp #(s/valid? ::unilog-spec/eventType %) :eventType :payload))
                   (map (increment-metric-counter metrics-collector db-name :event/correct-type))
                   (filter akvo-authorization.unilog.spec/valid?)
                   (map (increment-metric-counter metrics-collector db-name :event/valid))
                   (partition-all 1000))]
    (transduce
      pipeline
      (fn
        ([_]
         (store-offset!))
        ([_ batch]
         (akvo-authorization.unilog.core/process authz-db batch)
         (record-number-of-queued-messages config (:flow-instance @last-unilog-id))
         (store-offset!)))
      []
      reducible)))

(defn process-unilog-queue-for-tenant [{:keys [authz-db unilog-db] :as config} db-name]
  (let [offset (last-unilog-id authz-db db-name)]
    (process-new-events
      config
      db-name
      (jdbc/reducible-query
        (event-log-spec (assoc unilog-db :db-name db-name))
        ["SELECT id, payload::text FROM event_log WHERE id > ? ORDER BY id ASC " offset]
        {:auto-commit? false :fetch-size 1000}))))

(defn process-unilog-queue [{:keys [unilog-db metrics-collector] :as config}]
  (prometheus/with-duration (metrics-collector :event/all-tenants-duration)
    (dorun (pmap
             (fn [db-name]
               (prometheus/with-duration (metrics-collector :event/tenant-duration {:db-name db-name})
                 (process-unilog-queue-for-tenant config db-name)))
             (unilog-dbs (event-log-spec unilog-db) (:prefix unilog-db))))))

(defmethod ig/init-key ::start-cron [_ {:keys [authz-db unilog-db metrics-collector] :as k}]
  (assert authz-db)
  (assert unilog-db)
  (assert metrics-collector)
  (update k
    :authz-db :spec))

(comment
  (process-unilog-queue (get integrant.repl.state/system :akvo-authorization.unilog.consumer/start-cron))

  )