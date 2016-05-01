(ns ldap-shepherd.core-test
  (:require [clojure.test :refer :all]
            [ldap-shepherd.mapping :as mapping]
            [ldap-shepherd.apache-ds :as ds]
            [clj-ldap.client :as ldap])
  (:import [com.unboundid.ldap.sdk
            LDAPConnection]))
