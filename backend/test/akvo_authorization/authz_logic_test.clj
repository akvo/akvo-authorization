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
  (let [entities (get-entities (perms auth-tree))
        flow-instance (first auth-tree)
        unilog-msg (map-indexed (partial ->unilog flow-instance) entities)]
    (unilog/process (dev/local-db) unilog-msg)))

(defn can-see [user]
  (let [surveys (authz/find-all-surveys (dev/local-db) (email user))
        instance-name-pairs (map (juxt
                                   (comp keyword :flow-instance)
                                   (comp keyword :name)) surveys)]
    (set instance-name-pairs)))

(deftest authz
  (testing "basic "
    (with-authz [:uat-instance {:auth 1}
                 [:folder-1
                  [:folder-1.1 {:auth 4}
                   [:folder-1.1.1
                    [:survey1#survey {:auth 2}]]
                   [:folder-1.1.2 {:auth 3}
                    [:survey2#survey]]]]]
      (is (= #{[:uat-instance :survey1] [:uat-instance :survey2]} (can-see :user1)))
      (is (= #{[:uat-instance :survey1] [:uat-instance :survey2]} (can-see :user4)))
      (is (= #{[:uat-instance :survey1]} (can-see :user2)))
      (is (= #{[:uat-instance :survey2]} (can-see :user3)))
      (is (= #{} (can-see :user5))))))

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