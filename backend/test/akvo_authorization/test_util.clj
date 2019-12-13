(ns akvo-authorization.test-util
  (:require [clojure.test :refer :all]
            [akvo-authorization.unilog.message-processor :as unilog]
            [reifyhealth.specmonstah.core :as sm]
            [reifyhealth.specmonstah.spec-gen :as sg]
            [akvo-authorization.unilog.spec :as unilog-spec]
            hikari-cp.core
            [testit.core :as it :refer [=in=> fact =>]]
            [clojure.string :as str]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen])
  (:import (java.net Socket)))

(defonce local-db {:datasource (hikari-cp.core/make-datasource {:minimum-idle 0
                                                                :maximum-pool-size 30
                                                                :connection-timeout 30000
                                                                :validation-timeout 5000
                                                                :idle-timeout 30000
                                                                :max-lifetime 10000
                                                                :jdbc-url (System/getenv "AUTHZ_DATABASE_URL")})})

(def ^:dynamic *test-run-id* 0)

(defn unique-run-number
  [f]
  (binding [*test-run-id* (+ 10 (unilog/next-id local-db))]
    (f)))

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
  (str (name user-id) "@akvo.org-" *test-run-id*))

(defn auth->spec-specmonstah [secure-object-id authorizations]
  {:user (mapv (fn [{user-id :user-id simple-email :email}]
                 [user-id {:spec-gen {:emailAddress (email simple-email)
                                      :superAdmin false}}])
           authorizations)
   :user-authorization (mapv (fn [{:keys [user-id]}]
                               [1 {:refs {:userId user-id
                                          :securedObjectId secure-object-id}}])
                         authorizations)})

(defn single-number-to-full-authorization [n]
  (let [user-id (keyword (str "user" n))]
    [{:user-id user-id
      :email user-id}]))

(defn perms* [parent [node-name & [maybe-attrs & more :as all]]]
  (let [[node-name type] (str/split (name node-name) #"#")
        node-name (keyword node-name)
        next-parent (if parent node-name ::sm/omit)
        user-auth (when (map? maybe-attrs)
                    (when-let [authorizations (get maybe-attrs :auth)]
                      (auth->spec-specmonstah
                        next-parent
                        (if (number? authorizations)
                          (single-number-to-full-authorization authorizations)
                          authorizations))))
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
  (perms* nil tree))

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
                         :context (gen/generate (s/gen ::unilog-spec/context))
                         :entity (fix-entity-fn entity)}}]
    (when-not (unilog-spec/valid? value)
      (unilog-spec/explain value)
      (throw (ex-info "invalid data generated" {:data value})))
    value))

(defn flow-instance-with-test-id [flow-instance]
  (str (name flow-instance) "-" *test-run-id*))

(defn unilog-messages [auth-tree f]
  (let [entities (shuffle (get-entities (perms auth-tree)))
        flow-instance (flow-instance-with-test-id (first auth-tree))
        unilog-msg (map-indexed (partial ->unilog flow-instance) entities)]
    (f unilog-msg)
    entities))

(defn remove-test-run-id [s]
  (str/replace s #"-[0-9]+$" ""))

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

(deftest test-dsl
  (binding [*test-run-id* 99999]
    (testing "testing that the DSL generates the expected specmonstah, so testing the tests"
      (it/facts
        (perms [:root {:auth 1}])
        =>
        {:user [[:user1 {:spec-gen {:emailAddress "user1@akvo.org-99999" :superAdmin false}}]],
         :user-authorization [[1 {:refs {:userId :user1 :securedObjectId ::sm/omit}}]]}

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
        {:node ^:in-any-order [[:1 {:spec-gen {:name "1" :surveyGroupType "SURVEY"}}]]}))))

(defmacro try-for [msg how-long & body]
  `(let [start-time# (System/currentTimeMillis)]
     (loop []
       (let [[status# return#] (try
                                 (let [result# (do ~@body)]
                                   [(if result# ::ok ::fail) result#])
                                 (catch Throwable e# [::error e#]))
             more-time# (> (* ~how-long 1000)
                          (- (System/currentTimeMillis) start-time#))]
         (cond
           (= status# ::ok) return#
           more-time# (do (Thread/sleep 1000) (recur))
           (= status# ::fail) (throw (ex-info (str "Failed: " ~msg) {:last-result return#}))
           (= status# ::error) (throw (RuntimeException. (str "Failed: " ~msg) return#)))))))

(defn wait-for-server [host port]
  (try-for (str "Nobody listening at " host ":" port) 60
    (with-open [_ (Socket. host (int port))]
      true)))

(defn in-CI-env? []
  (= "true" (System/getenv "CI_ENV")))

(defn check-servers-are-up [f]
  (wait-for-server "authz" 3000)
  (when (in-CI-env?)
    (wait-for-server "authz-consumer" 3000))
  (f))

(comment

  (def so-far (atom 0))
  (while (let [x (dev/test)]
           (and
             (zero? (:fail x))
             (zero? (:error x))
             (> 3000 @so-far)))
    (swap! so-far inc))

  )