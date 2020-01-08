(ns akvo-authorization.authz
  (:require [integrant.core :as ig]
            ragtime.jdbc
            [clojure.set :refer [rename-keys]]
            [hugsql.core :as hugsql]
            [akvo-authorization.unilog.spec :as unilog-spec]
            [compojure.core :refer [POST routes GET]]
            [ring.util.response :as ring-util]
            [clojure.spec.alpha :as s]))

(hugsql/def-db-fns "sql/user.sql")
(hugsql/def-db-fns "sql/authz.sql")

(defn find-allowed-objects [db flow-instance flow-user-id]
  (let [user (get-flow-user-by-email db {:flow-id flow-user-id :flow-instance flow-instance})]
    (if (:super-admin user)
      {:isSuperAdmin true}
      {:isSuperAdmin false
       :securedObjectIds (->>
                           (get-flow-ids-for-user-in-flow-instance db {:user-id (:user-id user)
                                                                       :flow-instance flow-instance})
                           (map :flow-id)
                           set)})))

(defn find-all-surveys [db email instances]
  (let [user-id (:id (get-user-by-email db {:email email}))]
    (get-all-surveys-for-user db {:user-id user-id :flow-instances instances})))

(defn all-instances-queried [queried-surveys aliases]
  (set
    (map (fn [{:keys [:instance_id]}]
           (get aliases instance_id instance_id))
      queried-surveys)))

(defn filter-surveys [allowed-surveys queried-surveys aliases]
  (let [queried (reduce
                  (fn [so-far {:keys [instance_id survey_id] :as queried-survey}]
                    (assoc so-far
                      [(get aliases instance_id instance_id) (Long/parseLong survey_id)]
                      queried-survey))
                  {}
                  queried-surveys)]
    (reduce (fn [so-far {:keys [flow-instance flow-id]}]
              (if-let [found (get queried [flow-instance flow-id])]
                (conj so-far found)
                so-far))
      []
      allowed-surveys)))

(s/def ::instance_id ::unilog-spec/orgId)
(s/def ::positive-integer-string (s/and string? #(re-matches #"[0-9]+" %)))
(s/def ::survey_id ::positive-integer-string)
(s/def ::full-survey-id (s/keys :req-un [::survey_id ::instance_id]))
(def survey-list-spec (s/coll-of ::full-survey-id))

(defn surveys [db flow-aliases email body]
  (if-not (s/valid? survey-list-spec body)
    (ring-util/bad-request
      {:problems (mapv (fn [problem]
                         {:pred (str (:pred problem))
                          :val (:val problem)
                          :in (:in problem)})
                   (::s/problems (s/explain-data survey-list-spec body)))})
    (let [aliases @flow-aliases
          all-instances-queried (all-instances-queried body aliases)]
      (->
        (find-all-surveys db email all-instances-queried)
        (filter-surveys body aliases)
        ring-util/response))))

(defn endpoint* [db flow-aliases]
  (routes
    (GET "/poc" [flowInstance flowUserId]
      (ring-util/response (find-allowed-objects db flowInstance (Long/parseLong flowUserId))))
    (POST "/check_permissions" {:keys [email body-params]}
      (surveys db flow-aliases email body-params))))

(defmethod ig/init-key ::endpoint [_ {:keys [db flow-aliases]}]
  (endpoint* (:spec db) flow-aliases))