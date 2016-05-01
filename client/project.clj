(defproject ldap-shepherd "0.1.0-SNAPSHOT"
  :description "RO LDAP user management tool client"
  :url ""
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :plugins [[lein-cljsbuild "1.0.5"]
            [lein-figwheel "0.2.5"]]

  :ring {:handler ldap-shepherd.core/handler}

  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/clojurescript "0.0-3126"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [sablono "0.3.4"]
                 [org.omcljs/om "0.8.8"]
                 [jayq "2.5.4"]
                 [com.novemberain/validateur "2.4.2"]
                 [inflections "0.9.13"]

                 ;; Clojurescript development dependencies
                 [com.cemerick/piggieback "0.1.5"]
                 [weasel "0.7.0"] ; needed for the new repl infrastructure
                 [figwheel "0.2.5"]]

  :jvm-opts ["-Xmx1024M"]
  ;;:jvm-opts ["-Xmx512M" "-Xdebug" "-Xrunjdwp:transport=dt_socket,address=8000,server=y,suspend=n"]

  :source-paths ["src-clj"]
  :clean-targets ^{:protect false} ["resources/public/out"]


  :cljsbuild { 
    :builds [{:id "dev"
              :source-paths ["src"]
              :compiler {
                :output-to "resources/public/ldap-shepherd.js"
                :output-dir "resources/public/out"
                :cache-analysis true
                :pretty-print true
                :optimizations :none
                :source-map true}}]}

  :repl-options {:nrepl-middleware [cemerick.piggieback/wrap-cljs-repl]}

  :figwheel {:http-server-root "public"
             :repl false
             :load-warninged-code true
             :css-dirs ["resources/public"]})

