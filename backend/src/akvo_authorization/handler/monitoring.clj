(ns akvo-authorization.handler.monitoring
  (:require [iapetos.core :as prometheus]
            [iapetos.collector.jvm :as jvm]
            [iapetos.collector.ring :as ring]
            [integrant.core :as ig]
            [iapetos.collector.exceptions :as ex]))

(defn wrap-health-check
  [handler]
  (fn [{:keys [request-method uri] :as request}]
    (if (and (= uri "/healthz") (= request-method :get))
      {:status 200}
      (handler request))))

(defmethod ig/init-key ::collector [_ config]
  (->
    (prometheus/collector-registry)
    (jvm/initialize)
    (prometheus/register
      (prometheus/counter :event/total {:labels [:flow-instance]})
      (prometheus/counter :event/correct-type {:labels [:flow-instance]})
      (prometheus/counter :event/valid {:labels [:flow-instance]}))
    (ring/initialize)))

(defmethod ig/init-key ::middleware [_ {:keys [collector]}]
  #(-> %
     wrap-health-check
     (ring/wrap-metrics collector)))

(comment
  (slurp "http://localhost:3000/metrics"))