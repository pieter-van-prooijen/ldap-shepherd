(ns init-repl
  (:require [weasel.repl.websocket]
            [cljs.repl.browser]
            [cemerick.piggieback]))

;; Piggieback a browser repl onto the current nrepl session.
;; See also the file ldap-shepherd/piggieback_browser.cljs for the clojurescript counterpart

(cemerick.piggieback/cljs-repl
 :repl-env (weasel.repl.websocket/repl-env))

