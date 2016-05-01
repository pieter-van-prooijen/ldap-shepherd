(ns ldap-shepherd.piggieback-browser
  (:require [weasel.repl :as ws-repl]
            [clojure.browser.repl :as repl]
            [goog.log]))

 
;;(.setLevel (goog.log/getLogger "goog.net.WebSocket") goog.log/Level.FINE)

;; Weasel repl.
(ws-repl/connect "ws://localhost:9001" :verbose true)

;; Clojure script repl
#_(repl/connect "http://localhost:9000/repl")

   
