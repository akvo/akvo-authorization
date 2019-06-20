(ns akvo-authorization.compare-with-gae.compare-with-gae
  (:require [clojure.test :refer :all]
            [akvo-authorization.akvo-flow-server-config :as gae])
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

  (def diffs
    (map
      (fn [{:keys [user_id flow_id] :as u}]
        (let [with-flow (clojure.set/difference
                          (set
                            (sort
                              (gae/with-remote-api "akvoflow-uat1"
                                (let [survey-list (doall (list-ids flow_id))]
                                  survey-list))))
                          #{36634000})
              with-authz (clojure.set/difference
                           (set
                             (sort (map :flow-id
                                     (akvo-authorization.authz/get-all-surveys-for-user
                                       (dev/local-db) {:user-id user_id}))))
                           #{20099115 109219115 20079115})]
          (assoc
            u
            :with-flow with-flow
            :with-authz with-authz
            :same? (= with-flow with-authz))))
      (clojure.java.jdbc/query (dev/local-db) ["select * from users_flow_ids where flow_instance=?" "akvoflow-uat1"])))
  (def not-correct (remove :same? diffs))
  (frequencies (map (juxt :super_admin :same?) diffs))

  (take 2
    (clojure.data/diff
      (:with-flow (first not-correct))
      (:with-authz (first not-correct))))
  )