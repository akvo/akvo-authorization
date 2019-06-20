(ns akvo-authorization.end-to-end-test
  (:require [clojure.test :refer :all]
            [clojure.java.jdbc :as jdbc]
            [akvo-authorization.unilog.consumer :as consumer]
            [testit.core :as it :refer [=in=> fact => =eventually-in=>]]
            [testit.eventually :as eventually]
            [clj-http.client :as http]
            [akvo-authorization.authz-logic-test :as authz-test]
            [jsonista.core :as json]
            [clojure.string :as str])
  (:import (org.postgresql.util PGobject)))

(def unilog-db (consumer/event-log-spec {:event-log-server "postgres"
                                         :event-log-port 5432
                                         :db-name "u_unilog_events"
                                         :event-log-password "uniloguserpassword"
                                         :event-log-user "uniloguser"}))

(defn wipe-db [f]
  (jdbc/delete! unilog-db "event_log" nil)
  (f))

(use-fixtures :each authz-test/unique-run-number)
(use-fixtures :once wipe-db)

(defn jsonb
  "Create a JSONB object"
  [s]
  (doto (PGobject.)
    (.setType "jsonb")
    (.setValue (json/write-value-as-string s))))

(defn insert-in-unilog-db [auth-tree]
  (authz-test/unilog-messages auth-tree
    (fn [unilog-messages]
      (doseq [message unilog-messages]
        (jdbc/insert! unilog-db "event_log" {:payload (-> message :payload jsonb)})))))

(defn check-perms [user surveys]
  (-> (http/post "http://localhost:3000/check_permissions"
        {:as :json
         :headers {"x-akvo-email" user}
         :form-params surveys
         :content-type :json})
    :body
    set))

(defn get-valid-event-stat []
  (let [stats (->>
                (http/get "http://localhost:3000/metrics")
                :body
                (str/split-lines)
                (filter (fn [x] (str/starts-with? x "event_valid{db_name=\"u_unilog_events")))
                (map (fn [x] (-> x (str/split #" ") second Double/parseDouble int))))]
    (assert (#{0 1} (count stats)) "must have one or zero stats")
    (or (first stats) 0)))

(deftest end-to-end
  (binding [eventually/*eventually-timeout-ms* 10000
            eventually/*eventually-polling-ms* 100]
    (let [valid-events-stat-before (get-valid-event-stat)
          entities (insert-in-unilog-db [:instance1 {:auth 1}
                                         [:survey1#survey]])
          survey (authz-test/find-node entities :survey1)
          survey-full-id {:instance_id (authz-test/flow-instance-with-test-id :instance1)
                          :survey_id (:id survey)}]

      (fact
        (check-perms (authz-test/email :user1) [survey-full-id])
        =eventually-in=>
        #{survey-full-id})

      (is (= (get-valid-event-stat) (+ valid-events-stat-before (count entities)))))))

(deftest health-check
  (is (= 200 (:status (http/get "http://localhost:3000/healthz"))) ))