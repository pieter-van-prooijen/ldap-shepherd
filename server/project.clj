(defproject ldap-shepherd-server "0.1.0-SNAPSHOT"
  :description "RO LDAP user management tool server"
  :url ""
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :plugins [[lein-ring "0.8.13"]
            [lein-cljsbuild "1.0.3"]]

  :ring {:handler ldap-shepherd.core/handler}

  :dependencies [[org.clojure/clojure "1.6.0"]
                 [liberator "0.12.2"]
                 [compojure "1.3.2"]
                 [ring/ring-core "1.3.2"]
                 [ring/ring-json "0.3.1"]
                 [ring-basic-authentication "1.0.5"]
                 [com.unboundid/unboundid-ldapsdk "2.3.8"]
                 [org.clojure/data.codec "0.1.0"]
                 [clj-time "0.9.0"]
                 [digest "1.4.4"]
                 [com.taoensso/timbre "3.4.0"]
                 [com.cemerick/url "0.1.1"]]

  :profiles {:dev {:dependencies [[org.apache.directory.server/apacheds-all "2.0.0-M19"]
                                  [org.slf4j/slf4j-log4j12 "1.7.10"]]}}
   :jvm-opts ["-Xmx512M"]
  ;;:jvm-opts ["-Xmx512M" "-Xdebug" "-Xrunjdwp:transport=dt_socket,address=8000,server=y,suspend=n"]
  :source-paths ["src"])

