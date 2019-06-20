(ns akvo-authorization.authz-logic-test
  (:require [clojure.test :refer :all]
            [akvo-authorization.unilog.message-processor :as unilog]
            [akvo-authorization.authz :as authz]
            ragtime.jdbc
            [reifyhealth.specmonstah.core :as sm]
            [reifyhealth.specmonstah.spec-gen :as sg]
            [akvo-authorization.unilog.spec :as unilog-spec]
            [testit.core :as it :refer [=in=> fact =>]]
            [clojure.spec.gen.alpha :as gen]
            [akvo-authorization.test-util :as tu]
            [clojure.spec.alpha :as s]))

(use-fixtures :each tu/unique-run-number)

(defn add-authz [auth-tree]
  (tu/unilog-messages auth-tree (partial unilog/process (dev/local-db))))

(defn can-see [user]
  (let [surveys (authz/find-all-surveys (dev/local-db) (tu/email user))
        instance-name-pairs (map (juxt
                                   (comp keyword tu/remove-test-run-id :flow-instance)
                                   (comp keyword :name)) surveys)]
    (set instance-name-pairs)))

(defn delete [type flow-instance entity]
  (unilog/process (dev/local-db)
    [{:id (rand-int 100000)
      :payload {:eventType (case type
                             :user "userDeleted"
                             :role "userRoleDeleted"
                             :user-authorization "userAuthorizationDeleted"
                             :node "surveyGroupDeleted")
                :orgId (tu/flow-instance-with-test-id flow-instance)
                :entity {:id (:id entity)}}}]))

(defn upsert-entity [flow-instance [type entity :as type-entity-pair]]
  (unilog/process (dev/local-db) [(tu/->unilog
                                    (tu/flow-instance-with-test-id flow-instance)
                                    (rand-int 10000000)
                                    type-entity-pair)]))

(defn move-node-under [entities flow-instance entity-to-move to-entity]
  (let [node-to-move (tu/find-node entities entity-to-move)
        target-parent (tu/find-node entities to-entity)]
    (upsert-entity flow-instance [:node (assoc node-to-move :parentId (:id target-parent))])))

(defn create-admin [flow-instance user-id]
  (let [user (merge
               (gen/generate (s/gen ::unilog-spec/user))
               {:emailAddress (tu/email user-id)
                :superAdmin true})]
    (upsert-entity flow-instance [:user user])
    user))

(deftest authz
  (testing "basic "
    (add-authz [:uat-instance {:auth 1}
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
    (is (= #{} (can-see :user5)))))

(deftest authz-multiple-instances
  (add-authz [:uat-instance {:auth 1}
              [:folder-1
               [:survey1#survey]]])
  (add-authz [:prod-instance
              [:folder-1
               [:folder-1.1
                [:folder-1.1.2 {:auth 1}
                 [:survey1#survey]]]]])
  (is (= #{[:uat-instance :survey1] [:prod-instance :survey1]} (can-see :user1))))

(deftest admins
  (add-authz [:uat-instance
              [:folder-1
               [:survey1#survey]]])
  (add-authz [:prod-instance
              [:folder-1
               [:survey1#survey]]])
  (create-admin :uat-instance :user1)
  (create-admin :uat-instance :user2)
  (let [prod-admin (create-admin :prod-instance :user2)]
    (is (= #{[:uat-instance :survey1]} (can-see :user1)))
    (is (= #{[:uat-instance :survey1] [:prod-instance :survey1]} (can-see :user2)))
    (upsert-entity :prod-instance [:user (assoc prod-admin :superAdmin false)])
    (is (= #{[:uat-instance :survey1]} (can-see :user2)))))


(deftest moving-nodes
  (let [entities (add-authz [:uat-instance {:auth 1}
                             [:folder-1
                              [:folder-1.1 {:auth 4}
                               [:folder-1.1.1
                                [:survey1#survey {:auth 2}]]
                               [:folder-1.1.2 {:auth 3}
                                [:survey2#survey]]]
                              [:folder-1.2 {:auth 5}]]])]

    (is (= #{} (can-see :user5)))
    (is (= #{[:uat-instance :survey1]} (can-see :user2)))
    (is (= #{[:uat-instance :survey1] [:uat-instance :survey2]} (can-see :user4)))
    (move-node-under entities :uat-instance :folder-1.1.1 :folder-1.2)
    (is (= #{[:uat-instance :survey1]} (can-see :user5)))
    (is (= #{[:uat-instance :survey1]} (can-see :user2)))
    (is (= #{[:uat-instance :survey2]} (can-see :user4)))
    (is (= #{[:uat-instance :survey1] [:uat-instance :survey2]} (can-see :user1)))))

(deftest changing-email
  (let [entities (add-authz [:uat-instance
                             [:folder-1
                              [:folder-1.1 {:auth 1}
                               [:folder-1.1.1
                                [:survey1#survey]]]]])
        user (tu/find-user entities :user1)]
    (is (= #{[:uat-instance :survey1]} (can-see :user1)))
    (upsert-entity :uat-instance [:user (assoc user :emailAddress (tu/email :new-email))])
    (is (= #{} (can-see :user1)))
    (is (= #{[:uat-instance :survey1]} (can-see :new-email)))))

(deftest delete-user
  (let [entities (add-authz [:uat-instance {:auth 1}
                             [:survey1#survey]])
        user (tu/find-user entities :user1)]
    (delete :user :uat-instance user)
    (is (= #{} (can-see :user1)))))

(deftest delete-user-in-a-flow-instance-does-not-affect-his-perms-in-another-instance
  (let [entities (add-authz [:uat-instance {:auth 1}
                             [:survey1#survey]])
        _ (add-authz [:prod-instance
                      [:survey1#survey {:auth 1}]])]
    (delete :user :uat-instance (tu/find-user entities :user1))
    (is (= #{[:prod-instance :survey1]} (can-see :user1)))))

(deftest delete-user-auth
  (let [entities (add-authz [:uat-instance {:auth 1}
                             [:survey1#survey]])
        user-auths (tu/find-user-auths entities :user1)]
    (doseq [user-auth user-auths]
      (delete :user-authorization :uat-instance user-auth))
    (is (= #{} (can-see :user1)))))

(deftest delete-role
  (let [entities (add-authz [:uat-instance {:auth 1}
                             [:survey1#survey]])
        role (tu/find-role entities)]
    (delete :role :uat-instance role)
    (is (= #{} (can-see :user1)))))

(deftest delete-node
  (let [entities (add-authz [:uat-instance {:auth 1}
                             [:folder-1
                              [:survey1#survey]
                              [:folder-1.1
                               [:folder-1.1.1
                                [:survey2#survey]]]]])
        node (tu/find-node entities :folder-1.1)]
    (delete :node :uat-instance node)
    (is (= #{[:uat-instance :survey1]} (can-see :user1)))))

(deftest delete-node-with-perms-attached-to-survey
  (let [entities (add-authz [:uat-instance
                             [:folder-1
                              [:survey1#survey {:auth 1}]
                              [:folder-1.1
                               [:folder-1.1.1
                                [:survey2#survey {:auth 1}]]]]])
        node (tu/find-node entities :folder-1.1)]
    (delete :node :uat-instance node)
    (is (= #{[:uat-instance :survey1]} (can-see :user1)))))

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

(comment

  (def so-far (atom 0))
  (while (let [x (dev/test)]
           (and
             (zero? (:fail x))
             (zero? (:error x))
             (> 3000 @so-far)))
    (swap! so-far inc))

  )