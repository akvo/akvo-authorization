(ns akvo-authorization.main
  (:gen-class)
  (:require [duct.core :as duct]
            akvo-authorization.util.db))

(duct/load-hierarchy)

(defn -main [& args]
  (let [keys     (or (duct/parse-keys args) [:duct/daemon])
        profile-to-run (or
                         (#{:authz.profile/api :authz.profile/unilog-consumer} (keyword (System/getenv "AUTHZ_PROFILE_TO_RUN")))
                         (throw (RuntimeException. "Need a valid AUTHZ_PROFILE_TO_RUN var")))
        profiles [:duct.profile/prod profile-to-run]]
    (-> (duct/resource "akvo_authorization/config.edn")
        (duct/read-config)
        (duct/exec-config profiles keys))))