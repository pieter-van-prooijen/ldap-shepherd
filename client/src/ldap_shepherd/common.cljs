(ns ldap-shepherd.common
  (:require [ldap-shepherd.pagination]
            [ldap-shepherd.pagination :as pagination]
            [ldap-shepherd.ldap :as ldap]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]
            [sablono.core :as html :refer-macros [html]]
            [clojure.string :as string]
            [cljs.core.async :refer [<! >! chan put! close!]])
  (:require-macros [cljs.core.async.macros :refer [go]]))

;; Common components for the application

(defonce state-stack (atom '()))

(defn push-state []
  (swap! state-stack (fn [stack] (conj stack @ldap-shepherd.main/global-state))))

(defn pop-state []
  (if-let [state (peek @state-stack)]
    (reset! ldap-shepherd.main/global-state state)
    (swap! state-stack (fn [stack] (pop stack)))))

;; Reference cursor to the global message shown at the top.
(defn message []
  (let [root-cursor (om/root-cursor ldap-shepherd.main/global-state)]
    (om/ref-cursor (:message root-cursor))))

(defn show-message [owner severity text]
  (let [cursor (om/observe owner (message))]
    (om/update! cursor {:severity severity :text text})))
 
(defn hide-message [owner]
  (show-message owner :none ""))

;; No comma's etc. allowed in user  / group id's (comma's are not recognized in uri paths)
(def id-format #"^[a-zA-Z0-9.\\-]+$")

(defn invoke-ldap [owner ldap-fn success-fn]
  "Invoke ldap-fn with a channel and invoke success-fn on success or display an error on failure.
   Owner is used to display an error in case of failure."
  (let [c (chan)]
    (hide-message owner)
    (ldap-fn c)
    (go
      (let [{error :error result :result} (<! c)]
        (if error
          (show-message owner :error error)
          (when success-fn (success-fn result))))))) 

(defn message-box [_ owner options]
  (om/component
   (let [message-cursor (om/observe owner (message))
         {:keys [severity text]} message-cursor]
     (when-not (string/blank? text)
       (dom/div #js {:className "panel callout"} text)))))

;; Takes a map of paging params and a page-changed-fn function
(defn pagination-row [paging-params owner {:keys [:page-changed-fn :label]}]
  (om/component
   (html [:div.row
          [:div.column.large-8
           (om/build pagination/pagination (assoc paging-params :page-changed-fn page-changed-fn))]
          [:div.column.large-4
            (str (:total-size paging-params) (str " " label))]])))

(defn load-paged-list [state owner ldap-fn k place-holder]
  (hide-message owner)
  (let [c (chan)
        {:keys [q page page-size total-size cookie]} @state] ; possibly called async
    (ldap-fn q page page-size total-size cookie c)
    (om/update! state k place-holder)
    (go (let [{error :error [total-size cookie list] :result} (<! c)]
          (if error 
            (do
              (om/update! state :total-size 0)
              (show-message owner :error error))
            (do
              (om/update! state k list)
              (om/update! state :total-size total-size)
              (om/update! state :cookie cookie)))))))


(defn search-from-box [state owner update-fn]
  (let [q (.-value (om/get-node owner "query-input"))]
    (when-not (string/blank? q)
      (om/update! state :q q)
      (update-fn state))))

(defn search-box [state owner {update-fn :update-fn}]
  (om/component
   (html [:form.row
          [:div.column.large-1
           [:label.right.inline {:for "query-input"} "query:"]]
          [:div.column.large-4
           [:input {:type "text" :placeholder (if-let [q (:q state)] q "type a wildcard query...")
                    :name "q" :ref "query-input" :id "query-input"
                    :on-key-down (fn [e] (when (= "Enter" (.-key e))
                                           (.preventDefault e)
                                           (search-from-box state owner update-fn)))}]]
          [:div.column.large-1.end
           [:a.button.tiny.inline.left.radius {:on-click (fn [e]
                                                           (.preventDefault e)
                                                           (search-from-box state owner update-fn))}
            "Search"]]])))

(defn connection-info [info owner]
  (reify
    om/IWillMount
    (will-mount [_]
      (invoke-ldap owner #(ldap/connection %1) #(om/update! info %1)))
    om/IRender
    (render [_]
      (html [:span (str "ldap server: " (:host info) ":" (:port info))]))))
