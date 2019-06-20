(ns akvo-authorization.main
  (:gen-class)
  (:require [duct.core :as duct]
            akvo-authorization.db-utils))

(duct/load-hierarchy)

(defn -main [& args]
  (let [keys     (or (duct/parse-keys args) [:duct/daemon])
        profiles [:duct.profile/prod]]
    (-> (duct/resource "akvo_authorization/config.edn")
        (duct/read-config)
        (duct/exec-config profiles keys))))
