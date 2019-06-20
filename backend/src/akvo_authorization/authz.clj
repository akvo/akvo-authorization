(ns akvo-authorization.authz
  (:require [integrant.core :as ig]
            ragtime.jdbc
            [clojure.set :refer [rename-keys]]
            [hugsql.core :as hugsql]
            [akvo-authorization.unilog.spec :as unilog-spec]
            [compojure.core :refer [POST routes]]
            [ring.util.response :as ring-util]
            [clojure.spec.alpha :as s]))

(hugsql/def-db-fns "sql/user.sql")
(hugsql/def-db-fns "sql/authz.sql")

(defn find-all-surveys [db email]
  (let [user-id (:id (get-user-by-email db {:email email}))]
    (get-all-surveys-for-user db {:user-id user-id})))

(defn filter-surveys [allowed-surveys queried-surveys]
  (let [allowed (into #{} (map (juxt :flow-instance (comp str :flow-id))) allowed-surveys)]
    (filter
      (fn [{:keys [instance_id survey_id]}]
        (allowed [instance_id survey_id]))
      queried-surveys)))

(s/def ::instance_id ::unilog-spec/orgId)
(s/def ::positive-integer-string (s/and string? #(re-matches #"[0-9]+" %)))
(s/def ::survey_id ::positive-integer-string)
(s/def ::full-survey-id (s/keys :req-un [::survey_id ::instance_id]))
(def survey-list-spec (s/coll-of ::full-survey-id))

(defn surveys [db email body]
  (if-not (s/valid? survey-list-spec body)
    (ring-util/bad-request
      {:problems (mapv (fn [problem]
                         {:pred (str (:pred problem))
                          :val (:val problem)
                          :in (:in problem)})
                   (::s/problems (s/explain-data survey-list-spec body)))})
    (->
      (find-all-surveys db email)
      (filter-surveys body)
      ring-util/response)))

(defn endpoint* [db]
  (POST "/check_permissions" {:keys [email body-params]}
    (surveys db email body-params)))

(defmethod ig/init-key ::endpoint [_ {:keys [db]}]
  (endpoint* (:spec db)))