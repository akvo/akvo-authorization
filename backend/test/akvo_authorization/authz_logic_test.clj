(ns akvo-authorization.authz-logic-test
  (:require [clojure.test :refer :all]
            [integrant.core :as ig]
            [ring.mock.request :as mock]
            [reifyhealth.specmonstah.core :as sm]
            [reifyhealth.specmonstah.spec-gen :as sg]
            [akvo-authorization.unilog.spec :as unilog-spec]
            [testit.core :as it :refer [=in=> fact =>]]
            [clojure.string :as str]))

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

(let [database (atom [])]
  (-> (sg/ent-db-spec-gen {:schema schema}
        {:node [[:root
                 {:spec-gen {:name "root", :survey-group-type "FOLDER", :id 0},
                  :refs {:parentId :reifyhealth.specmonstah.core/omit}}]],
         :user [[:user1 {:spec-gen {:emailAddress "user1@akvo.org"}}]],
         :user-authorization [[1 {:refs {:userId :user1, :securedObjectId :root}}]],
         :role [[1 {:spec-gen {:permissions #{"PROJECT_FOLDER_READ"}}}]]})
    (sm/visit-ents :insert (fn insert
                             [db ent-name visit-key]
                             (let [{:keys [spec-gen ent-type] :as attrs} (sm/ent-attrs db ent-name)]
                               (when-not (visit-key attrs)
                                 (swap! database conj [ent-type spec-gen])
                                 true)))))
  @database)

(defn perms* [parent [node-name & [maybe-attrs & more :as all]]]
  (let [[node-name type] (str/split (name node-name) #"#")
        node-name (keyword node-name)
        next-parent (if parent node-name ::sm/omit)
        user-auth (if (map? maybe-attrs)
                    (let [user-id (keyword (str "user" (get maybe-attrs :auth)))]
                      {:user [[user-id {:spec-gen {:emailAddress (str (name user-id) "@akvo.org")}}]]
                       :user-authorization [[1 {:refs {:userId user-id
                                                       :securedObjectId next-parent}}]]}))
        children (map
                   (partial perms* next-parent)
                   (if (map? maybe-attrs) more all))]
    (apply merge-with (fn [left right] (distinct (concat left right)))
      (when parent
        {:node [[node-name {:spec-gen {:name (name node-name)
                                       :survey-group-type (if type (str/upper-case type) "FOLDER")}
                            :refs {:parentId parent}}]]})
      user-auth
      children)))

(defn perms [tree]
  (assoc (perms* nil tree)
    :role [[1 {:spec-gen {:permissions #{"PROJECT_FOLDER_READ"}}}]]))

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
      {:node ^:in-any-order [[:1 {:spec-gen {:name "1" :survey-group-type "SURVEY"}}]]})))