(ns akvo-authorization.handler.email
  (:require [integrant.core :as ig]))

(defn wrap-email [handler]
  (fn [request]
    (if-let [email (get-in request [:headers "x-akvo-email"])]
      (handler (assoc request :email email))
      (handler request))))

(defmethod ig/init-key ::wrap-email [_ _]
  wrap-email)