(ns akvo-authorization.compare-with-gae.analyze-unprocesed-messages
  (:require [clojure.test :refer :all]
            [akvo-authorization.compare-with-gae.akvo-flow-server-config :as gae]
            [taoensso.nippy :as nippy]
            [akvo-authorization.unilog.message-processor :as msg-processor])
  (:import
    (com.gallatinsystems.survey.dao SurveyDAO SurveyGroupDAO)
    (com.gallatinsystems.survey.domain SurveyGroup SurveyGroup$ProjectType)
    (com.gallatinsystems.framework.dao BaseDAO)
    (com.gallatinsystems.user.dao UserDao UserAuthorizationDAO UserRoleDao)
    (com.gallatinsystems.user.domain UserAuthorization)))

(defn month-ago []
  (java.sql.Timestamp.
    (- (.getTime (java.util.Date.))
      (* 30 24 60 60 1000))))

(defn now []
  (java.sql.Timestamp.
    (.getTime (java.util.Date.))))

(defn survey-group-local-why [db x]
  (let [{:keys [parentId]} (-> x :message :payload :entity)
        flow-instance (:flow_instance x)]
    (cond-> nil
      (not (first (clojure.java.jdbc/query db ["select * from nodes where flow_instance=? and flow_id=? LIMIT 10" flow-instance parentId])))
      (assoc :parent-folder-missing parentId))))

(defn find-survey-in-queue [all-unilog-msgs-in-queue-with-cause flow-id]
  (first (filter
           (fn [x]
             (= flow-id (-> x :msg :flow_id)))
           all-unilog-msgs-in-queue-with-cause)))

(defn find-root-cause
  ([all-unilog-msgs-in-queu-with-cause x]
   (find-root-cause all-unilog-msgs-in-queu-with-cause x #{}))
  ([all-unilog-msgs-in-queu-with-cause x visited-parents]
   (if-let [parent-in-queue (or
                              (-> x :cause :auth-waiting-for-folder-in-queue)
                              (-> x :cause :parent-in-queue))]
     (if (visited-parents parent-in-queue)
       :circular-dependency-in-parents
       (find-root-cause all-unilog-msgs-in-queu-with-cause
         (find-survey-in-queue all-unilog-msgs-in-queu-with-cause parent-in-queue)
         (conj visited-parents parent-in-queue)))
     (-> x :cause))))

(defn find-folder [all-unprocess-messages folder-id]
  (first (filter (fn [x]
                   (= folder-id (-> x :message :payload :entity :id)))
           all-unprocess-messages)))

(defn authorization? [all-unprocess-messages auth-msg]
  (let [{:keys [securedObjectId userId roleId]} (-> auth-msg :message :payload :entity)]
    (cond
      (not (.getByKey (UserDao.) (long userId))) :user-was-deleted
      (and
        (not (zero? securedObjectId))
        (not (.getByKey (SurveyGroupDAO.) (long securedObjectId)))) :survey-was-deleted
      (not (.getByKey (UserRoleDao.) (long roleId))) :role-was-deleted
      (find-folder all-unprocess-messages securedObjectId) {:auth-waiting-for-folder-in-queue securedObjectId})))

(defn folder? [db all-unprocess-messages survey-msg]
  (let [{:keys [id parentId]} (-> survey-msg :message :payload :entity)]
    (cond
      (= id parentId) :self-parent
      (not (.getByKey (SurveyGroupDAO.) (long parentId))) :parent-deleted
      (find-folder all-unprocess-messages parentId) {:parent-in-queue parentId}
      (survey-group-local-why db survey-msg) (survey-group-local-why db survey-msg))))

(defn find-out-why-not-working [db all-unprocess-messages unilog-msg]
  (let [cause
        (try
          (case (-> unilog-msg :message :payload :eventType)
            ("userAuthorizationCreated" "userAuthorizationUpdated") (authorization? all-unprocess-messages unilog-msg)
            ("surveyGroupCreated" "surveyGroupUpdated") (folder? db all-unprocess-messages unilog-msg)
            nil)
          (catch Exception e e))]
    {:msg unilog-msg
     :cause cause}))

(defn user-auth-local-why [db x]
  (let [{:keys [securedObjectId userId roleId]} (-> x :message :payload :entity)
        flow-instance (:flow_instance x)]
    (cond-> {}
      (not (first (clojure.java.jdbc/query db ["select * from roles where flow_instance=? and flow_id=? LIMIT 10" flow-instance roleId])))
      (assoc :missing-role roleId)

      (not (first (clojure.java.jdbc/query db ["select * from users_flow_ids where flow_instance=? AND flow_id=? LIMIT 10" flow-instance userId])))
      (assoc :missing-user userId)

      (not (or
             (zero? securedObjectId)
             (first (clojure.java.jdbc/query db ["select * from nodes where flow_instance=? AND flow_id=? LIMIT 10" flow-instance securedObjectId]))))
      (assoc :missing-folder securedObjectId))))


(defn raw-to-process-unilog-msgs [db tenant]
  (->>
    (clojure.java.jdbc/query db ["select * from process_later_messages where flow_instance=? and created_at < ?" tenant (now)])
    (map (fn [x] (update x :message nippy/thaw)))))

(defn with-root-cause [db tenant raw-unilog-msgs]
  (let [msgs-with-cause (gae/with-remote-api tenant
                          (->>
                            raw-unilog-msgs
                            (mapv (partial find-out-why-not-working db raw-unilog-msgs))))]
    (map
      (fn [x]
        (assoc x :root-cause (find-root-cause msgs-with-cause x)))
      msgs-with-cause)))

(defn bogus-surveys-to-ignore [unilog-with-cause]
  (->> unilog-with-cause
    (filter (fn [x] (#{:self-parent :circular-dependency-in-parents} (:root-cause x))))
    ;(filter (fn [x] (= :parent-in-queue (:root-cause x))))
    (map (comp :flow_id :msg))))

(defn bogus [db tenant]
  (let [with-root (->>
                      (raw-to-process-unilog-msgs db tenant)
                      (with-root-cause db tenant))]
    (def unilog-msgs with-root)
    (doseq [msg (->> unilog-msgs
                  (filter (fn [{:keys [root-cause]}]
                            (#{:user-was-deleted :role-was-deleted :survey-was-deleted
                               :parent-deleted} root-cause))))]
      (msg-processor/delete-message db (:msg msg)))
    (bogus-surveys-to-ignore with-root))
  )

(def tenant "akvoflow-152")

(comment

  (def db {:connection-uri "jdbc:postgresql://postgres/authzdb?user=authzuser&password=authzpasswd&ssl=true"})

  (def unilog-msgs
    (->> (raw-to-process-unilog-msgs db tenant)
      (with-root-cause db tenant)
      ))

  (count unilog-msgs)
  (frequencies (map :root-cause unilog-msgs))

  (def unkown-broken (->>
                       unilog-msgs
                       (remove :cause)
                       (map :msg)))
  )