(ns akvo-authorization.akvo-flow-server-config
  (:import [com.google.apphosting.utils.config AppEngineWebXmlReader]
           (com.google.appengine.tools.remoteapi RemoteApiOptions RemoteApiInstaller))
  (:require
    [clojure.java.io :as io]))

(defn get-file [tmp-dir instance-id file-path]
  (let [file-name (.getName (io/file file-path))
        file (io/file tmp-dir instance-id file-name)]
    (if (.exists file)
      file
      (throw (ex-info "No config for instance" {:instance instance-id
                                                :file file-path})))))

(defn get-p12-file [tmp-dir instance-id]
  (.getAbsolutePath (get-file tmp-dir instance-id (format "/%1$s/%1$s.p12" instance-id))))

(defn get-appengine-web-xml-file [tmp-dir instance-id]
  (get-file tmp-dir instance-id (format "/%s/appengine-web.xml" instance-id)))

(defn get-instance-props [tmp-dir instance-id]
  (try
    (let [tmp-file (get-appengine-web-xml-file tmp-dir instance-id)
          ae-reader (AppEngineWebXmlReader. (format "%s%s/" tmp-dir instance-id)
                      (.getName tmp-file))]
      (.getSystemProperties (.readAppEngineWebXml ae-reader)))
    (catch clojure.lang.ExceptionInfo e)))

(defn host [instance-id]
  (str instance-id ".appspot.com"))

(defn options [instance-id]
  (let [port 443
        host (host instance-id)
        iam-account (->
                      (get-instance-props "/akvo-flow-server-config/" instance-id)
                      (get "serviceAccountId"))
        p12-path (get-p12-file "/akvo-flow-server-config/" instance-id)
        remote-path "/remote_api"
        options (-> (RemoteApiOptions.)
                  (.server host port)
                  (.remoteApiPath remote-path))]
    (.useServiceAccountCredential options
      iam-account
      p12-path)
    options))

(defmacro with-remote-api [instance-id & body]
  `(let [options# (options ~instance-id)
         installer# (RemoteApiInstaller.)]
     (.install installer# options#)
     (try
       ~@body
       (finally
         (.uninstall installer#)))))
