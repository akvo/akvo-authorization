(ns akvo-authorization.handler.monitoring
  (:require [compojure.core :refer :all]
            [integrant.core :as ig]))

(defmethod ig/init-key :akvo-authorization.handler/monitoring [_ _]
  (GET "/healthz" [] "OK"))
