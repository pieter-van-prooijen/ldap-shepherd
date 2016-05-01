(ns ldap-shepherd.mapping
  (:require [clj-ldap.client :as ldap]
            [clojure.data.codec.base64 :as base64]
            [clojure.string :as string])
  (:import java.util.Arrays
           [java.security
            MessageDigest
            SecureRandom]))

;; Map cljs-ldap calls to the rest domain model


(def dn-special-chars-re #"([,\\#+<>;\"= ])")
(defn dn-escape [s]
  (string/replace s dn-special-chars-re "\\\\$1"))

(defn dn-unescape [s]
  (string/replace s #"\\([,\\#+<>;\"= ])" "$1"))

(defn get-admin-dn [user]
  (format "cn=%s,dc=rijksoverheid,dc=nl" (dn-escape user)))

(def dn-base "dc=rijksoverheid,dc=nl")
(def dn-user-base (format "ou=users,%s" dn-base))

(defn get-dn-user [uid]
  (format "uid=%s,%s" (dn-escape uid) dn-user-base))

(defn get-dn-user-unescaped [uid]
  (format "uid=%s,%s" uid dn-user-base))

(defn get-uid [dn]
  (dn-unescape (second (re-find #"uid=([^,]+)," dn))))

;; Sentinel user so a group has always one member
(def dn-user-sentinel (get-dn-user "__DUMMY__"))

(def dn-group-base (format "ou=applications,%s" dn-base))
(defn get-dn-group [gid]
  (format "ou=%s,%s" (dn-escape gid) dn-group-base))

(def charset "UTF-8")
(defn ssha-password [password]
  "Generate a SSHA hashed version of password. Note that openldap does not support SHA-2 without an optional module."
  (let [salt (byte-array 16)
        _ (.nextBytes (SecureRandom.) salt)
        bs (.getBytes password charset)
        digest (MessageDigest/getInstance "SHA1")]
    (.reset digest)
    (.update digest bs)
    (.update digest salt)
    (let [sha1 (.digest digest)
          dest  (byte-array (+ (alength sha1) (alength salt)))]
      (System/arraycopy sha1 0 dest 0 (alength sha1))
      (System/arraycopy salt 0 dest (alength sha1) (alength salt))
      (str "{SSHA}" (String. (base64/encode dest) charset)))))

(defn- ldap-get [conn base filter {:keys [cookie sort-key-arg] :as options}]
  (let [asn-cookie (and cookie (ldap/decode-cookie cookie)) 
        sort-key (or sort-key-arg ["dnQualifier" false]) ; only sortable attribute in the schema ?
        result (ldap/paged-search conn base 
                                  (assoc options :filter filter :cookie asn-cookie :sort-key sort-key))
        cookie (second result)
        enc-cookie (and cookie (ldap/encode-cookie cookie))]
    (assoc result 1 enc-cookie)))

(defn get-users [conn q options]
  (let [filter (format "(&(objectclass=inetOrgPerson)(|(cn=%s)(displayName=%s)))" q q)]
    (ldap-get conn dn-user-base filter options)))


(defn get-user-groups [conn uid]
  (let [filter (format "(&(objectClass=groupOfNames)(member=%s))" (get-dn-user-unescaped uid))]
    (ldap/search conn dn-group-base {:filter filter :attributes [:ou]})))

(defn get-user [conn uid]
  (when-let [ldap-user (ldap/get conn (get-dn-user uid))]
    {:uid (:cn ldap-user)
     :full-name (:displayName ldap-user)
     :email (:mail ldap-user)
     :groups (map :ou (get-user-groups conn uid))}))

(defn create-user [conn {:keys [uid full-name email password]}]
  (ldap/add conn (get-dn-user uid)
            {:objectClass "inetOrgPerson"
             :uid uid
             :cn uid
             :sn full-name
             :displayName full-name
             :mail email
             :userPassword (ssha-password password)}))

(declare delete-user-from-group)
(defn delete-user [conn uid]
  (doseq [{gid :ou} (get-user-groups conn uid)]
    (delete-user-from-group conn gid uid))
  (ldap/delete conn (get-dn-user uid)))

(defn update-user [conn {:keys [uid full-name email password]}]
  (let [replace {:sn full-name
                 :displayName full-name
                 :mail email}
        replace (if-not (string/blank? password)
                  (assoc replace :userPassword (ssha-password password))
                  replace)]
    (ldap/modify conn (get-dn-user uid)
                 {:replace replace})))

(defn get-groups [conn q options]
  (let [filter (format "(&(objectclass=organizationalUnit)(&(ou=%s)(!(ou=applications))))" q)]
    (ldap-get conn dn-group-base filter options)))

(defn get-group [conn gid]
  (when-let [ldap-group (ldap/get conn (get-dn-group gid))]
    {:gid (:ou ldap-group)})) 

;; Create the users groupOfNames when adding the first user.
(defn create-group [conn {:keys [gid] :as document}]
  (ldap/add conn (get-dn-group gid)
            {:objectClass "organizationalUnit"
             :ou gid}))

(defn delete-group [conn gid]
  (ldap/delete conn (get-dn-group gid)))

;; Answer all members of a group (as a list of dn's), cannot be paged ?
(defn get-users-in-group [conn gid]
  (let [dn-group (get-dn-group gid)
        filter "(member=*)"
        options {:filter filter :attributes [:member]}]
    (if-let [result (ldap/search conn dn-group options)]
      (->> (:member (first result))
          (remove #{dn-user-sentinel})
          (map get-uid)))))

(defn add-user-to-group [conn gid uid]
  (let [dn-users (format "cn=users,%s" (get-dn-group gid))
        dn-user (get-dn-user uid)]
    (if (ldap/get conn dn-users)
      (ldap/modify conn dn-users {:add {:member dn-user}})
      (do
        (ldap/add conn dn-users
                  {:objectClass "groupOfNames"
                   :cn "users"
                   :ou gid ; to easily retrieve the groups of a user
                   :member dn-user})
        ;; Group of names wants at least one member attribute.
        (ldap/modify conn dn-users {:add {:member dn-user-sentinel}}))))) 

(defn delete-user-from-group [conn gid uid]
  (let [dn-users (format "cn=users,%s" (get-dn-group gid))
        dn-user (get-dn-user uid)]
    (ldap/modify conn dn-users {:delete {:member dn-user}})))
