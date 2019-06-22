(ns akvo-authorization.flow-config.parsing-test
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [akvo-authorization.flow-config.github :as flow-config])
  (:import (java.util.zip ZipEntry ZipOutputStream)
           (java.io ByteArrayOutputStream File)))

(defmacro ^:private with-entry
  [zip entry-name & body]
  `(let [^ZipOutputStream zip# ~zip]
     (.putNextEntry zip# (ZipEntry. ~entry-name))
     ~@body
     (flush)
     (.closeEntry zip#)))

(defn flow-config [instance alias]
  (->
    (slurp (io/resource "akvo_authorization/flow_config/appengine-web.xml"))
    (str/replace "aliastobechanged" alias)
    (str/replace "an-instance-to-be-changed" instance)
    (.getBytes "UTF-8")
    io/input-stream))

(defn with-content [& contents]
  (with-open [zip (ByteArrayOutputStream.)
              output (ZipOutputStream. zip)]
    (doseq [content contents]
      (if (string? content)
        (with-entry output content)
        (with-entry output (first content)
          (io/copy (second content) output))))
    (io/input-stream (.toByteArray zip))))

(deftest parsing-zip
  (let [zip (with-content
              "folder/"
              ["folder/appengine-web.xml" (flow-config "instance-34" "analias.akvoflow.org")]
              "folder2/"
              ["folder2/appengine-web.xml" (flow-config "instance-65" "other-alias.akvoflow.org")])
        config (flow-config/parse-zip-file zip)]

    (is (= config {"analias" "instance-34"
                   "other-alias" "instance-65"}))))

(deftest parses-just-appengine-files
  (let [zip (with-content
              ["folder/appengine-web-not-this.xml" (flow-config "instance-34" "analias.akvoflow.org")]
              ["folder/some-txt.txt" (io/input-stream (.getBytes "hi there!"))])
        config (flow-config/parse-zip-file zip)]
    (is (= config {}))))

(deftest invalid-config
  (let [zip (with-content
              ["folder/appengine-web.xml" (flow-config "instance-34" "")]
              ["folder2/appengine-web.xml" (flow-config "" "alias.foo.org")]
              ["folder3/appengine-web.xml" (flow-config "instance-33" ".foo.org")])
        config (flow-config/parse-zip-file zip)]
    (is (= config {}))))

(deftest invalid-xml
  (let [zip (with-content
              ["folder/appengine-web.xml" (flow-config "<invliad>" "<xml")])]

    (is (thrown? org.xml.sax.SAXException (flow-config/parse-zip-file zip)))))