(ns akvo-authorization.authz-logic-test
  (:require [clojure.test :refer :all]
            [integrant.core :as ig]
            [ring.mock.request :as mock]
            [akvo-authorization.unilog.core :as unilog]
            [akvo-authorization.authz :as authz]
            [reifyhealth.specmonstah.core :as sm]
            [reifyhealth.specmonstah.spec-gen :as sg]
            [akvo-authorization.unilog.spec :as unilog-spec]
            [testit.core :as it :refer [=in=> fact =>]]
            [clojure.string :as str]
            [clojure.java.jdbc :as jdbc]))

(def schema
  {:user {:prefix :u
          :spec ::unilog-spec/user}
   :user-authorization {:prefix :ua
                        :spec ::unilog-spec/userAuthorization
                        :relations {:userId [:user :id]
                                    :roleId [:role :id]
                                    :securedObjectId [:node :id]}}
   :node {:prefix :f
          :relations {:parentId [:node :id]}
          :spec ::unilog-spec/surveyGroup}
   :role {:prefix :ur
          :spec ::unilog-spec/userRole}})

(defn get-entities [specmostah-definition]
  (let [tmp (atom [])]
    (-> (sg/ent-db-spec-gen {:schema schema}
          specmostah-definition)
      (sm/visit-ents :insert (fn insert
                               [db ent-name visit-key]
                               (let [{:keys [spec-gen ent-type] :as attrs} (sm/ent-attrs db ent-name)]
                                 (when-not (visit-key attrs)
                                   (swap! tmp conj [ent-type spec-gen])
                                   true)))))
    @tmp))

(defn email
  [user-id]
  {:pre [(keyword? user-id)]}
  (str (name user-id) "@akvo.org"))

(defn perms* [parent [node-name & [maybe-attrs & more :as all]]]
  (let [[node-name type] (str/split (name node-name) #"#")
        node-name (keyword node-name)
        next-parent (if parent node-name ::sm/omit)
        user-auth (if (map? maybe-attrs)
                    (let [user-id (keyword (str "user" (get maybe-attrs :auth)))]
                      {:user [[user-id {:spec-gen {:emailAddress (email user-id)}}]]
                       :user-authorization [[1 {:refs {:userId user-id
                                                       :securedObjectId next-parent}}]]}))
        children (map
                   (partial perms* next-parent)
                   (if (map? maybe-attrs) more all))]
    (apply merge-with (fn [left right] (distinct (concat left right)))
      (when parent
        {:node [[node-name {:spec-gen {:name (name node-name)
                                       :surveyGroupType (if type (str/upper-case type) "FOLDER")}
                            :refs {:parentId parent}}]]})
      user-auth
      children)))

(defn perms [tree]
  (assoc (perms* nil tree)
    :role [[1 {:spec-gen {:permissions #{"PROJECT_FOLDER_READ"}}}]]))

#_(defn db-transaction-fixture [f]
    (jdbc/with-db-transaction [conn test-db-uri]
      (jdbc/db-set-rollback-only! conn)
      (binding [store (postgres/build conn)]
        (f))))

(defn ->unilog [flow-instance idx [type entity]]
  (let [fill-with-0 (fn [k]
                      (fn [e]
                        (update e k (fn [v] (or v 0)))))
        [eventType fix-entity-fn] (case type
                                    :user ["userCreated" identity]
                                    :role ["userRoleCreated" identity]
                                    :user-authorization ["userAuthorizationCreated" (fill-with-0 :securedObjectId)]
                                    :node ["surveyGroupCreated" (fill-with-0 :parentId)])
        value {:id idx
               :payload {:eventType eventType
                         :orgId (name flow-instance)
                         :entity (fix-entity-fn entity)}}]
    (when-not (unilog-spec/valid? value)
      (unilog-spec/explain value)
      (throw (ex-info "invalid data generated" {:data value})))
    value))

(defn with-authz [auth-tree & body]
  (let [entities (shuffle (get-entities (perms auth-tree)))
        flow-instance (first auth-tree)
        unilog-msg (map-indexed (partial ->unilog flow-instance) entities)]
    (unilog/process (dev/local-db) unilog-msg)
    entities))

(defn can-see [user]
  (let [surveys (authz/find-all-surveys (dev/local-db) (email user))
        instance-name-pairs (map (juxt
                                   (comp keyword :flow-instance)
                                   (comp keyword :name)) surveys)]
    (set instance-name-pairs)))

(defn find-user [entities user]
  (->> entities
    (filter (fn [[type e]]
              (and
                (= :user type)
                (= (email user) (:emailAddress e)))))
    first
    second))

(defn find-user-auths [entities user]
  (when-let [user (find-user entities user)]
    (->> entities
      (filter (fn [[type e]]
                (and
                  (= :user-authorization type)
                  (= (:id user) (:userId e)))))
      (map second))))

(defn find-node [entities node-name]
  (->> entities
    (filter (fn [[type e]]
              (and
                (= :node type)
                (= (name node-name) (:name e)))))
    first
    second))

(defn find-role [entities]
  (->> entities
    (filter (fn [[type _]]
              (= :role type)))
    first
    second))

(defn delete [type flow-instance entity]
  (unilog/process (dev/local-db)
    [{:id (rand-int 100000)
      :payload {:eventType (case type
                             :user "userDeleted"
                             :role "userRoleDeleted"
                             :user-authorization "userAuthorizationDeleted"
                             :node "surveyGroupDeleted")
                :orgId (name flow-instance)
                :entity {:id (:id entity)}}}]))

(deftest authz
  (testing "basic "
    (with-authz [:uat-instance {:auth 1}
                 [:folder-1
                  [:folder-1.1 {:auth 4}
                   [:folder-1.1.1
                    [:survey1#survey {:auth 2}]]
                   [:folder-1.1.2 {:auth 3}
                    [:survey2#survey]]]]])
    (is (= #{[:uat-instance :survey1] [:uat-instance :survey2]} (can-see :user1)))
    (is (= #{[:uat-instance :survey1] [:uat-instance :survey2]} (can-see :user4)))
    (is (= #{[:uat-instance :survey1]} (can-see :user2)))
    (is (= #{[:uat-instance :survey2]} (can-see :user3)))
    (is (= #{} (can-see :user5))))
  (testing "same user in multiple instances"
    (with-authz [:uat-instance {:auth 1}
                 [:folder-1
                  [:survey1#survey]]])
    (with-authz [:prod-instance
                 [:folder-1
                  [:folder-1.1
                   [:folder-1.1.2 {:auth 1}
                    [:survey1#survey]]]]])
    (is (= #{[:uat-instance :survey1] [:prod-instance :survey1]} (can-see :user1)))))

(deftest deleting
  (testing "delete user"
    (let [entities (with-authz [:uat-instance {:auth 1}
                                [:survey1#survey]])
          user (find-user entities :user1)]
      (delete :user :uat-instance user)
      (is (= #{} (can-see :user1)))))
  (testing "Deleting a user in a flow instance does not affect his perms in other instances"
    (let [entities (with-authz [:uat-instance {:auth 1}
                                [:survey1#survey]])
          _ (with-authz [:prod-instance
                         [:survey1#survey {:auth 1}]])]
      (delete :user :uat-instance (find-user entities :user1))
      (is (= #{[:prod-instance :survey1]} (can-see :user1)))))
  (testing "delete user auth"
      (let [entities (with-authz [:uat-instance {:auth 1}
                                  [:survey1#survey]])
            user-auths (find-user-auths entities :user1)]
        (doseq [user-auth user-auths]
          (delete :user-authorization :uat-instance user-auth))
        (is (= #{} (can-see :user1)))))
  (testing "delete role"
    (let [entities (with-authz [:uat-instance {:auth 1}
                                [:survey1#survey]])
          role (find-role entities)]
      (delete :role :uat-instance role)
      (is (= #{} (can-see :user1)))))
  (testing "delete node"
    (let [entities (with-authz [:uat-instance {:auth 1}
                                [:folder-1
                                 [:survey1#survey]
                                 [:folder-1.1
                                  [:folder-1.1.1
                                   [:survey2#survey]]]]])
          node (find-node entities :folder-1.1)]
      (delete :node :uat-instance node)
      (is (= #{[:uat-instance :survey1]} (can-see :user1)))))
  (testing "delete node - permissions in survey"
    (let [entities (with-authz [:uat-instance
                                [:folder-1
                                 [:survey1#survey {:auth 1}]
                                 [:folder-1.1
                                  [:folder-1.1.1
                                   [:survey2#survey {:auth 1}]]]]])
          node (find-node entities :folder-1.1)]
      (delete :node :uat-instance node)
      (is (= #{[:uat-instance :survey1]} (can-see :user1))))))

(deftest should-process-later-user-auth-msg
  (let [any 1
        flow-root 0]
    (are [expected role-id user-id node-id flow-node-id] (= expected (unilog/should-process-later? role-id user-id node-id flow-node-id))
      true nil nil nil flow-root
      true nil any any flow-root
      true any nil any flow-root
      true any any nil any
      false any any nil flow-root
      false any any any any
      )))

(deftest test-dsl
  (testing "testing that the DSL generates the expected specmonstah, so testing the tests"
    (it/facts
      (perms [:root {:auth 1}])
      =>
      {:user [[:user1 {:spec-gen {:emailAddress "user1@akvo.org"}}]],
       :user-authorization [[1 {:refs {:userId :user1 :securedObjectId ::sm/omit}}]],
       :role [[1 {:spec-gen {:permissions #{"PROJECT_FOLDER_READ"}}}]]}

      (perms [:root {:auth 1}
              [:1
               [:1.1]]])
      =in=>
      {:node ^:in-any-order [[:1 {:refs {:parentId ::sm/omit}}]
                             [:1.1 {:refs {:parentId :1}}]]}

      (perms [:root
              [:1
               [:1.1 {:auth 1}]]])
      =in=>
      {:user-authorization [[1 {:refs {:securedObjectId :1.1}}]]}

      (perms [:root
              [:1]
              [:2 {:auth 1}]])
      =in=>
      {:node ^:in-any-order [[:1 {:refs {:parentId ::sm/omit}}]
                             [:2 {:refs {:parentId ::sm/omit}}]]
       :user-authorization [[1 {:refs {:securedObjectId :2}}]]}

      (perms [:root
              [:1 {:auth 1}]
              [:2 {:auth 2}]])
      =in=>
      {:user ^:in-any-order [[:user1 it/any] [:user2 it/any]]
       :user-authorization ^:in-any-order [[1 {:refs {:userId :user1 :securedObjectId :1}}]
                                           [1 {:refs {:userId :user2 :securedObjectId :2}}]]}

      (perms [:root
              [:1#survey {:auth 1}]])
      =in=>
      {:node ^:in-any-order [[:1 {:spec-gen {:name "1" :surveyGroupType "SURVEY"}}]]})))