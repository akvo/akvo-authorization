(ns akvo-authorization.util.nrepl
  (:require [integrant.core :as ig]
            [nrepl.server :refer [start-server stop-server]]))

(defmethod ig/init-key ::nrepl [_ {:keys [bind port]}]
  (start-server :bind bind :port port))

(defmethod ig/halt-key! ::nrepl [server]
  (stop-server server))