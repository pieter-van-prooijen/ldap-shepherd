(ns ldap-shepherd.core
  (:require [ldap-shepherd.mapping :as mapping]
            [liberator.core :refer [resource defresource]]
            [liberator.dev]
            [ring.util.response :refer [redirect]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.json :refer [wrap-json-body]]
            [ring.middleware.resource :refer [wrap-resource]]
            [ring.middleware.file :refer [wrap-file]]
            [ring.middleware.basic-authentication :refer [wrap-basic-authentication]]
            [compojure.core :refer [defroutes ANY GET POST PUT DELETE]]
            [clj-ldap.client :as ldap]
            [clojure.pprint :refer [pprint]]
            [clojure.walk]
            [clojure.string :as string]
            [clj-time.core :as time]
            [taoensso.timbre :as timbre]
            [cemerick.url :as url])
  (:import [com.unboundid.ldap.sdk
            LDAPConnection
            LDAPConnectionPool
            LDAPException
            ResultCode
            StartTLSPostConnectProcessor]
           [com.unboundid.ldap.sdk.extensions
            StartTLSExtendedRequest]
           [com.unboundid.util.ssl
            SSLUtil
            TrustAllTrustManager]))

(timbre/refer-timbre)

;; Liberator resource definitions for the LDAP REST interface.


(def authenticated 
  "Global authentication info and connection pool."
  (atom {:name "" :password "" :created (time/epoch) :pool nil}))

(defn create-trust-all-ssl-context []
  (let [util (SSLUtil. (TrustAllTrustManager.))]
    (.createSSLContext util)))

(defn create-authenticated-connection [name password host port]
  (let [new-connection (LDAPConnection. host port)
        start-tls (StartTLSExtendedRequest. (create-trust-all-ssl-context))]
    (.processExtendedOperation new-connection start-tls)
    (let [result (.bind new-connection (mapping/get-admin-dn name) password)]
      (when (= (.getResultCode result) ResultCode/SUCCESS)
        new-connection))))

;; Execute f in the context of a connection, indicating if the connection should be destroyed after use
;; Which works around the "Other sort requests already in progress" errors in openldap
(defn with-connection [destroy f]
  (let [{pool :pool} @authenticated]
    (if destroy
      (let [c-pooled (.getConnection pool)
            c (.replaceDefunctConnection pool c-pooled)
            _ (.releaseConnection pool c-pooled)]
        (try
          (f c)
          (finally
            (.replaceDefunctConnection pool c)
            (info "Destroyed connection"))))
      (let [c (.getConnection pool)]
        (try
          (f c)
          (finally
            (.releaseConnection pool c)
            (info "Released connection " (System/identityHashCode c))))))))

;; Hook into the basic authentication mechanism.
(defn authenticated? [name password]
  ;; Revalidate the connection pool after n minutes.
  (let [{auth-name :name auth-password :password created :created existing-pool :pool} @authenticated
        age-minutes (time/in-minutes (time/interval created (time/now)))]
    (if (and existing-pool (= name auth-name) (= password auth-password) (< age-minutes 5))
      true
      (try
        (if-let [authenticated-connection (create-authenticated-connection name password "localhost" 389)]
          (let [processor (StartTLSPostConnectProcessor. (create-trust-all-ssl-context))
                pool (LDAPConnectionPool. authenticated-connection 1 10 processor)]
            (swap! authenticated (fn [_]
                                   (when existing-pool
                                     (info "Closing connection pool")
                                     (.close existing-pool))
                                   {:name name :password password :created (time/now) :pool pool}))) 
          false)
        (catch LDAPException e
          (println "Can't authenticate: " (.getMessage e))
          false)))))

(defn validate-user [doc password-required]
  (let [fields [:uid :email :full-name]]
    (not-any? #(string/blank? (% doc))
              (if password-required (conj fields :password) fields))))

(defn create-options [page page-size total-size cookie]
  (try
    {:page (Integer/valueOf page)
     :page-size (Integer/valueOf page-size)
     :total-size (Integer/valueOf total-size)
     :cookie cookie}
    (catch NumberFormatException e nil)))

;; common user / search request parsing / handling.
;; Answer a tupple of [malformed? {:options paging-options}]
(defn handle-search-options [ctx q page page-size total-size cookie]
  (let [options (create-options page page-size total-size cookie)]
    [(or (string/blank? q) (nil? options)) {:options options}]))


(def json-mimetype "application/json")

(def base-resource {:available-media-types [json-mimetype]
                    :handle-exception (fn [{:keys [exception]}]
                                        {:error (.getMessage exception)})})

(defresource get-connection []
  base-resource
  :allowed-methods [:get]
  :handle-ok (fn [ctx]
               (with-connection true 
                 (fn [c] {:host (.getConnectedAddress c) :port (.getConnectedPort c)}))))

(defresource get-users [q page page-size total-size cookie]
  base-resource
  :allowed-methods [:get]
  :malformed? (fn [ctx] 
                (handle-search-options ctx q page page-size total-size cookie))
  :handle-ok (fn [ctx]
               (with-connection true #(mapping/get-users %1 q (:options ctx)))))


(defresource create-user []
  base-resource
  :allowed-methods [:post]
  :malformed? (fn [ctx]
                (let [body-temp (get-in ctx [:request :body])
                      document (clojure.walk/keywordize-keys body-temp)]
                  [(not (validate-user document true)) {:document document}]))
  :allowed? (fn [{:keys [document]}]
              "Disallow posts for an existing user"
              (with-connection false #(not (mapping/get-user %1 (:uid document)))))
  :handle-forbidden (fn [{:keys [document]}]
                      (format "A user with uid %s is already present" (:uid document)))
  :can-post-to-missing? true
  :post! (fn [{:keys [document]}]
           (with-connection false #(mapping/create-user %1 document)))

  :post-redirect? (fn [{:keys [document]}]
                    {:location (format "/users/%s" (url/url-encode (:uid document)))}))

(defresource update-user [uid]
  base-resource
  :allowed-methods [:put]
  :malformed? (fn [ctx] 
                (let [body-temp (get-in ctx [:request :body])
                      document (clojure.walk/keywordize-keys body-temp)]
                  [(not (validate-user document false)) {:document document}]))
  :allowed? (fn [{:keys [document]}]
              "Disallow posts for a new user"
              (and (= uid (:uid document))
                   (with-connection false #(mapping/get-user %1 (:uid document)))))
  :exists? (fn [{:keys [document]}]
             (with-connection false #(mapping/get-user %1 (:uid document))))
  :handle-forbidden (fn [{:keys [document]}]
                      (format "A user with uid %s is not  present" (:uid document)))
  :can-put-to-missing? false
  :put! (fn [{:keys [document]}]
          (with-connection false #(mapping/update-user %1 document)))
  :new? false
  :respond-with-entity? true
  :handle-ok {:uid uid})

(defn user-exists [uid]
  (let [user (with-connection false #(mapping/get-user %1 uid))]
    [user {:user user}]))

(defresource get-user [uid]
  base-resource
  :allowed-methods [:get]
  :malformed? (fn [ctx] (string/blank? uid))
  :exists? (fn [ctx] (user-exists uid))
  :handle-ok (fn [{:keys [user]}]
               user))

(defresource delete-user [uid]
  base-resource
  :allowed-methods [:delete]
  :malformed? (fn [ctx] (string/blank? uid))
  :exists? (fn [ctx] (user-exists uid))
  :delete! (fn [ctx] (with-connection false #(mapping/delete-user %1 uid)))
  :handle-no-content nil)

(defn validate-group [doc]
  (not-any? #(string/blank? (% doc)) [:gid]))

(defresource create-group []
  base-resource
  :allowed-methods [:post]
  :malformed? (fn [ctx] 
                (let [body-temp (get-in ctx [:request :body])
                      document (clojure.walk/keywordize-keys body-temp)]
                  [(not (validate-group document)) {:document document}]))
  :allowed? (fn [{:keys [document]}]
              "Disallow posts for an existing group"
              (with-connection false #(not (mapping/get-group %1 (:gid document)))))
  :handle-forbidden (fn [{:keys [document]}]
                      (format "A group with gid / ou %s is already present" (:gid document)))
  :can-post-to-missing? true
  :post! (fn [{:keys [document]}]
           (with-connection false #(mapping/create-group %1 document)))

  :post-redirect? (fn [{:keys [document]}]
                    {:location (format "/groups/%s" (url/url-encode (:gid document)))}))

(defresource get-groups [q page page-size total-size cookie]
  base-resource
  :allowed-methods [:get]
  :malformed? (fn [ctx]
                (handle-search-options ctx q page page-size total-size cookie))
  :handle-ok (fn [ctx]
               (with-connection true #(mapping/get-groups %1 q (:options ctx)))))

(defn group-exists [gid]
  (let [group (with-connection false #(mapping/get-group %1 gid))]
    [group {:group group}]))

(defresource get-group [gid]
  base-resource
  :allowed-methods [:get]
  :malformed? (fn [ctx] (string/blank? gid))
  :exists? (fn [ctx] (group-exists gid))
  :handle-ok (fn [{:keys [group]}]
               (assoc group :users (with-connection false #(mapping/get-users-in-group %1 gid)))))

(defresource delete-group [gid]
  base-resource
  :allowed-methods [:delete]
  :malformed? (fn [ctx] (string/blank? gid))
  :exists? (fn [ctx] (group-exists gid))
  :delete! (fn [ctx] (with-connection false #(mapping/delete-group %1 gid)))
  :handle-no-content nil)

(defresource group-with-user [gid uid]
  base-resource
  :allowed-methods [:post :delete]
  :malformed? (fn [ctx] (or (string/blank? gid) (string/blank? uid)))
  :handle-malformed (fn [ctx] (str "gid " gid " uid " uid))
  :allowed? (fn [_]
              (with-connection false
                #(and (mapping/get-group %1 gid) (mapping/get-user %1 uid))))
  :post! (fn [_]
           (with-connection false #(mapping/add-user-to-group %1 gid uid)))
  :delete! (fn [_]
             (with-connection false #(mapping/delete-user-from-group %1 gid uid)))
  :handle-created {:success true})

(defroutes app
  (GET "/connection" [] (get-connection))
  (GET "/users" [q page page-size total-size cookie] 
       (get-users q page page-size total-size cookie))
  (POST "/users" [] (create-user))
  (PUT "/users/:uid" [uid] (update-user uid))
  (GET "/users/:uid" [uid] (get-user uid))
  (DELETE "/users/:uid" [uid] (delete-user uid))

  (GET "/groups" [q page page-size total-size cookie] 
       (get-groups q page page-size total-size cookie))
  (POST "/groups" [] (create-group))
  (GET "/groups/:gid" [gid] (get-group gid))
  (DELETE "/groups/:gid" [gid] (delete-group gid))
  
  (POST "/groups/:gid/:uid" [gid uid] (group-with-user gid uid))
  (DELETE "/groups/:gid/:uid" [gid uid] (group-with-user gid uid)))

(def handler
  (-> app
      (wrap-basic-authentication authenticated?)
      wrap-params
      wrap-json-body
      (wrap-file "../client/resources/public") ; serves the files from the client project for testing
      (liberator.dev/wrap-trace :ui :header)))

