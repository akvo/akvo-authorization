(ns akvo-authorization.unilog.consumer
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.string :as str]
            [integrant.core :as ig]
            [akvo-authorization.unilog.spec :as unilog-spec]
            [akvo-authorization.unilog.message-processor]
            [jsonista.core :as json]
            [clojure.spec.alpha :as s]
            [com.climate.claypoole :as cp]
            [hugsql.core :as hugsql]
            [taoensso.timbre :as timbre]
            [iapetos.core :as prometheus])
  (:import (java.util.concurrent Executors TimeUnit)))

(hugsql/def-db-fns "sql/consumer.sql")

(defn event-log-spec [config]
  (assert (not (empty? config)) "Config map is empty")
  (merge
    {:subprotocol "postgresql"
     :subname (format "//%s:%s/%s"
                (config :event-log-server)
                (config :event-log-port)
                (config :db-name))
     :ssl true
     :user (config :event-log-user)
     :password (config :event-log-password)}
    (:extra-jdbc-opts config)))

(def mapper
  (json/object-mapper {:decode-key-fn keyword}))

(defn last-unilog-id [db db-name]
  (or
    (:unilog-id (get-offset db {:db-name db-name}))
    -1))

(defn unilog-dbs [db db-prefix]
  (->>
    (get-database-names db)
    (map :datname)
    (filter (fn [db-name] (str/starts-with? db-name db-prefix)))
    set))

(defn record-number-of-queued-messages [{:keys [authz-db metrics-collector]} flow-instance]
  (prometheus/set metrics-collector :event/queued-up {:flow-instance flow-instance}
    (:count (count-pending-for-flow-instance authz-db {:flow-instance flow-instance}))))

(defn increment-metric-counter [metrics-collector db-name metric]
  (fn [x]
    (prometheus/inc metrics-collector metric {:db-name db-name})
    x))

(defn event-delay [event]
  (let [event-time (some->> event :payload :context :timestamp)
        current-time (System/currentTimeMillis)]
    (and
      (pos-int? event-time)
      (> current-time event-time)
      (- current-time event-time))))

(defn metrics-delay-per-event-type [metrics-collector db-name]
  (fn [x]
    (when-let [delay (event-delay x)]
      (prometheus/observe
        metrics-collector
        :event/delay
        {:db-name db-name :event-type (-> x :payload :eventType)}
        delay))
    x))

(defn process-new-events [{:keys [authz-db metrics-collector] :as config} db-name reducible]
  (let [last-unilog-id (atom nil)
        store-offset! (fn []
                        (when @last-unilog-id
                          (let [unilog-id (:unilog-id @last-unilog-id)]
                            (upsert-offset! authz-db {:unilog-id unilog-id
                                                      :db-name db-name})
                            (prometheus/set metrics-collector :event/unilog-offset {:db-name db-name} unilog-id))))
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
                   (map (metrics-delay-per-event-type metrics-collector db-name))
                   (partition-all 1000))]
    (transduce
      pipeline
      (fn
        ([_]
         (store-offset!))
        ([_ batch]
         (akvo-authorization.unilog.message-processor/process authz-db batch)
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

(defmacro log-and-ignore-error [metrics-collector db-name & body]
  `(try
     (let [metrics-labels# {:db-name ~db-name}]
       (prometheus/with-duration (~metrics-collector :event/tenant-duration metrics-labels#)
         (prometheus/with-timestamps {:last-run (~metrics-collector :event/last-run metrics-labels#)
                                      :last-success (~metrics-collector :event/last-success metrics-labels#)
                                      :last-failure (~metrics-collector :event/last-failure metrics-labels#)}
           (prometheus/set ~metrics-collector :event/last-start metrics-labels# (System/currentTimeMillis))
           ~@body)))
     (catch Throwable t#
       (timbre/error t#))))

(defn process-unilog-queue [{:keys [unilog-db thread-pool metrics-collector] :as config}]
  (dorun
    (cp/pmap thread-pool
      (fn [db-name]
        (log-and-ignore-error metrics-collector db-name
          (process-unilog-queue-for-tenant config db-name)))
      (unilog-dbs (event-log-spec unilog-db) (:prefix unilog-db)))))

(defmethod ig/init-key ::start-cron [_ {:keys [authz-db unilog-db metrics-collector parallelism] :as config}]
  (assert authz-db)
  (assert unilog-db)
  (assert metrics-collector)
  (assert parallelism)
  (let [tmp-config (-> config
                     (update :authz-db :spec)
                     (assoc :thread-pool (cp/threadpool parallelism :name "unilog-consumer")))
        cron-thread (Executors/newScheduledThreadPool 1)
        cron-task (.scheduleWithFixedDelay cron-thread
                    (fn []
                      (log-and-ignore-error metrics-collector "global"
                        (process-unilog-queue tmp-config))) 0 2 TimeUnit/SECONDS)]
    (-> tmp-config
      (assoc
        :cron-task cron-task
        :cron-thread cron-thread))))


(defmethod ig/halt-key! ::start-cron [_ {:keys [thread-pool cron-thread cron-task]}]
  (when thread-pool
    (cp/shutdown! thread-pool))
  (when cron-task
    (.cancel cron-task true))
  (when cron-thread
    (.shutdownNow cron-thread)))

(comment
  (process-unilog-queue (get integrant.repl.state/system :akvo-authorization.unilog.consumer/start-cron))
  )