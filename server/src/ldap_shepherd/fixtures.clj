(ns ldap-shepherd.fixtures
  (:use [ldap-shepherd.core])
  (:require [clojure.string :as string]
            [ldap-shepherd.mapping]))

(def first-names ["Jan" "Piet" "Kees" "Aaltje" "Brecht" "Martijn" "Marjan" "Linda" "Carla" "Henk" "Pim" "Roel"])
(def suppositions ["van" "het" "de" ""])
(def last-names ["Potma" "Letema" "Lima" "Abbema" "Hielkema" "Sijtsema" "Sytzama", "Boomsma" "Wezema" "Wijma"])

(defn generate-user []
  (let [first-name (rand-nth first-names)
        supp (rand-nth suppositions)
        last-name (rand-nth last-names)
        uid (str (first (string/lower-case first-name)) (string/lower-case last-name))
        email (str (string/join "." [(string/lower-case first-name) supp (string/lower-case last-name)]) "@mail.com")]
    {:uid uid :full-name (string/join " " [first-name supp last-name]) :email email :password "not-set"}))

;; Lazy seq of generated users.
(defn generated-users [] (cons (generate-user) (lazy-seq (generated-users))))

(defn insert-users [n]
  (doseq [user (take n (generated-users))]
    (with-connection false #(ldap-shepherd.mapping/create-user %1 user))))




