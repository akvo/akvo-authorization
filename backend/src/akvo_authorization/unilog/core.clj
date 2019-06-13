(ns akvo-authorization.unilog.core
  (:require [hugsql.core :as hugsql]
            [clojure.set :refer [rename-keys]]
            [clojure.java.jdbc :as jdbc]
            [clojure.data.json :as json])
  (:import (org.postgresql.util PGobject)))

(hugsql/def-db-fns "sql/nodes.sql")

(defrecord ltree [v])

(extend-protocol jdbc/IResultSetReadColumn
  PGobject
  (result-set-read-column [pgobj _metadata _index]
    (let [type (.getType pgobj)
          value (.getValue pgobj)]
      (case type
        "json" (json/read-str value)
        "jsonb" (json/read-str value)
        "citext" (str value)
        "ltree" (->ltree value)
        value))))

(extend-protocol jdbc/ISQLValue
  ltree
  (sql-value [v]
    (doto (PGobject.)
      (.setType "ltree")
      (.setValue (:v v)))))

(defn next-id [db]
  (:nextval (next-node-id-seq db)))

(def flow-root-id 0)

(defn find-node-by-flow-id [db flow-instance flow-id]
  (some->
    (get-node-by-flow-id db {:flow-instance flow-instance
                             :flow-id flow-id})
    (rename-keys {:flow_id :flow-id
                  :flow_instance :flow-instance
                  :is_public :is-public
                  :flow_parent_id :flow-parent-id
                  :full_path :full-path})
    (update :full-path :v)))

(defn insert-root-node [db flow-instance]
  (let [root-id (next-id db)]
    (insert-root-node! db {:id root-id
                           :is-public false
                           :name flow-instance
                           :flow-id flow-root-id
                           :flow-instance flow-instance
                           :flow-parent-id nil
                           :full-path (->ltree (str root-id))
                           :type "ROOT"})
    (find-node-by-flow-id db flow-instance flow-root-id)))

(defn is-root-folder-in-flow [n]
  (= flow-root-id (:flow-parent-id n)))

(defn add-child [parent-node child-id]
  (str (:full-path parent-node) "." child-id))

(defn insert-node [db node parent]
  (let [node-id (next-id db)]
    (insert-node! db (assoc node
                       :id node-id
                       :full-path (->ltree (add-child parent node-id))))))

(defn process [db unilog-msgs]
  (doseq [msg unilog-msgs]
    (if (= "surveyGroupCreated" (-> msg :payload :eventType))
      (do
        (let [e (-> msg :payload :entity)
              flow-instance (-> msg :payload :orgId)
              e (-> e
                  (select-keys [:id :name :public :surveyGroupType :parentId])
                  (rename-keys {:public :is-public
                                :surveyGroupType :type
                                :id :flow-id
                                :parentId :flow-parent-id})
                  (assoc :flow-instance flow-instance))]
          (if-let [parent (find-node-by-flow-id db flow-instance (:flow-parent-id e))]
            (insert-node db e parent)
            (if (is-root-folder-in-flow e)
              (let [new-instance-root (insert-root-node db flow-instance)]
                (insert-node db e new-instance-root))))))
      (println "ignoring "))))


(comment
  (clojure.java.jdbc/query (dev/local-db) ["select * from nodes where type!='ROOT'"] {:transaction? false})

  (do
    (ragtime.core/rollback (ragtime.jdbc/sql-database (dev/local-db)) (first (ragtime.jdbc/load-resources "migrations")))
    (ragtime.core/migrate-all (ragtime.jdbc/sql-database (dev/local-db)) {} (ragtime.jdbc/load-resources "migrations"))))

