(ns akvo-authorization.unilog.core
  (:require [hugsql.core :as hugsql]
            [clojure.set :refer [rename-keys]]
            [hugsql-adapter-case.adapters :as adapter-case]
            [clojure.java.jdbc :as jdbc]
            [taoensso.nippy :as nippy]
            [clojure.data.json :as json]
            [clojure.string :as str])
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
        "ltree" value
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
  (get-node-by-flow-id db {:flow-instance flow-instance
                           :flow-id flow-id}))

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

(defn is-root-folder-in-flow [flow-id]
  (= flow-root-id flow-id))

(defn add-child [parent-node child-id]
  (str (:full-path parent-node) "." child-id))

(defn upsert-node [db {:keys [flow-instance flow-id flow-parent-id] :as node} parent]
  (if-let [existing-node (find-node-by-flow-id db flow-instance flow-id)]
    (let [new-full-path (->ltree (add-child parent (:id existing-node)))]
      (update-node! db (assoc node :full-path new-full-path :id (:id existing-node)))
      (when (not= (:flow-parent-id existing-node) flow-parent-id)
        (update-all-childs-paths! db {:old-full-path (->ltree (:full-path existing-node))
                                      :new-full-path new-full-path})))
    (let [node-id (next-id db)]
      (insert-node! db (assoc node
                         :id node-id
                         :full-path (->ltree (add-child parent node-id))))))
  :reprocess-queue)

(defn process-node-entity [db {:keys [flow-instance] :as gae-entity}]
  (if-let [parent (find-node-by-flow-id db flow-instance (:flow-parent-id gae-entity))]
    (upsert-node db gae-entity parent)
    (if (is-root-folder-in-flow (:flow-parent-id gae-entity))
      (let [new-instance-root (insert-root-node db flow-instance)]
        (upsert-node db gae-entity new-instance-root))
      :process-later)))

(defn upsert-role [db {:keys [permissions] :as user-role}]
  (let [{:keys [id]} (upsert-role! db user-role)]
    (delete-role-perms-for-role! db {:id id})
    (when (seq permissions)
      (create-role-perms! db {:permissions (map (fn [p] [id p]) permissions)})))
  :reprocess-queue)

(defn upsert-user [db user]
  (let [{:keys [id]} (upsert-user! db user)
        previous-user-flow (get-user-by-flow-id db user)]
    (upsert-user-flow-id! db (assoc user :user-id id))
    (when (and previous-user-flow (not= (:email previous-user-flow) (:email user)))
      (change-auths-owner! db {:previous-user-id (:user-id previous-user-flow)
                               :new-user-id id
                               :flow-instance (:flow-instance user)})))
  :reprocess-queue)

(defn should-process-later? [role-id user-id node-id flow-node-id]
  (or
    (nil? role-id)
    (nil? user-id)
    (and (nil? node-id) (not (is-root-folder-in-flow flow-node-id)))))

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
  (when-let [user-id (delete-user-by-flow-id! db user)]
    (delete-user-auth! db (assoc user-id :flow-instance flow-instance)))
  :delete-related)

(defn delete-user-auth [db user-auth]
  (delete-user-auth-by-flow-id! db user-auth)
  :delete-related)

(defn delete-role [db role]
  (delete-role-by-flow-id! db role)
  :delete-related)

(defn delete-node [db node]
  (when-let [deleted-node (delete-node-by-flow-id! db node)]
    (delete-all-childs! db (update deleted-node :full-path ->ltree)))
  :delete-related)

(defn process-single [db msg]
  (let [type (-> msg :payload :eventType)
        flow-instance (-> msg :payload :orgId)
        gae-entity (-> msg
                     :payload
                     :entity
                     (rename-keys {:id :flow-id})
                     (assoc :flow-instance flow-instance))]
    (case type
      ("surveyGroupCreated" "surveyGroupUpdated")
      (process-node-entity db (-> gae-entity
                                (rename-keys {:public :is-public
                                              :surveyGroupType :type
                                              :parentId :flow-parent-id})
                                (update :flow-parent-id (fn [parent-id] (or parent-id 0)))))

      ("userRoleCreated" "userRoleUpdated")
      (upsert-role db gae-entity)

      ("userCreated" "userUpdated")
      (upsert-user db (-> gae-entity
                        (rename-keys {:emailAddress :email
                                      :permissionList :permission-list
                                      :superAdmin :super-admin})))

      ("userAuthorizationCreated" "userAuthorizationUpdated")
      (upsert-user-auth db (-> gae-entity
                             (rename-keys {:roleId :flow-role-id
                                           :userId :flow-user-id
                                           :securedObjectId :flow-node-id})))

      "userDeleted"
      (delete-user db gae-entity)

      "userAuthorizationDeleted"
      (delete-user-auth db gae-entity)

      "userRoleDeleted"
      (delete-role db gae-entity)

      "surveyGroupDeleted"
      (delete-node db gae-entity))))

(defn kind [event-type]
  ;; Probably we should use the type in the entity, but that would mean generating the proper
  ;; value in the tests, which I cannot be bothered right now.
  ;; Deleted/Created/Updated all happen to have the same number of chars
  (str/replace event-type #".......$" ""))

(defn store-messages! [db unilog-msgs]
  (doall
    (for [msg unilog-msgs]
      (add-message db {:message (nippy/freeze msg)
                       :unilog-id (-> msg :id)
                       :flow-instance (-> msg :payload :orgId)
                       :flow-id (-> msg :payload :entity :id)
                       :entity-type (-> msg :payload :eventType kind)}))))

(defn remove-future-events-of-entity [messages stored-message]
  (remove (fn [m]
            (and
              (= (:flow-instance m) (:flow-instance stored-message))
              (= (:entity-type m) (:entity-type stored-message))
              (= (:flow-id m) (:flow-instance m))))
    messages))

(defn process-message-batch* [db [stored-message & more] reprocess?]
  (if-not stored-message
    reprocess?
    (let [[more-result reprocess-result]
          (jdbc/with-db-transaction [tx db]
            (case (process-single tx (nippy/thaw (:message stored-message)))
              :nothing (do
                         (delete-message tx stored-message)
                         [more reprocess?])
              :process-later [more reprocess?]
              :delete-related (do
                                (delete-messages-related! tx stored-message)
                                [(remove-future-events-of-entity more stored-message) reprocess?])
              :reprocess-queue (do
                                 (delete-messages-related-before! tx stored-message)
                                 [more true])))]
      (recur db more-result reprocess-result))))

(defn assert-all-from-same-flow-instance [unilog-msgs]
  (assert
    (= 1 (count (distinct (map #(-> % :payload :orgId) unilog-msgs))))
    "All unilog messages should be from the same flow instance"))

(defn process [db unilog-msgs]
  (assert-all-from-same-flow-instance unilog-msgs)
  (let [stored-messages (store-messages! db unilog-msgs)]
    (loop [batch-number 0
           batch stored-messages]
      (let [reprocess-from-start? (process-message-batch* db batch false)]
        (when reprocess-from-start?
          (recur
            (inc batch-number)
            (messages-for-flow-instance db (first batch))))))))