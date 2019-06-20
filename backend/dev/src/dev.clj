(ns dev
  (:refer-clojure :exclude [test])
  (:require [clojure.repl :refer :all]
            [fipp.edn :refer [pprint]]
            [clojure.tools.namespace.repl :refer [refresh]]
            [clojure.java.io :as io]
            [duct.core :as duct]
            [duct.core.repl :as duct-repl]
            akvo-authorization.db-utils
            [eftest.runner :as eftest]
            [integrant.core :as ig]
            [integrant.repl :refer [clear halt go init prep reset]]
            [integrant.repl.state :refer [config system]]))

(duct/load-hierarchy)

(defn read-config []
  (duct/read-config (io/resource "akvo_authorization/config.edn")))

(defn test []
  (eftest/run-tests (eftest/find-tests "test")))

(def profiles
  [:duct.profile/dev :duct.profile/local])

(clojure.tools.namespace.repl/set-refresh-dirs "dev/src" "src" "test")

(when (io/resource "local.clj")
  (load "local"))

(integrant.repl/set-prep! #(duct/prep-config (read-config) profiles))

(defn local-db []
  (:spec (get integrant.repl.state/system [:duct.database.sql/hikaricp :authz/authz-db])))

(comment

  (clojure.java.jdbc/query (local-db) ["select * from user_node_role"] {:transaction? false})
  (clojure.java.jdbc/query (local-db) ["select * from nodes"] {:transaction? false})
  (clojure.java.jdbc/query (local-db) ["select * from roles"] {:transaction? false})
  (clojure.java.jdbc/query (local-db) ["select * from users_flow_ids"] {:transaction? false})
  (clojure.java.jdbc/query (local-db) ["select * from process_later_messages"] {:transaction? false})
  (clojure.pprint/print-table (clojure.java.jdbc/query (local-db) ["select * from unilog_offsets"] {:transaction? false}))

  (do
    (doseq [m (reverse (ragtime.jdbc/load-resources "migrations"))]
      (ragtime.core/rollback (ragtime.jdbc/sql-database (dev/local-db)) m))
    (ragtime.core/migrate-all (ragtime.jdbc/sql-database (dev/local-db)) {} (ragtime.jdbc/load-resources "migrations")))
  )