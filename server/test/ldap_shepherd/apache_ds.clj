(ns ldap-shepherd.apache-ds
  (:import [java.io File]
           [net.sf.ehcache Cache]
           [org.apache.directory.server.core DefaultDirectoryService]
           [org.apache.directory.server.constants ServerDNConstants]
           [org.apache.directory.api.ldap
            model.name.Dn
            model.schema.SchemaManager
            schemamanager.impl.DefaultSchemaManager
            schemaloader.LdifSchemaLoader
            schemaextractor.impl.DefaultSchemaLdifExtractor]
           [org.apache.directory.server.core.api
            schema.SchemaPartition
            InstanceLayout
            CacheService]
           [org.apache.directory.server.core.partition
            ldif.LdifPartition
            impl.btree.jdbm.JdbmPartition]
           [org.apache.directory.server.core.shared DefaultDnFactory]
           [org.apache.directory.server.protocol.shared.transport TcpTransport]
           [org.apache.directory.server.ldap LdapServer]))

;; See https://issues.apache.org/jira/secure/attachment/12626473/EmbeddedADSVerTrunkV2.java

(defn create-work-dir [] "")
(defn empty-work-dir [] "")

(defn create-partition [ds partition-id partition-dn]
  (let [partition (JdbmPartition. (.getSchemaManager ds) (.getDnFactory ds))
        partitions-dir (.. ds getInstanceLayout getPartitionsDirectory)]
    (doto partition
        (.setId partition-id)
        (.setPartitionPath (.toURI (File. partitions-dir  partition-id)))
        (.setSuffixDn (.create (.getDnFactory ds) partition-dn)))
    partition))

(defn add-partition [ds partition-id partition-dn]
  (let [partition (create-partition ds partition-id partition-dn)]
    (.addPartition ds partition)
    partition))

(defn init-schema-partition [ds]
  (let [instance-layout (.getInstanceLayout ds)
        schema-partition-dir (File. (.getPartitionsDirectory instance-layout) "schema")
        extractor (DefaultSchemaLdifExtractor. (.getPartitionsDirectory instance-layout))
        _ (.extractOrCopy extractor)
        schema-loader (LdifSchemaLoader. schema-partition-dir)
        schema-manager (DefaultSchemaManager. schema-loader)]
    (.setDnFactory ds (DefaultDnFactory. schema-manager (.. ds getCacheService (getCache "dn"))))
    (.loadAllEnabled schema-manager)
    (when-not (zero? (.size (.getErrors schema-manager)))
      (throw (Exception. (str " Schema load failed: " (.getErrors schema-manager)))))
    (.setSchemaManager ds schema-manager)
    (let [schema-ldif-partition (LdifPartition. schema-manager (.getDnFactory ds))
          _ (.setPartitionPath schema-ldif-partition (.toURI schema-partition-dir))
          schema-partition (SchemaPartition. schema-manager)
          _ (.setWrappedPartition schema-partition schema-ldif-partition)]
      (.setSchemaPartition ds schema-partition))))


(defn create-directory-service [work-dir]
  (let [ds (DefaultDirectoryService.)
        instance-layout (InstanceLayout. work-dir)
        _ (.setInstanceLayout ds instance-layout)
        cache-service (CacheService.)
        _ (.initialize cache-service instance-layout)
        _ (.setCacheService ds cache-service)]
    
    (init-schema-partition ds)

    (let [system-partition (create-partition ds "system" ServerDNConstants/SYSTEM_DN)]
      (.setSystemPartition ds system-partition))
   

    (add-partition ds "rijksoverheid" "dc=rijksoverheid,dc=nl")

    (.setEnabled (.getChangeLog ds) false)
    (.setDenormalizeOpAttrsEnabled ds true)
    (.setAccessControlEnabled ds false)
    (.setAllowAnonymousAccess ds true)
    ds))

(defn start []
  (let [work-dir (str (System/getProperty "java.io.tmpdir") "/apache-ds-work")
        _ (org.apache.commons.io.FileUtils/deleteDirectory (File. work-dir))
        ds (create-directory-service work-dir)
        transport (TcpTransport. 10389)
        transport-array (make-array TcpTransport 1)
        _ (aset transport-array 0 transport)
        server (doto (LdapServer.) (.setTransports transport-array) (.setDirectoryService ds))]
    (.startup ds)
    (.start server)
    server))

(defn stop [server]
  (.shutdown (.getDirectoryService server))
  (.stop server))

