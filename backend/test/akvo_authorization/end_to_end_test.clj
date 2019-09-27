(ns akvo-authorization.end-to-end-test
  (:require [clojure.test :refer :all]
            [clojure.java.jdbc :as jdbc]
            [akvo-authorization.unilog.consumer :as consumer]
            [testit.core :as it :refer [=in=> fact => =eventually-in=> =throws=>]]
            [akvo-authorization.flow-config.parsing-test :as flow-config-test]
            [testit.eventually :as eventually]
            [clj-http.client :as http]
            [akvo-authorization.test-util :as tu]
            [jsonista.core :as json]
            [clojure.string :as str]
            [clojure.java.io :as io])
  (:import (org.postgresql.util PGobject)
           (java.io File)))

(def unilog-db (consumer/event-log-spec {:event-log-server "postgres"
                                         :event-log-port 5432
                                         :db-name "u_unilog_events"
                                         :event-log-password "uniloguserpassword"
                                         :event-log-user "uniloguser"}))

(defn wipe-db [f]
  (jdbc/delete! unilog-db "event_log" nil)
  (tu/check-servers-are-up f))

(use-fixtures :each tu/unique-run-number)
(use-fixtures :once wipe-db)

(defn jsonb
  "Create a JSONB object"
  [s]
  (doto (PGobject.)
    (.setType "jsonb")
    (.setValue (json/write-value-as-string s))))

(defn insert-in-unilog-db [auth-tree]
  (tu/unilog-messages auth-tree
    (fn [unilog-messages]
      (doseq [message unilog-messages]
        (jdbc/insert! unilog-db "event_log" {:payload (-> message :payload jsonb)})))))

(defn check-perms* [user surveys]
  (http/post "http://authz:3000/check_permissions"
    {:as :json
     :headers {"x-akvo-email" user}
     :form-params surveys
     :content-type :json}))

(defn allowed-objects [flow-instance user]
  (:body (http/get "http://authz:3000/poc"
           {:as :json
            :query-params {:flowUserId (:id user)
                           :flowInstance flow-instance}
            :content-type :json})))

(defn check-perms [user surveys]
  (->
    (check-perms* user surveys)
    :body
    set))

(defn get-stats [stat & [host]]
  (let [unilog-consumer-host (or host (if (tu/in-CI-env?) "authz-consumer" "authz"))]
    (->>
      (http/get (str "http://" unilog-consumer-host ":3000/metrics"))
      :body
      (str/split-lines)
      (filter (fn [x] (str/starts-with? x stat))))))

(defn get-stat [stat & [host]]
  (let [unilog-consumer-host (or host (if (tu/in-CI-env?) "authz-consumer" "authz"))
        stats (->>
                (http/get (str "http://" unilog-consumer-host ":3000/metrics"))
                :body
                (str/split-lines)
                (filter (fn [x] (str/starts-with? x stat)))
                (map (fn [x] (-> x (str/split #" ") second Double/parseDouble int))))]
    (assert (#{0 1} (count stats)) "must have one or zero stats")
    (or (first stats) 0)))

(defn get-unilog-offset-stat [& [host]]
  (get-stat "event_unilog_offset{db_name=\"u_u" host))

(defn get-valid-event-stat [& [host]]
  (get-stat "event_valid{db_name=\"u_unilog_events" host))

(defn update-flow-alias-config [flow-instance-alias]
  (io/copy
    (flow-config-test/with-content
      ["folder/appengine-web.xml"
       (flow-config-test/flow-config
         (tu/flow-instance-with-test-id :instance1)
         (str flow-instance-alias ".akvofoo.org"))])
    (File. "/app/fake-github/repos/akvo/akvo-flow-server-config/zipball/master")))

(deftest end-to-end
  (binding [eventually/*eventually-timeout-ms* 10000
            eventually/*eventually-polling-ms* 100]
    (let [valid-events-stat-before (get-valid-event-stat)
          unilog-offset-before (get-unilog-offset-stat)
          entities (insert-in-unilog-db [:instance1 {:auth 1}
                                         [:survey1#survey]])
          survey (tu/find-node entities :survey1)
          flow-instance-alias (str "an-" (tu/flow-instance-with-test-id :instance1) "-alias")
          survey-full-id {:instance_id flow-instance-alias
                          :survey_id (str (:id survey))}]

      (update-flow-alias-config flow-instance-alias)

      (fact
        (check-perms (tu/email :user1) [survey-full-id])
        =eventually-in=>
        #{survey-full-id})

      (fact
        (allowed-objects (tu/flow-instance-with-test-id :instance1) (tu/find-user entities :user1))
        =eventually-in=>
        {:securedObjectIds [0]})

      ;; Checking varies Prometheus metrics
      (is (= (get-valid-event-stat) (+ valid-events-stat-before (count entities))))
      (is (> (get-unilog-offset-stat) unilog-offset-before))
      (is (not-empty (get-stats "sql_run_duration_bucket")))
      (is (not-empty (get-stats "sql_run_duration_bucket" "authz")))
      (is (not-empty (get-stats "hikaricp_")))
      (is (not-empty (get-stats "hikaricp_" "authz")))
      (when (tu/in-CI-env?)
        (is (= 0 (get-valid-event-stat "authz")))))))

(deftest health-check
  (is (= 200 (:status (http/get "http://authz:3000/healthz"))))
  (when (tu/in-CI-env?)
    (is (= 200 (:status (http/get "http://authz-consumer:3000/healthz"))))))

(deftest returns-useful-error-on-validation []
  (let [response (try
                   (check-perms* "any-user" [{:instance_id "any" :survey_id 1}])
                   (catch Exception e (ex-data e)))]
    (is (= 400 (:status response)))
    (is (seq (-> response :body json/read-value (get "problems"))))))

(comment
  (->
    (http/post
      "https://authz.akvotest.org/authz/check_permissions"
      ;"https://api.akvotest.org/flow/check_permissions"
      {:as :json
       :headers {"x-akvo-email" "me"
                 "User-Agent" "lumen"
                 "Accept" "application/vnd.akvo.flow.v2+json"
                 "Authorization" (str "Bearer " "eyJhbGciOiJSUzI1NiIsInR5cCIgOiAiSldUIiwia2lkIiA6ICJZREFraDh6UEZxNUtkZm5xdFpabHBWRzJmYWpsTHpBY3FoN0JtVjI4OUpFIn0.eyJqdGkiOiI2ODA4NThiMS03ODMwLTQyZjUtYWU2MC1lZjIxYzk3ZjczYzkiLCJleHAiOjE1NjEwNDgxNTEsIm5iZiI6MCwiaWF0IjoxNTYxMDQ3ODUxLCJpc3MiOiJodHRwczovL2tjLmFrdm90ZXN0Lm9yZy9hdXRoL3JlYWxtcy9ha3ZvIiwiYXVkIjoiYWt2by1sdW1lbiIsInN1YiI6IjFhNGIxZGQzLWZlOGItNDZjZS05MWY4LTI4MTdmMmFkZjlkYSIsInR5cCI6IkJlYXJlciIsImF6cCI6ImFrdm8tbHVtZW4iLCJub25jZSI6IjViYjNhNjc3LWU0MjktNGI1Ny1iMThiLWU5NmEzYTI3ZDI3MCIsImF1dGhfdGltZSI6MTU2MTA0Mjk3OSwic2Vzc2lvbl9zdGF0ZSI6IjQxZjBlZmZlLTdkNzAtNGEwZC04OTM3LWM1Y2M5NTA4Mzg3YiIsImFjciI6IjAiLCJhbGxvd2VkLW9yaWdpbnMiOlsiaHR0cHM6Ly9rYy5ha3ZvdGVzdC5vcmciLCJodHRwczovL2x1bWVuY2l0ZXN0LmFrdm90ZXN0Lm9yZyIsImh0dHBzOi8vZGFyay1sdW1lbmNpdGVzdC5ha3ZvdGVzdC5vcmciLCJodHRwOi8vdDEubHVtZW4ubG9jYWw6MzAzMCIsImh0dHBzOi8vbHVtZW4uYWt2b3Rlc3Qub3JnIiwiaHR0cHM6Ly9kYXJrLWx1bWVuLmFrdm90ZXN0Lm9yZyJdLCJyZWFsbV9hY2Nlc3MiOnsicm9sZXMiOlsiYWt2bzpsdW1lbjpsdW1lbmNpdGVzdDphZG1pbiIsImFrdm86bHVtZW46ZGFudGVzdCIsImFrdm86bHVtZW46bHVtZW4iLCJ1bWFfYXV0aG9yaXphdGlvbiIsImFrdm86bHVtZW46bHVtZW5jaXRlc3QiLCJha3ZvOmx1bWVuOmRhbnRlc3Q6YWRtaW4iXX0sInJlc291cmNlX2FjY2VzcyI6eyJhY2NvdW50Ijp7InJvbGVzIjpbIm1hbmFnZS1hY2NvdW50IiwibWFuYWdlLWFjY291bnQtbGlua3MiLCJ2aWV3LXByb2ZpbGUiXX19LCJuYW1lIjoiRGFuaWVsIExlYnJlcm8iLCJwcmVmZXJyZWRfdXNlcm5hbWUiOiJkYW4iLCJnaXZlbl9uYW1lIjoiRGFuaWVsIiwiZmFtaWx5X25hbWUiOiJMZWJyZXJvIiwiZW1haWwiOiJkYW5AYWt2by5vcmcifQ.Rk7nyTATuNgv2xTpVWkVn685jPICcJEbR252cU1gbwZPsZXTzUhwGbY84YTSlSVmljvCY4zy-xDYvZPl2C-VR3NO2tddaj0sHrqXDHLv-91tqUmenb1oEoK7d_0uFC64qCbFmCwBBd8VRaYd9cDzXtNUegpiQDIDyHRGxD1Gb6tXiECB_VygdnIM2ijLEMeKUrJnjalbkZW9DvGNIhPAUUjvTRlbqq4ZPn5pq_ZceoinkDLbO6h3-aESk4w4dcsTq6qSkiCagO4PtQGp7kwNOm1eJfGiBdjoHxsbRL1FyIcnEpFhd8rS9eaA7dkBkOh65dGWzqH5agtINBDgfMcpAw")
                 }
       :form-params '({:instance_id "uat1", :survey_id "48477041"} {:instance_id "flowliberia", :survey_id "48477041"} {:instance_id "flowliberia-hrd", :survey_id "48477041"} {:instance_id "flowliberia", :survey_id "48477041"} {:instance_id "flowliberia", :survey_id "48477041"})
       :content-type :json})
    (try (catch Exception e (ex-data e))))
  (http/get "https://authz.akvotest.org/authz/metrics"))