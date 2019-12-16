(ns akvo-authorization.compare-with-gae.compare-with-gae
  (:require [clojure.test :refer :all]
            [akvo-authorization.compare-with-gae.akvo-flow-server-config :as gae])
  (:import (com.gallatinsystems.survey.dao SurveyDAO SurveyGroupDAO)
           (com.gallatinsystems.survey.domain SurveyGroup SurveyGroup$ProjectType)
           (com.gallatinsystems.framework.dao BaseDAO)
           (com.gallatinsystems.user.dao UserDao)))

;; The akvo-flow-server-config repo must be made available to the container. See docker-compose.yml
;; There are right now the know following mismatches:
;;    1. SurveyGroups without a parentId do not show in Authz while Flow return them for super admins (but not for regular users). They should not appear at all.
;;    2. Some SurveyGroups are deleted in Flow but no delete message shows Unilog. Example: 20099115 for akvoflow-uat1
;;    3. When two different Flow users in the same instance have the same email, Authz merges the authorizations of both. Flow picks a random user. In theory, there should not be duplicated emails in a given instance.

(def list-by-property
  (doto
    (.getDeclaredMethod BaseDAO "listByProperty" (into-array Class [String Object String]))
    (.setAccessible true)))

(defn filter-surveys [surveys user-id]
  (->>
    (.filterByUserAuthorizationObjectId (SurveyGroupDAO.) surveys user-id)
    (map (fn [survey]
           (-> survey .getKey .getId)))))

(defn get-all-surveys []
  (let [survey-dao (SurveyGroupDAO.)
        all-surveys (.invoke list-by-property survey-dao (into-array Object ["projectType" SurveyGroup$ProjectType/PROJECT "String"]))]
    (doall all-surveys)))

(defn get-all-non-admin-users-in-flow []
  (let [user-dao (UserDao.)
        users (.invoke list-by-property user-dao (into-array Object ["superAdmin" false "Boolean"]))
        to-map (fn [u]
                 {:email (.getEmailAddress u)
                  :flow_id (-> u .getKey .getId)})]
    (doall (map to-map users))))

(defn real-flow-path
  ([id] (real-flow-path id #{}))
  ([id visited-nodes]
   (when-let [x (let [survey-dao (SurveyGroupDAO.)]
                  (.getByKey survey-dao id))]
     (if (and
           (.getParentId x)
           (not (zero? (.getParentId x)))
           (not= (.getParentId x) id))
       (if (visited-nodes id)
         :circular-dependency
         (concat [id] (real-flow-path (.getParentId x) (conj visited-nodes id))))
       [id]))))

(defn flow-path-correct? [id]
  (when-let [x (let [survey-dao (SurveyGroupDAO.)]
                 (.getByKey survey-dao id))]
    (let [ancestors (concat (.getAncestorIds x) [id])
          real-path (reverse (concat (real-flow-path id) [0]))]
      (= ancestors real-path))))

(defn check-folder? [folder-id]
  (let [sg (.getByKey (SurveyGroupDAO.) (long folder-id))]
    (cond
      (nil? sg) nil
      (nil? (.getAncestorIds sg)) :nil-ancestors
      (empty? (.getAncestorIds sg)) :empty-ancestors
      (not (flow-path-correct? folder-id)) :flow-path-broken)))

(defn duplicate-email-in-flow? [db tenant user-id]
  (< 1 (count (clojure.java.jdbc/query db ["select * from users_flow_ids where flow_instance=? and user_id=?" tenant user-id]))))

(defn duplicate-email-in-flow?-by-email [db tenant email]
  (< 1 (count (clojure.java.jdbc/query db ["select * from users_flow_ids where flow_instance=? and email=?" tenant email]))))

(defn check-tenant [db tenant]
  #_(def diffs
      ;; This is checking the users that authz knows, while the next is checking the users that Flow know about. Maybe
      ;; should start by checking that both set of users are the same.
      (gae/with-remote-api tenant
        (let [all-survey (get-all-surveys)]
          (mapv
            (fn [{:keys [user_id flow_id] :as u}]
              (let [with-flow (set
                                (doall (filter-surveys all-survey flow_id)))
                    with-authz (set
                                 (map :flow-id
                                   (filter (fn [{:keys [flow-instance]}]
                                             (= tenant flow-instance))
                                     (akvo-authorization.authz/get-all-surveys-for-user
                                       db {:user-id user_id}))))]
                (assoc
                  u
                  :with-flow with-flow
                  :with-authz with-authz
                  :multiple-flow-ids? (duplicate-email-in-flow? db tenant user_id)
                  :same? (= with-flow with-authz))))
            (clojure.java.jdbc/query db ["select * from users_flow_ids where flow_instance=?" tenant])))))

  (def diffs
    (gae/with-remote-api tenant
      (let [all-survey (get-all-surveys)]
        (mapv
          (fn [{:keys [email flow_id] :as u}]
            (let [with-flow (set
                              (doall (filter-surveys all-survey flow_id)))
                  with-authz (set
                               (map :flow-id
                                 (filter (fn [{:keys [flow-instance]}]
                                           (= tenant flow-instance))
                                   (akvo-authorization.authz/find-all-surveys
                                     db email))))]
              (assoc
                u
                :with-flow with-flow
                :with-authz with-authz
                :multiple-flow-ids? (duplicate-email-in-flow?-by-email db tenant email)
                :same? (= with-flow with-authz))))
          (get-all-non-admin-users-in-flow)))))

  (def broken-folders (set (akvo-authorization.compare-with-gae.analyze-unprocesed-messages/bogus db tenant)))

  (def broken-because-ancestors-are-incorrect #{})

  ;; remove broken folders
  (def corrected-diffs
    (map
      (fn [{:keys [with-flow with-authz user_id] :as x}]
        (let [without-broken (clojure.set/difference with-flow broken-folders)
              authz-without-broken (clojure.set/difference with-authz broken-because-ancestors-are-incorrect)]
          (assoc x
            :with-flow without-broken
            :same? (= authz-without-broken without-broken)
            :diff (take 2 (clojure.data/diff
                            without-broken authz-without-broken)))))
      diffs))

  (def not-correct (->>
                     corrected-diffs
                     (remove :same?)
                     (remove :multiple-flow-ids?)))

  (def incorrect-folders-in-right
    (gae/with-remote-api tenant
      (set
        (filter
          check-folder?
          (apply clojure.set/union #{}
            (map second (map :diff not-correct)))))))

  (def incorrect-folders-in-left
    (gae/with-remote-api tenant
      (set
        (filter
          check-folder?
          (apply clojure.set/union #{}
            (map first (map :diff not-correct)))))))

  (def broken-both-
    (clojure.set/union incorrect-folders-in-left incorrect-folders-in-right))

  (def re-corrected-diffs
    (map
      (fn [{:keys [with-flow with-authz user_id] :as x}]
        (let [without-broken (clojure.set/difference with-flow broken-both-)
              authz-without-broken (clojure.set/difference with-authz broken-both-)]
          (assoc x
            :with-flow without-broken
            :same? (= authz-without-broken without-broken)
            :diff (take 2 (clojure.data/diff
                            without-broken authz-without-broken)))))
      not-correct))

  (def not-correct-for-real (->>
                              re-corrected-diffs
                              (remove :same?)
                              (remove :multiple-flow-ids?)))

  (println "without correction" (frequencies (map (juxt :super_admin :same? :multiple-flow-ids?) corrected-diffs)))
  (println "with correction" (frequencies (map (juxt :super_admin :same? :multiple-flow-ids?) not-correct)))
  (println "with re-correction" (frequencies (map (juxt :super_admin :same? :multiple-flow-ids?) not-correct-for-real)))
  (println "wrong messages" (frequencies (map :root-cause akvo-authorization.compare-with-gae.analyze-unprocesed-messages/unilog-msgs)))
  not-correct-for-real
  {:not-correct-for-real not-correct-for-real
   :not-correct not-correct
   :corrected-diffs corrected-diffs
   :unprocess-messages akvo-authorization.compare-with-gae.analyze-unprocesed-messages/unilog-msgs
   :broken-folders [incorrect-folders-in-left incorrect-folders-in-right]
   }
  )



(comment


  (def db {:connection-uri "jdbc:postgresql://postgres/authzdb?user=authzuser&password=authzpasswd&ssl=true"})

  (check-tenant db "akvoflow-93")

  (def n93 *1)
  (:not-correct-for-real n93)

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

  (sort-by second (frequencies (map :errors all-tenants)))

  )