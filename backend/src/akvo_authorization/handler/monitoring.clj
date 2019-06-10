(ns akvo-authorization.handler.monitoring
  (:require [compojure.core :refer :all]
            [integrant.core :as ig]))

(defmethod ig/init-key :akvo-authorization.handler/healthz [_ _]
  (GET "/healthz" [] "OK"))
