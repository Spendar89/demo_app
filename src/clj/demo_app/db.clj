(ns demo-app.db
  (:require [datomic.api :as d]
            [clojure.java.io :as io])
  (:import datomic.Util)) 

(def uri "datomic:dev://localhost:4334/demo-app")

(defn make-conn [] 
  (d/connect uri))

(defn get-db []
  (d/db (make-conn)))

(defn read-all
    "Read all forms in f, where f is any resource that can
       be opened by io/reader"
         [f]
                               (Util/readAll (io/reader f)))

(defn transact-all
  "Load and run all transactions from f, where f is any
  resource that can be opened by io/reader."
  [conn f]
  (doseq [txd (read-all f)]
    (d/transact conn [txd])))

(defn read-schema []
  (io/resource "resources/../schema.edn"))

(defn get-schema []
  (read-all (read-schema)))

(defn set-schema []
  (transact-all (make-conn) (read-schema)))

(defn add-to-schema [attr]
  (d/transact (make-conn) [attr]))

(def db (get-db))

(def all-products-query
  '[:find ?n
    :where [?n :product/name]])

(defn get-all-products [& [db]]
  (d/q all-products-query (or db (get-db))))

(defn make-tempid []
  (d/tempid :db.part/user))

(defn e->hash
  "converts entity into map that plays nicely"
  [e]
  (into (select-keys e [:db/id]) e))

(defn touch-entity [eid & [db]]
  (d/touch (d/entity (or db (get-db)) eid)))

(defn touch-and-convert-entity [eid many-attr-name & [e]]
  (let [e (or e (touch-entity eid))]
    (assoc (e->hash e) many-attr-name
           (into [] (map #(e->hash %) 
                         (vec (many-attr-name  e)))))))

(defn e->om [eid component-keys]
  (loop [component-keys component-keys
         om-result nil]
    (if-let [component-key (first component-keys)]
      (recur (rest component-keys)
             (touch-and-convert-entity eid component-key om-result))
      om-result)))

(defn retract-arr [arr]
  (mapv 
    #(merge [:db.fn/retractEntity] (:db/id %)) 
    arr))

(defn retract-data [data & [to-add]]
  (when-let [to-delete (:to-delete data)]
    (retract-arr to-delete)))

(defn xset-data 
  "retracts data assigned to :to-delete and adds the rest"
  [data] 
  (if-let [to-add (-> (->> (or (:db/id data) (make-tempid))
                           (assoc data :db/id))
                      (dissoc :to-delete))]
    (or 
      (retract-data data to-add) 
      (d/transact-async (make-conn) [to-add]))
    (retract-data data)))

(defn data-to-add 
  [data] 
  (-> (->> (or (:db/id data) (make-tempid))
           (assoc data :db/id))
      (dissoc :to-delete))) 

(defn set-data [data]
  (let [to-add (map #(data-to-add %) data)
        to-delete (retract-arr (first (mapv #(:to-delete %) (filter #(some % [:to-delete])  data))))]
    (d/transact-async (make-conn) to-add)
    (d/transact-async (make-conn) to-delete)))

(defn has-attribute?
  "Does database have an attribute named attr-name?"
  [db attr-name]
  (-> (d/entity db attr-name)
      :db.install/_attribute
      boolean))

;(d/transact (make-conn) [{:db/id (make-tempid)
;                         :product/name "Dreamer"
;                        :product/category "sock"}])
