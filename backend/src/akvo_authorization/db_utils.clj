(ns akvo-authorization.db-utils
  (:require [integrant.core :as ig]
            [hugsql.core :as hugsql]
            [hugsql-adapter-case.adapters :as adapter-case]
            [clojure.java.jdbc :as jdbc]
            ragtime.jdbc
            [jsonista.core :as json])
  (:import (org.postgresql.util PGobject)))

(hugsql/set-adapter! (adapter-case/kebab-adapter))

(defmethod ig/init-key ::migration [_ config]
  (ragtime.jdbc/load-resources "migrations"))

(defrecord ltree [v])

(extend-protocol jdbc/IResultSetReadColumn
  PGobject
  (result-set-read-column [pgobj _metadata _index]
    (let [type (.getType pgobj)
          value (.getValue pgobj)]
      (case type
        "json" (json/read-value value)
        "jsonb" (json/read-value value)
        "citext" (str value)
        "ltree" value
        value))))

(extend-protocol jdbc/ISQLValue
  ltree
  (sql-value [v]
    (doto (PGobject.)
      (.setType "ltree")
      (.setValue (:v v)))))
