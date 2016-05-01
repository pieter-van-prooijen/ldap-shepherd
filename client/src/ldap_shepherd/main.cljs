(ns ldap-shepherd.main
  (:require [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]
            [sablono.core :as html :refer-macros [html]]
            [ldap-shepherd.group :as group]
            [ldap-shepherd.user :as user]
            [ldap-shepherd.common :as common]
            [ldap-shepherd.ldap]
            [figwheel.client :include-macros true]))

(defn create-button [state owner]
  "Show an (optional) create button in the top-right corner."
  (om/component
   (cond
    (and (= (:mode state) :users) (not (:user state)))
    (om/build user/create-user-button state)
    (and (= (:mode state) :groups) (not (:group state)))
    (om/build group/create-group-button state))))

(defn side-bar [state owner opts]
  (om/component
   (html [:div.large-2.columns
          [:ul.side-nav 
           [:li {:class (when (= (:mode state) :users) "active")}
            [:a {:on-click (fn [_]
                             (om/update! state :mode :users)
                             (user/reload-users state owner))} "Users"]]
           [:li {:class (when (= (:mode state) :groups) "active")}
            [:a {:on-click (fn [_]
                             (om/update! state :mode :groups)
                             (group/reload-groups state owner))} "Groups"]]]])))

;; Main panel has four states: list-of-users, edit-user, list-of-groups, edit-group
(defn main-panel [state owner opts]
  (om/component
   (html [:div.row
          (when (= (:mode state) :users)
            (if (:user state)
              (om/build user/edit-user state
                        {:opts {:did-finish-fn
                                (fn [user-changed]
                                  (when user-changed (user/reload-users state owner)))}})
              (om/build user/list-users state)))
          (when (= (:mode state) :groups)
            (if (:group state)
              (om/build group/edit-group state
                        {:opts {:did-finish-fn
                                (fn [group-changed]
                                  (when group-changed (group/reload-groups state owner)))}})
              (om/build group/list-groups state 
                        {:opts {:allow-delete true}})))])))

(defn root [state owner options]
  (reify
    om/IWillMount
    (will-mount [_]
      (user/reload-users state owner))
    om/IRender
    (render [_]
      (html [:div 
             ;; title row
             [:div.row 
              [:div.large-2.columns]
              [:div.large-10.columns [:h1 "LDAP"]]]
             ;; main row
             [:div.row
              ;; side bar
              (om/build side-bar state)
              ;; right column 
              [:div.large-10.columns
               [:div.row
                [:div.large-8.columns
                 [:h2 (if (= (:mode state) :users) "Users" "Groups")]]
                [:div.large-4.columns
                 (om/build create-button state)]]

               [:hr]
               (om/build common/message-box {})

               ;; Main panel with either a list or an edit form
               (om/build main-panel state)

               ;; Status panel 
               [:div.row
                [:div.column.large-8.columns [:span ""]]
                [:div.column.large-4
                 (om/build common/connection-info (:connection-info state))]]]]]))))
 
(defonce global-state (atom {:q "*"
                             :message {:severity :none :text ""}
                             :connection-info {:host "" :port ""}
                             :page 0 :page-size 10 :total-size 0
                             :mode :users
                             :users []
                             :groups []}))

(defn main []
  (om/root root global-state
           {:target (.getElementById js/document "app")}))

;; Call om/root on initial file load
(defonce initial-file-load (main))

;; Figwheel automatic code reloading, start autobuild + figwheel with "lein figwheel"

(figwheel.client/watch-and-reload
  :websocket-url   "ws://localhost:3449/figwheel-ws" ; jetty webserver.
  :jsload-callback (fn []
                     (print "reloaded files")
                     (main)))
