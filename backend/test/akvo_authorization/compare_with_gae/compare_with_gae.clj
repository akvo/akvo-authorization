(ns akvo-authorization.compare-with-gae.compare-with-gae
  (:require [clojure.test :refer :all]
            [akvo-authorization.compare-with-gae.akvo-flow-server-config :as gae])
  (:import (com.gallatinsystems.survey.dao SurveyDAO SurveyGroupDAO)
           (com.gallatinsystems.survey.domain SurveyGroup SurveyGroup$ProjectType)
           (com.gallatinsystems.framework.dao BaseDAO)))

;; The akvo-flow-server-config repo must be made available to the container. See docker-compose.yml
;; There are right now the know following mismatches:
;;    1. SurveyGroups without a parentId do not show in Authz while Flow return them for super admins (but not for regular users). They should not appear at all.
;;    2. Some SurveyGroups are deleted in Flow but no delete message shows Unilog. Example: 20099115 for akvoflow-uat1
;;    3. When two different Flow users in the same instance have the same email, Authz merges the authorizations of both. Flow picks a random user. In theory, there should not be duplicated emails in a given instance.

(def list-by-property
  (doto
    (.getDeclaredMethod BaseDAO "listByProperty" (into-array Class [String Object String]))
    (.setAccessible true)))

(defn list-for-user [user-id]
  (let [survey-dao (SurveyGroupDAO.)
        all-surveys (.invoke list-by-property survey-dao (into-array Object ["projectType" SurveyGroup$ProjectType/PROJECT "String"]))]
    (.filterByUserAuthorizationObjectId survey-dao all-surveys user-id)))

(defn list-ids [user-id]
  (->>
    (list-for-user user-id)
    (map (fn [survey]
           (-> survey .getKey .getId)))))

(comment

  (def db {:connection-uri "jdbc:postgresql://postgres/authzdb?user=authzuser&password=authzpasswd&ssl=true"})

  (check-tenant db "akvoflow-101")

  (def all-tenants
    (->>
      (clojure.java.jdbc/query db ["select distinct(flow_instance) from users_flow_ids"])
      (map :flow_instance)
      (map (fn [t]
             (println t)
             {:tenant t
              :errors (try
                        (check-tenant db t)
                        (catch Exception e e))}))))

  (count all-tenants)

  )