(defproject akvo-authorization "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :min-lein-version "2.0.0"
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [duct/core "0.7.0"]
                 [duct/module.logging "0.4.0"]
                 [duct/module.sql "0.5.0"]
                 [duct/module.web "0.7.0"]
                 [reifyhealth/specmonstah "2.0.0-alpha-1"]
                 [org.clojure/test.check "0.10.0-alpha3"]
                 [org.clojure/spec.alpha "0.2.176"]
                 [org.postgresql/postgresql "42.2.5"]
                 [hugsql-adapter-case "0.1.0"]
                 [com.taoensso/nippy "2.14.0"]
                 [com.layerware/hugsql "0.4.9"]

                 [iapetos "0.1.8"]
                 [io.prometheus/simpleclient_hotspot "0.6.0"]
                 [io.prometheus/simpleclient_jetty_jdk8 "0.6.0"]]
  :plugins [[duct/lein-duct "0.12.1"]
            [lein-ancient "0.6.15"]]
  :main ^:skip-aot akvo-authorization.main
  :resource-paths ["resources" "target/resources"]
  :prep-tasks     ["javac" "compile" ["run" ":duct/compiler"]]
  :middleware     [lein-duct.plugin/middleware]
  :profiles
  {:dev [:project/dev :profiles/dev]
   :repl {:prep-tasks ^:replace ["javac" "compile"]
          :repl-options {:init-ns dev
                         :init (do
                                 (println "Starting BackEnd ...")
                                 (go))
                         :host "0.0.0.0"
                         :port 47480}}
   :uberjar {:aot :all}
   :profiles/dev {}
   :project/dev {:source-paths ["dev/src"]
                 :resource-paths ["dev/resources"]
                 :dependencies [[integrant/repl "0.3.1"]
                                [eftest "0.5.8"]
                                [metosin/testit "0.4.0"]
                                [spec-provider "0.4.14"]
                                [kerodon "0.9.0"]
                                [org.akvo.flow/akvo-flow "v1.9.43-5-gff7e9ea" :classifier "classes"
                                 :exclusions [[commons-fileupload]]]
                                [com.google.appengine/appengine-tools-sdk "1.9.50"]
                                [com.google.appengine/appengine-remote-api "1.9.50"]
                                [com.google.appengine/appengine-api-1.0-sdk "1.9.50"]
                                [com.google.appengine/appengine-jsr107cache "1.9.50"]]}})
