(ns ldap-shepherd.ldap
  (:require [goog.json :as json]
            [goog.Uri]
            [goog.net.XhrIo :as xhrio]
            [goog.net.HttpStatus :as status]
            [clojure.set :as set]
            [cljs.core.async :refer [<! >! chan put!]]
            [clojure.string :as string])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(def ldap-base-url "http://localhost:3000")

(def json-header #js {"Content-Type" "application/json"})

;; Note: goog.Uri only does an encodeURI on the path, which means that comma's etc. are not escaped
(defn- create-uri [path params]
 "Create a goog.Uri instance to the ldap rest service, with the specified path and parameter map"
 (let [uri (goog.Uri. ldap-base-url)
       _ (.setPath uri path false)] ; don't decode the path
   (doseq [[k v] params]
     (.setParameterValues uri (name k) (clj->js v)))
   uri))

(defn- handle-response [c e]
  "Read a JSON response and put it on the channel c with either and :error or :result key"
  (let [status (.. e -target getStatus)
        result-js (if (= status status/NO_CONTENT) #js {:result ""} (.. e -target getResponseJson))
        result (js->clj result-js :keywordize-keys true)]
    (go
      (if (status/isSuccess status)
        (>! c {:result result})
        (>! c {:error (or (:error result) status)})))))

(defn get-paged-list [uri-path q page page-size total-size cookie c]
  "Get a list of all ldap users matching search string q and put this on the channel"
  (let [callback (partial handle-response c)
        params {:q q :page page :page-size page-size :total-size total-size}]
    ;; Cookie is optional
    (xhrio/send (create-uri uri-path (if cookie (assoc params :cookie cookie) params))
                callback)))
 
(defn get-users [& args]
  (apply get-paged-list "/users" args))

(defn get-groups [& args]
  (apply get-paged-list "/groups" args))

(defn get-group [gid c]
  (let [callback (partial handle-response c)]
    (xhrio/send (create-uri (str "/groups/" gid) {}) callback)))

(defn create-group [group c]
  (let [callback (partial handle-response c)
        content (json/serialize (clj->js group))]
    (xhrio/send (create-uri (str "/groups") {}) callback "POST" content json-header)))

;; not yet implemented.
(defn update-group [group c])

(defn delete-group [gid c]
  (let [callback (partial handle-response c)]
    (xhrio/send (create-uri (str "/groups/" gid) {}) callback "DELETE" "" json-header)))

(defn get-user [uid c]
  (let [callback (partial handle-response c)
        uri (create-uri (str "/users/" uid) {})]
    (xhrio/send (create-uri (str "/users/" uid) {}) callback)))

(defn create-user [user c]
  (let [callback (partial handle-response c)
        content (json/serialize (clj->js user))]
    (xhrio/send (create-uri (str "/users") {}) callback
                "POST" content json-header)))

(defn update-user [user c]
  (let [callback (partial handle-response c)
        content (json/serialize (clj->js user))]
    (xhrio/send (create-uri (str "/users/" (:uid user)) {}) callback
                "PUT" content json-header)))

(defn delete-user [uid c]
  (let [callback (partial handle-response c)]
    (xhrio/send (create-uri (str "/users/" uid) {}) callback "DELETE" "" json-header)))

(defn add-user-to-group [gid uid c]
  (let [callback (partial handle-response c)]
    (xhrio/send (create-uri (str "/groups/" gid "/" uid) {}) callback
                "POST" "" json-header)))

(defn delete-user-from-group [gid uid c]
  (let [callback (partial handle-response c)]
    (xhrio/send (create-uri (str "/groups/" gid "/" uid) {}) callback
                "DELETE" "" json-header)))

(defn update-groups [uid new-groups-vec old-groups-vec c]
  (let [new-groups (set new-groups-vec)
        old-groups (set old-groups-vec)
        added (set/difference new-groups old-groups)
        deleted (set/difference old-groups new-groups)]
    (doseq [add added]
      (add-user-to-group add uid c))
    (doseq [delete deleted]
      (delete-user-from-group delete uid c))))

(defn connection [c]
  (let [callback (partial handle-response c)]
    (xhrio/send (create-uri "/connection" {}) callback)))
