(ns ldap-shepherd.util
  (:require [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]
            [clojure.string :as string]
            [jayq.core :refer [$]]
            [inflections.core]))

(defn html-dangerously [dom-fn attr html-text]
  "Show raw html-text in the specified dom function, using the supplied attribute map"
  (dom-fn (clj->js (merge attr {:dangerouslySetInnerHTML #js {:__html html-text}}))))


(defn icon [name & css-classes]
  "Show the named icon from the open iconic font set."
  (html-dangerously dom/svg {:viewBox "0 0 8 8" :className (string/join " " (conj css-classes "icon"))}
                    (str "<use xlink:href=\"/open-iconic.svg#" name "\"" " class=\"" name " alt=\"" name "\"></use>")))

;; TODO: replace by (map first (partition-by identity coll))
(defn collapse-same
  ([coll]
     "Answer a lazy seq with ranges of same value items in coll collapsed into one.
      (collapse-same [1 1 2 3 3 1 1]) => (1 2 3 1).
      Allows nil value items in the seq."
     (if (seq coll)
       (collapse-same coll (first coll))
       (empty coll)))
  ([coll v]
     (lazy-seq
      (if (and (seq coll) (= (first coll) v))
        (collapse-same (rest coll) v)
        (cons v (collapse-same coll))))))

;;
;; Wrap the specified react component ("owner") into a reveal modal with the specified id
;; Reveal elements are not managed by react, because they are manipulated by the reveal javascript which
;; React doesn't like.
;; component is the the result of an om/build invocation (but is *not* the same as the "owner" argument in
;; an om/build invocation.
(defn- render-to-element-with-id [component id]
  (let [element (. js/document (getElementById id))]
    (js/React.render component element)))

(defn reveal-modal [app owner {:keys [reveal-id inner-owner] :as opts}]
  (reify
    om/IRender
    (render [_]
      (html-dangerously dom/div {} (str "<div id='" reveal-id "' class='reveal-modal' data-reveal></div>")))
    om/IDidMount
    (did-mount [this]
      (render-to-element-with-id inner-owner reveal-id))
    om/IDidUpdate
    (did-update [this prev-props prev-state]
      (render-to-element-with-id inner-owner reveal-id))))

(defn do-reveal [id op]
  "Do some operation on the specified reveal modal identified by id. Op can be :open or :close "
  (.foundation ($ (str "#" id)) "reveal" (name op)))

(defn error-message [errors k]
  "Render an error message if k is present in the errors map."
  (when (k errors)
    (dom/small #js {:className "error"} (first (k errors)))))

(defn dialog-buttons [ok-label ok-fn cancel-fn]
  (dom/div #js {:className "row"}
           (dom/div #js {:className "large-2 columns"} 
                    (dom/a #js {:className "button round" :href "#" 
                                :onClick ok-fn}
                           ok-label))
           (dom/div #js {:className "large-2 columns end"} 
                    (dom/a #js {:className "" :href "#" 
                                :onClick cancel-fn}
                           "Cancel"))))

(defn display-count [count singular-label]
  (str count " " (if (= count 1) singular-label (inflections.core/plural singular-label))))
