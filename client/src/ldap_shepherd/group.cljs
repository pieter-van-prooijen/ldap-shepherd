(ns ldap-shepherd.group
  (:require [ldap-shepherd.ldap :as ldap]
            [ldap-shepherd.util :as util]
            [ldap-shepherd.common :as common]
            [ldap-shepherd.pagination :as pagination]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]
            [sablono.core :as html :refer-macros [html]]
            [cljs.core.async :refer [<! >! chan put! close!]]
            [ldap-shepherd.pagination]
            [validateur.validation :as vr])
  (:require-macros [cljs.core.async.macros :refer [go]]))

;; groups only use the "q" parameter for filtering.
(defn load-groups [state owner]
  (common/load-paged-list state owner ldap/get-groups :groups [{:ou "Loading..."}]))

(defn reload-groups [state owner]
  (om/update! state :page 0)
  (om/update! state :cookie nil)
  (load-groups state owner))

(defn group-row [{gid :ou :as group} owner {:keys [group-selected-fn delete-selected-fn]}]
  (om/component
   (html [:tr
          [:td [:a {:on-click (fn [_] (when group-selected-fn
                                        (group-selected-fn gid)))} gid]]
          [:td (when delete-selected-fn
                 [:a {:on-click (fn [_] (delete-selected-fn gid))}
                  (util/icon "delete" "delete")])]])))


(defn delete-group [owner gid state] 
  (common/invoke-ldap owner #(ldap/delete-group gid %1) #(common/show-message owner :info "Group deleted.")))

(def delete-group-modal-id "delete-group-modal")
(defn delete-group-modal [{{gid :gid :as group} :group-to-delete :as state} owner]
  (om/component
   (html [:div
          [:div.row
           [:div.large-12.columns.bold
            [:h2 (str "Do you want to delete group " gid " ?")]
            [:p (str "(it contains " (util/display-count (count (:users group)) "user") ")")]
            (util/dialog-buttons "Delete"
                                 (fn [_]
                                   (delete-group owner gid state)
                                   (util/do-reveal delete-group-modal-id :close)
                                   (reload-groups state owner))
                                 (fn [_]
                                   (util/do-reveal delete-group-modal-id :close)))]]])))

(defn popup-delete-group-modal [state owner gid]
  (common/invoke-ldap owner
               #(ldap/get-group gid %1) 
               (fn [group]
                 (om/update! state :group-to-delete group)
                 (util/do-reveal delete-group-modal-id :open))))
 

(defn list-groups [state owner {:keys [group-selected-fn allow-delete]}]
  (om/component
   (let [delete-group-modal-owner (om/build delete-group-modal state)]
     (html [:div.large-12.columns
            [:div.row
             [:div.large-8.columns
              (om/build common/search-box state {:opts {:update-fn #(reload-groups state owner)}})]]
            [:div.row
             [:div.large-12.columns
              [:table.groups {:role "grid"}
               [:thead
                [:tr
                 [:th "gid"]
                 [:th]]]
               [:tbody
                (om/build-all group-row (:groups state) 
                              {:key :ou ; use as the react key
                               :opts {:group-selected-fn group-selected-fn
                                      :delete-selected-fn 
                                      (when allow-delete 
                                        (partial popup-delete-group-modal state owner))}})]]
              (om/build common/pagination-row (select-keys state pagination/pagination-keys) 
                        {:opts {:page-changed-fn (fn [page]
                                                   (om/update! state :page page)
                                                   (load-groups state owner))
                                :label "groups"}})]]
            (om/build util/reveal-modal state {:opts {:reveal-id delete-group-modal-id
                                                      :inner-owner delete-group-modal-owner}})]))))

;;; Group selection.
 
(def reveal-id "select_group")

(defn list-groups-modal [state owner {:keys [:group-selected-fn]}]
  (om/component
   (html [:div.row
          (om/build list-groups state {:opts {:group-selected-fn group-selected-fn}})
          [:a {:on-click (fn [_] (util/do-reveal reveal-id :close))} "Cancel"]])))
 

(defn select-group [state owner {:keys [:group-selected-fn]}]
  (om/component
   (let [group-list (om/build list-groups-modal state {:opts {:group-selected-fn group-selected-fn}})]
     (om/build util/reveal-modal state {:opts {:reveal-id reveal-id :inner-owner group-list}}))))

;;; Group creation

(defn validate-group [group]
  (let [v (vr/validation-set 
           (vr/presence-of :gid)
           (vr/format-of :gid :format common/id-format :message "Group id can only contain alphanumericals, dots and dashes"))
        errors (v @group)]
    (om/update! group :errors errors)
    (empty? errors)))

(defn submit-group [group did-finish-fn]
  (when (validate-group group)
    (let [c (chan)]
      (if (:new @group)
          (ldap/create-group @group c)
          (ldap/update-group @group c))
      (go
        (let [result (<! c)]
          (common/pop-state)
          (did-finish-fn true))))))

(defn handle-change [group k e]
  (let [v (.-value (.-target e ))]
    (om/update! group k v)))

(defn edit-group [{{errors :errors :as group} :group :as state} owner {:keys [did-finish-fn]}]
  (om/component
   (let [on-change (partial handle-change group)]
     (html [:div
            [:form
             [:div.row
              [:div.large-3.columns [:label.inline.right {:for "gid"} "GID:"]]
              [:div.large-6.columns.end
               [:input {:type "text" :name "gid" :id "gid" :value (:gid group)
                        :disabled (not (:new group))
                        :on-change (partial on-change :gid)}]]]
             [:div.row 
              [:div.large-2.columns.large-offset-3
               [:a.button.radius {:href "#" :on-click (fn [_] 
                                                 (submit-group group did-finish-fn)) }
                (if (:new group) "Create"  "Modify")]]
              [:div.large-2.columns.end
               [:a {:href "#" :on-click (fn [_] 
                                          (common/pop-state)
                                          (did-finish-fn false))} "Cancel"]]]]]))))

(defn create-group-button [state owner opts]
  (om/component
   (html [:a.button.tiny.right.radius {:on-click (fn [_]
                                                   (common/push-state)
                                                   (om/update! state :group {:new true}))} "Create group"])))
