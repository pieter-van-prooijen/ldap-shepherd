(ns ldap-shepherd.mapping-test
  (:require [clojure.test :refer :all]
            [ldap-shepherd.mapping :as mapping]
            [ldap-shepherd.apache-ds :as ds]
            [clj-ldap.client :as ldap])
  (:import [com.unboundid.ldap.sdk
            LDAPConnection]))


(deftest dn-escape
  (testing "Escaping special characters in dn components"
    (is (= "\\,\\," (mapping/dn-escape ",,")))
    (is (= "\\\\" (mapping/dn-escape "\\")))
    (is (= "\\\"") (mapping/dn-escape "\""))))

(deftest dn-unescape
  (testing "Un-escaping a dn component"
    (is (= "," (mapping/dn-unescape "\\,")))))


;; Run the mapping / tests using an embedded apache-ds server.

(defn with-connection [f]
  
  (let [c (LDAPConnection. "localhost" 10389)]
    (try
      (f c)
      (finally (when c (.close c))))))

(defn create-users [c]
  (ldap/add c "dc=rijksoverheid,dc=nl"
            {:objectClass ["organizationalunit" "dcObject" "top"] :ou "rijksoverheid"
             :dc "rijksoverheid"})
  (ldap/add c "ou=users,dc=rijksoverheid,dc=nl"
            {:objectClass "organizationalunit"
             :ou "users"})
  (ldap/add c "ou=applications,dc=rijksoverheid,dc=nl"
            {:objectClass "organizationalunit"
             :ou "applications"}))

(use-fixtures :once (fn [f] 
                      (let [server (ds/start)]
                        (try 
                          (with-connection #(create-users %1))
                          (f)
                          (finally (when server (ds/stop server)))))))

(deftest test-create-fetch-delete-user
  (testing "Create and fetch user"
    (is (= "some-user" 
           (:uid (with-connection (fn [c]
                                    (mapping/create-user c {:uid  "some-user"
                                                            :full-name "some-full-name"
                                                            :email "some-email"
                                                            :password "secrit"})
                                    (mapping/get-user c "some-user"))))))
    (with-connection #(mapping/delete-user %1 "some-user"))
    (is (nil? (with-connection #(mapping/get-user %1 "some-user"))))))


