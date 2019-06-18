(ns akvo-authorization.unilog.consumer
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.string :as str]
            [akvo-authorization.unilog.spec :as unilog-spec]
            [jsonista.core :as json]
            [clojure.spec.alpha :as s]
            [hugsql.core :as hugsql]
            [hugsql-adapter-case.adapters :as adapter-case]))

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

(defn last-unilog-id [db flow-instance]
  (or
    (:unilog-id (get-offset db {:flow-instance flow-instance}))
    -1))

(defn unilog-dbs [db db-prefix]
  (->>
    (jdbc/query db ["SELECT datname FROM pg_database WHERE datistemplate = false"])
    (map :datname)
    (filter (fn [db-name] (str/starts-with? db-name db-prefix)))
    set))

(defn process-all [flow-instance authz-db reducible]
  (let [last-unilog-id (atom nil)
        s (comp
            (map (fn [x] (update x :payload json/read-value mapper)))
            (map (fn [x]
                   (reset! last-unilog-id (:id x))
                   x))
            (filter (comp #(s/valid? ::unilog-spec/eventType %) :eventType :payload))
            (filter akvo-authorization.unilog.spec/valid?)
            (partition-all 1000))]
    (transduce
      s
      (fn
        ([_]
         (when @last-unilog-id
           (upsert-offset! authz-db {:unilog-id @last-unilog-id
                                     :flow-instance flow-instance}))
         nil)
        ([_ batch]
         (akvo-authorization.unilog.core/process authz-db batch)
         (println (jdbc/query authz-db ["select count(*) from process_later_messages where flow_instance=?" flow-instance]))
         (upsert-offset! authz-db {:unilog-id @last-unilog-id
                                   :flow-instance flow-instance})
         nil))
      []
      reducible)))

(defn process-unilog-queue [authz-db {:keys [prefix] :as unilog-db-config}]
  (doall (pmap
           (fn [flow-instance]
             (let [offset (last-unilog-id authz-db flow-instance)]
               (process-all
                 flow-instance
                 authz-db
                 (jdbc/reducible-query
                   (event-log-spec (assoc unilog-db-config :db-name flow-instance))
                   ["SELECT id, payload::text FROM event_log WHERE id > ? ORDER BY id ASC " offset]
                   {:auto-commit? false :fetch-size 1000}))))
           (unilog-dbs (event-log-spec unilog-db-config) prefix))))
