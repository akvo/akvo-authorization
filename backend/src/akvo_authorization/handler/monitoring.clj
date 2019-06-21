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
      (prometheus/counter :event/total {:description "Total number of events read from unilog"
                                        :labels [:db-name]})
      (prometheus/counter :event/correct-type {:description "Total number of events read from unilog that the app is interested in"
                                               :labels [:db-name]})
      (prometheus/counter :event/valid {:description "Total number of events from unilog of that the app is interested in and are also valid"
                                        :labels [:db-name]})
      (prometheus/gauge :event/queued-up {:description "Total number of events queued locally, waiting for some other entity to show up"
                                          :labels [:flow-instance]})
      (prometheus/gauge :event/unilog-offset {:description "Last processed unilog offset"
                                              :labels [:db-name]})
      (prometheus/gauge :event/last-run {:description "Timestamp of last unilog consumer run"
                                         :labels [:db-name]})
      (prometheus/gauge :event/last-success {:description "Timestamp of last unilog consumer run without exceptions"
                                             :labels [:db-name]})
      (prometheus/gauge :event/last-failure {:description "Timestamp of last unilog consumer run and fail"
                                             :labels [:db-name]})
      (prometheus/gauge :event/last-start {:description "Timestamp of last unilog consumer run start time"
                                           :labels [:db-name]})
      (prometheus/histogram
        :event/tenant-duration
        {:description "Time taken to process a tenant"
         :labels [:db-name]
         :buckets [1, 3, 5, 10, 60, 300, (* 30 60), (* 60 60)]}))
    (ring/initialize)))

(defmethod ig/init-key ::middleware [_ {:keys [collector]}]
  #(-> %
     wrap-health-check
     (ring/wrap-metrics collector)))

(comment
  (slurp "http://authz:3000/metrics"))