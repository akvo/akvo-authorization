(ns akvo-authorization.authz
  (:require [integrant.core :as ig]
            ragtime.jdbc
            [clojure.java.jdbc :as jdbc]
            [hugsql-adapter-case.adapters :as adapter-case]
            [hugsql.core :as hugsql]))

(defmethod ig/init-key ::migration [_ config]
  (ragtime.jdbc/load-resources "migrations"))

(hugsql/def-db-fns "sql/user.sql" {:adapter (adapter-case/kebab-adapter)})
(hugsql/def-db-fns "sql/authz.sql" {:adapter (adapter-case/kebab-adapter)})

(defn find-all-surveys [db email]
  (let [user-id (:id (get-user-by-email db {:email email}))]
    (get-all-surveys-for-user db {:user-id user-id})))
