(ns akvo-authorization.authz
  (:require [integrant.core :as ig]
            ragtime.jdbc))

(defmethod ig/init-key ::migration [_ config]
  (ragtime.jdbc/load-resources "migrations"))

(defn find-all-surveys [db email]
  )
