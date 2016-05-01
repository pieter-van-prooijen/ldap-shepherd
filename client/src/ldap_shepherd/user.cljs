(ns ldap-shepherd.user
  (:require [ldap-shepherd.ldap :as ldap]
            [ldap-shepherd.group :as group] 
            [ldap-shepherd.common :as common]
            [ldap-shepherd.util :as util] 
            [ldap-shepherd.pagination :as pagination]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]
            [sablono.core :as html :refer-macros [html]]
            [cljs.core.async :refer [<! >! chan put! close!]]
            [clojure.string :as string]
            [validateur.validation :as vr]) 
  (:require-macros [cljs.core.async.macros :refer [go]]))

(def placeholder-user {:uid "Loading..." :full-name "" :email ""})

(defn load-users [state owner]
  (common/load-paged-list state owner ldap/get-users :users [placeholder-user]))

(defn reload-users [state owner]
  (om/update! state :page 0)
  (om/update! state :cookie nil)
  (load-users state owner))


(defn load-user [owner uid state]
  (common/invoke-ldap owner
                      #(ldap/get-user uid %1)
                      (fn [result]
                        (common/push-state)
                        (om/update! state :q "*")
                        ;; Make a copy of the current group when editing so adds/deletes can be tracked.
                        (om/update! state :user (assoc result :old-groups (:groups result))))))

(defn delete-user [owner uid]
  (common/invoke-ldap owner #(ldap/delete-user uid %1) #(common/show-message owner :info "User deleted.")))

(def delete-user-modal-id "delete-user-modal")
(defn delete-user-modal [state owner]
  (om/component
   (html [:div
          [:div.row 
           [:div.large-12.columns.bold
            [:h2 (str "Do you want to delete user " (:uid-to-delete state) " ?")]]]
          (util/dialog-buttons "Delete"
                            (fn [_] 
                              (delete-user owner (:uid-to-delete @state))
                              (util/do-reveal delete-user-modal-id :close)
                              (reload-users state owner))
                            (fn [_] (util/do-reveal delete-user-modal-id :close)))])))

(defn user-row [{:keys [uid displayName mail]} owner {:keys [user-selected-fn delete-selected-fn]}]
  (om/component
   (html [:tr
          [:td  uid]
          [:td [:a {:href "#" :on-click #(user-selected-fn uid)} displayName]]
          [:td mail]
          [:td [:a {:href "#" :on-click  #(delete-selected-fn uid)}
                (util/icon "delete" "delete")]]])))

(defn list-users [state owner options]
  (om/component
   (let [delete-user-modal-owner (om/build delete-user-modal state)]
     (html [:div.large-12.columns
            [:div.row
             [:div.large-8.columns 
              (om/build common/search-box state  {:opts {:update-fn #(reload-users state owner)}})]]
            [:div.row
             [:div.large-12.columns
              [:table {:role "grid" :class "users"}
               [:thead
                [:tr 
                 [:th "uid"] [:th "full name"] [:th "e-mail"] [:th ""]]]
               [:tbody
                (om/build-all user-row (:users state) 
                              {:key :uid ; use as a react key
                               :opts {:delete-selected-fn 
                                      (fn [uid]
                                        (util/do-reveal delete-user-modal-id :open)
                                        (om/update! state :uid-to-delete uid))
                                      :user-selected-fn
                                      (fn [uid] 
                                        (load-user owner uid state))}})]]
              (om/build common/pagination-row (select-keys state pagination/pagination-keys) 
                                          {:opts {:page-changed-fn (fn [page]
                                                                     (om/update! state :page page)
                                                                     (load-users state owner))
                                                  :label "users"}})]]
            (om/build util/reveal-modal state {:opts {:reveal-id delete-user-modal-id
                                                      :inner-owner delete-user-modal-owner}})]))))


(defn validate-user [user]
  (let [v (vr/validation-set
           (vr/format-of :uid :format common/id-format "UID can only contain alpha numerical characters and dots")
           (vr/presence-of :full-name)
           (vr/format-of :email :format #"[^@]+@[^@]+" :message "Email must have format  user@domain.name")
           (if (:new @user)
              (vr/presence-of :password)
              (vr/presence-of #{})))
        errors (v @user)]
    (om/update! user :errors errors)
    (empty? errors)))

(defn submit-user [user did-finish-fn state owner]
  (when (validate-user user)
    (common/invoke-ldap owner
                        (fn [c]
                          (if (:new @user)
                            (ldap/create-user @user c)
                            (ldap/update-user @user c))
                          (ldap/update-groups (:uid @user) (:groups @user) (:old-groups @user) c))
                        (fn [result]
                          (common/pop-state)
                          (common/show-message owner :info (if (:new @user) "Created user" "Updated user"))
                          (did-finish-fn true)))))

;; Set a value under k in user based on the current state of the input field targeted by event e.
(defn handle-change [user k e]
  (let [v (.-value (.-target e ))]
    (om/update! user k v)))

(defn password [length]
  "Generate a password from a set of dissimilar characters (don't use 0/O, 1/l etc..)"
  (apply str (map  #(rand-nth "23459abcdefghijkmnopqurstvwxyzABCDEFGHIJKLMNPQURSTVWXYZ") (range length))))
 
(defn edit-user-groups [{user :user :as state} owner]
  (om/component
   (html [:div.row
          [:div.large-3.columns
           [:label.inline.right "Groups:"]]
          [:div.large-3.columns
           [:ul
            (for [group (:groups user)]
              [:li
               [:span group " "]
               [:a {:on-click (fn [_] (om/transact! user :groups 
                                                    (fn [groups] 
                                                      (remove #(= % group) groups))))}
                (util/icon "delete" "delete")]])]]
          [:div.large-3.columns.end
           [:a {:on-click (fn [_] 
                            (group/reload-groups state owner)
                            (util/do-reveal group/reveal-id :open))}  "add group..."]]])))


(defn user-text-field [user field label enabled errors on-change]
  (let [field-name (name field)]
    [:div.row
     [:div.large-3.columns
      [:label.inline.right {:for field-name} label]]
     [:div.large-6.columns.end
      [:input {:type "text" :name field-name :id field-name :value (field user)
               :disabled (not enabled)
               :on-change (partial on-change field)}]
      (util/error-message errors field)]]))

   
(defn edit-user [{{errors :errors :as user} :user :as state} owner {:keys [did-finish-fn]}]
  (om/component
   (let [on-change (partial handle-change user)]
     (html [:div
            [:form
             (user-text-field user :uid "UID:" (:new user) errors on-change)
             (user-text-field user :full-name "Name:" true errors on-change)
             (user-text-field user :email "Email:" true errors on-change)
             [:div.row
              [:div.large-3.columns
               [:label.inline.right {:for "password"} "Set password:"]]
              [:div.large-3.columns
               [:input {:type "text" :name "password" :id "password"
                        :on-change (partial on-change :password)
                        :value (:password user)}]
               (util/error-message errors :password)]
              [:div.large-3.columns.end
               [:a {:href "#" :on-click (fn [_] (om/update! user :password (password 6)))} "generate"]]]

             (om/build edit-user-groups state)

             [:div.row
              [:div.large-2.columns.large-offset-3
               [:a.button.radius {:href "#" :on-click (fn [_]
                                                 (submit-user user did-finish-fn state owner))}
                (if (:new user) "Create" "Modify")]]
              [:div.large-2.columns.end
               [:a {:href "#" :on-click (fn [_] 
                                          (common/pop-state)
                                          (did-finish-fn false))} "Cancel"]]]]
            ;; Can't nest forms in react, so put the modal for groups outside.
            (om/build group/select-group state
                      {:opts {:group-selected-fn (fn [group]
                                                   (util/do-reveal group/reveal-id :close)
                                                   (om/transact! user :groups 
                                                                 (fn [groups]
                                                                   (conj groups group))))}})]))))

(defn create-user-button [state owner opts]
  (om/component
   (html [:a.button.tiny.right.radius {:on-click (fn [_]
                                                   (common/push-state)
                                                   (om/update! state :user {:new true}))} "Create user"])))
