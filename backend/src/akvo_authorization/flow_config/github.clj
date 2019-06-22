(ns akvo-authorization.flow-config.github
  (:require [clj-http.client :as http]
            [clojure.string :as str]
            [clojure.xml :as xml]
            [com.climate.claypoole :as cp]
            [integrant.core :as ig]
            [taoensso.timbre :as timbre]
            [iapetos.core :as prometheus])
  (:import (java.util.zip ZipInputStream)
           (java.io ByteArrayOutputStream ByteArrayInputStream)
           (java.util.concurrent TimeUnit Executors)))

(defonce github-url nil)

(defn headers [auth-token]
  {"Authorization" (format "token %s" auth-token)
   ;"If-None-Match" "\"5b91c8278d0ff8b7aca6d90cf2a113f446b420c6\""
   "User-Agent" "akvo-authz-app"})

(defn github-contents [auth-token]
  (http/get (str github-url "/repos/akvo/akvo-flow-server-config/zipball/master")
    {:as :stream
     :socket-timeout 120000
     :connection-timeout 10000
     :headers (headers auth-token)}))

(defn find-tag [xml to-find]
  (first (filter
           (fn [{:keys [tag]}] (= tag to-find))
           xml)))

(defn value-for-attr [xml attr-name]
  (first (keep
           (fn [{:keys [attrs]}] (when (= attr-name (:name attrs))
                                   (:value attrs)))
           xml)))

(defn read-alias [x]
  (let [top-level (:content x)]
    [(first (:content (find-tag top-level :application)))
     (value-for-attr (:content (find-tag top-level :system-properties)) "alias")]))

(defn parse-zip-entry [stream]
  (let [tmp-out (ByteArrayOutputStream.)]
    (clojure.java.io/copy stream tmp-out)
    (read-alias (xml/parse (ByteArrayInputStream. (.toByteArray tmp-out))))))

(defn iterate-zip-file [stream entry aliases-so-far]
  (if-not entry
    aliases-so-far
    (let [alias (when (and
                        (not (.isDirectory entry))
                        (str/ends-with? (.getName entry) "appengine-web.xml"))
                  (parse-zip-entry stream))]
      (recur
        stream
        (.getNextEntry stream)
        (if alias
          (conj aliases-so-far alias)
          aliases-so-far)))))

(defn load-from-stream [stream]
  (with-open [stream (ZipInputStream. stream)]
    (iterate-zip-file stream (.getNextEntry stream) [])))

(defn parse-zip-file [stream]
  (into {}
    (keep (fn [[flow-instance full-alias-domain]]
            (when (and
                    (not (str/blank? full-alias-domain))
                    (not (str/blank? flow-instance)))
              (let [alias (first (str/split full-alias-domain #"\."))]
                (when (not (str/blank? alias))
                  [alias flow-instance])))))
    (load-from-stream stream)))

(defn load-from-github [token]
  (parse-zip-file (-> (github-contents token) :body)))

(defn set-aliases! [alias-config-atom token]
  (let [new-aliases (load-from-github token)]
    (if-not (seq new-aliases)
      (throw (ex-info "Something went wrong loading the flow-config repo" {})))
    (reset! alias-config-atom new-aliases)))

(defmacro log-and-ignore-error [metrics-collector & body]
  `(try
     (prometheus/with-timestamps {:last-run (~metrics-collector :flow-config-load/last-run)
                                  :last-success (~metrics-collector :flow-config-load/last-success)
                                  :last-failure (~metrics-collector :flow-config-load/last-failure)}
       (prometheus/set ~metrics-collector :flow-config-load/last-start (System/currentTimeMillis))
       ~@body)
     (catch Throwable t#
       (timbre/error t#))))

(defmethod ig/init-key ::flow-aliases [_ _]
  (atom {}))

(defmethod ig/init-key ::start-cron [_ {:keys [github-token alias-config-atom metrics-collector github-host refresh-every-secs]}]
  (assert github-token)
  (assert github-host)
  ;; loading the very first time in the main thread so that the app does not start if something goes wrong.
  ;; once we have loaded it at least once, we are happy to github to fail.
  (alter-var-root #'github-url (constantly github-host))
  (set-aliases! alias-config-atom github-token)
  (let [cron-thread (Executors/newScheduledThreadPool 1)
        cron-task (.scheduleWithFixedDelay cron-thread
                    (fn []
                      (log-and-ignore-error metrics-collector "global"
                        (set-aliases! alias-config-atom github-token)))
                    refresh-every-secs
                    refresh-every-secs
                    TimeUnit/SECONDS)]
    {:cron-task cron-task
     :cron-thread cron-thread}))


(defmethod ig/halt-key! ::start-cron [_ {:keys [cron-thread cron-task]}]
  (when cron-task
    (.cancel cron-task true))
  (when cron-thread
    (.shutdownNow cron-thread)))
