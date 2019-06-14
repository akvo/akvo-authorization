(ns akvo-authorization.unilog.core
  (:require [hugsql.core :as hugsql]
            [clojure.set :refer [rename-keys]]
            [hugsql-adapter-case.adapters :as adapter-case]
            [clojure.java.jdbc :as jdbc]
            [taoensso.nippy :as nippy]
            [clojure.data.json :as json])
  (:import (org.postgresql.util PGobject)))

(hugsql/def-db-fns "sql/nodes.sql" {:adapter (adapter-case/kebab-adapter)})
(hugsql/def-db-fns "sql/user.sql" {:adapter (adapter-case/kebab-adapter)})
(hugsql/def-db-fns "sql/queue.sql" {:adapter (adapter-case/kebab-adapter)})

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
                       :full-path (->ltree (add-child parent node-id)))))
  :reprocess-queue)

(defn upsert-role [db {:keys [permissions] :as user-role}]
  (jdbc/with-db-transaction [tx db]
    (let [{:keys [id]} (upsert-role! tx user-role)]
      (delete-role-perms-for-role! tx {:id id})
      (create-role-perms! tx {:permissions (map (fn [p] [id p]) permissions)})))
  :reprocess-queue)

(defn upsert-user [db user]
  (jdbc/with-db-transaction [tx db]
    (let [{:keys [id]} (upsert-user! tx user)]
      (upsert-user-flow-id! tx (assoc user :user-id id))))
  :reprocess-queue)

(defn should-process-later? [role-id user-id node-id flow-node-id]
  (or
    (nil? role-id)
    (nil? user-id)
    (and (nil? node-id) (not (zero? flow-node-id)))))

(defn upsert-user-auth [db {:keys [flow-instance flow-node-id flow-role-id flow-user-id flow-id]}]
  (let [{user-id :user-id} (get-user-by-flow-id db {:flow-instance flow-instance :flow-id flow-user-id})
        {role-id :id} (get-role-by-flow-id db {:flow-instance flow-instance :flow-id flow-role-id})
        {node-id :id} (get-node-by-flow-id db {:flow-instance flow-instance :flow-id flow-node-id})]
    (if (should-process-later? role-id user-id node-id flow-node-id)
      :process-later
      (do
        (upsert-user-auth! db {:flow-id flow-id
                               :flow-instance flow-instance
                               :user-id user-id
                               :role-id role-id
                               :node-id (or node-id (:id (insert-root-node db flow-instance)))})
        :nothing))))

(defn delete-user [db {:keys [flow-instance] :as user}]
  (let [user-id (delete-user-by-flow-id! db user)]
    (if user-id
      (delete-user-auth! db (assoc user-id :flow-instance flow-instance))))
  :nothing)

(defn delete-user-auth [db user-auth]
  (delete-user-auth-by-flow-id! db user-auth)
  :nothing)

(defn delete-role [db role]
  (delete-role-by-flow-id! db role)
  :nothing)

(defn process-single [db msg]
  (let [type (-> msg :payload :eventType)
        e (-> msg :payload :entity)
        flow-instance (-> msg :payload :orgId)]
    (case type
      ("surveyGroupCreated" "surveyGroupUpdated")
      (let [e (-> e
                (select-keys [:id :name :public :surveyGroupType :parentId])
                (rename-keys {:public :is-public
                              :surveyGroupType :type
                              :id :flow-id
                              :parentId :flow-parent-id})
                (assoc :flow-instance flow-instance)
                (update :flow-parent-id (fn [parent-id] (or parent-id 0))))]
        (if-let [parent (find-node-by-flow-id db flow-instance (:flow-parent-id e))]
          (insert-node db e parent)
          (if (is-root-folder-in-flow e)
            (let [new-instance-root (insert-root-node db flow-instance)]
              (insert-node db e new-instance-root))
            :process-later)))

      ("userRoleCreated" "userRoleUpdated")
      (upsert-role db (-> e
                        (select-keys [:id :name :permissions])
                        (rename-keys {:id :flow-id})
                        (assoc :flow-instance flow-instance)))

      ("userCreated" "userUpdated")
      (upsert-user db (-> e
                        (rename-keys {:id :flow-id
                                      :emailAddress :email
                                      :permissionList :permission-list
                                      :superAdmin :super-admin})
                        (assoc :flow-instance flow-instance)))

      ("userAuthorizationCreated" "userAuthorizationUpdated")
      (upsert-user-auth db (-> e
                             (rename-keys {:id :flow-id
                                           :roleId :flow-role-id
                                           :userId :flow-user-id
                                           :securedObjectId :flow-node-id})
                             (assoc :flow-instance flow-instance)))

      "userDeleted"
      (delete-user db (-> e
                        (rename-keys {:id :flow-id})
                        (assoc :flow-instance flow-instance)))

      "userAuthorizationDeleted"
      (delete-user-auth db (-> e
                             (rename-keys {:id :flow-id})
                             (assoc :flow-instance flow-instance)))

      "userRoleDeleted"
      (delete-role db (-> e
                        (rename-keys {:id :flow-id})
                        (assoc :flow-instance flow-instance)))

      :nothing
      )))

(defn process [db unilog-msgs]
  ;; TODO: assuming all messages are for the same flow-instance!!!!! Assert this.
  (let [stored-messages (for [msg unilog-msgs]
                          (let [id (-> msg :id)
                                flow-instance (-> msg :payload :orgId)]
                            (add-message db {:message (nippy/freeze msg)
                                             :unilog-id id
                                             :flow-instance flow-instance})))]
    (loop [[stored-message & more] stored-messages]
      (when stored-message
        (let [result (process-single db (nippy/thaw (:message stored-message)))]
          (case result
            :nothing (do
                       (delete-message db stored-message)
                       (recur more))
            :process-later (recur more)
            :reprocess-queue (do
                               (delete-message db stored-message)
                               (recur (messages-for-flow-instance db stored-message)))))))))