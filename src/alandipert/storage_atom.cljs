(ns alandipert.storage-atom
  (:require [cognitect.transit :as t]
            [goog.Timer :as timer]
            [clojure.string :as string]))


(def transit-read-handlers (atom {}))

(def transit-write-handlers (atom {}))

(defn clj->json [x]
  (t/write (t/writer :json {:handlers @transit-write-handlers}) x))

(defn json->clj [x]
  (t/read (t/reader :json {:handlers @transit-read-handlers}) x))

(defprotocol IStorageBackend
  "Represents a storage resource."
  (-get [this not-found])
  (-commit! [this value] "Commit value to storage at location."))

(deftype StorageBackend [store key pre-clean-fn post-clean-fn]
  IStorageBackend
  (-get [_this not-found]
    (if-let [existing (.getItem store (clj->json key))]
      ((or post-clean-fn identity) (json->clj existing) nil)
      not-found))
  (-commit! [_this value]
    (.setItem store (clj->json key) (clj->json ((or pre-clean-fn identity) value)))))


(defn debounce-factory
  "Return a function that will always store a future call into the
  same atom. If recalled before the time is elapsed, the call is
  replaced without being executed." []
  (let [f (atom nil)]
    (fn [func ttime]
      (when @f
        (timer/clear @f))
      (reset! f (timer/callOnce func ttime)))))

(def storage-delay
  "Delay in ms before a change is committed to the local storage. If a
new change occurs before the time is elapsed, the old change is
discarded an only the new one is committed."
  (atom 10))

(def ^:dynamic *storage-delay* nil)

(def ^:dynamic *watch-active* true)
;; To prevent a save/load loop when changing the values quickly.

(defn store
  [atom backend]
  (let [existing (-get backend ::none)
        debounce (debounce-factory)]
    (if (= ::none existing)
      (-commit! backend @atom)
      (reset! atom existing))
    (doto atom
      (add-watch ::storage-watch
                 #(when (and *watch-active*
                             (not= %3 %4))
                    (debounce (fn [](-commit! backend %4))
                              (or *storage-delay*
                                  @storage-delay)))))))

(defn maybe-update-backend
  [atom storage k default post-clean-fn e]
  (when (identical? storage (.-storageArea e))
    (if (empty? (.-key e)) ;; is all storage is being cleared?
      (binding [*watch-active* false]
        (reset! atom default))
      (try
        (when-let [sk (json->clj (.-key e))]
          (when (= sk k) ;; is the stored key the one we are looking for?
            (binding [*watch-active* false]
              (reset! atom (let [value (.-newValue e)] ;; new value, or is key being removed?
                             (if-not (string/blank? value)
                               ((or post-clean-fn identity) (json->clj value) @atom)
                               default))))))
        (catch :default _e)))))

(defn link-storage
  [atom storage k post-clean-fn]
  (let [default @atom]
    (.addEventListener js/window "storage"
                       #(maybe-update-backend atom storage k default post-clean-fn %))))

(defn dispatch-remove-event!
  "Create and dispatch a synthetic StorageEvent. Expects key to be a string.
  An empty key indicates that all storage is being cleared."
  [storage key]
  (let [event (.createEvent js/document "StorageEvent")]
    (.initStorageEvent event "storage" false false key nil nil
                       (-> js/window .-location .-href)
                       storage)
    (.dispatchEvent js/window event)
    nil))

;;; mostly for tests

(defn load-html-storage
  [storage k]
  (-get (StorageBackend. storage k nil nil) nil))

(defn load-local-storage [k]
  (load-html-storage js/localStorage k))

(defn load-session-storage [k]
  (load-html-storage js/sessionStorage k))

;;; main API

(defn html-storage
  [atom storage k pre-clean-fn post-clean-fn]
  (link-storage atom storage k post-clean-fn)
  (store atom (StorageBackend. storage k pre-clean-fn post-clean-fn)))

(defn local-storage
  [atom k & [pre-clean-fn post-clean-fn]]
  (html-storage atom js/localStorage k pre-clean-fn post-clean-fn))

(defn session-storage
  [atom k & [pre-clean-fn post-clean-fn]]
  (html-storage atom js/sessionStorage k pre-clean-fn post-clean-fn))

;; Methods to safely remove items from storage or clear storage entirely.

(defn clear-html-storage!
  "Clear storage and also trigger an event on the current window
  so its atoms will be cleared as well."
  [storage]
  (.clear storage)
  (dispatch-remove-event! storage ""))

(defn clear-local-storage! []
  (clear-html-storage! js/localStorage))

(defn clear-session-storage! []
  (clear-html-storage! js/sessionStorage))

(defn remove-html-storage!
  "Remove key from storage and also trigger an event on the current
  window so its atoms will be cleared as well."
  [storage k]
  (let [key (clj->json k)]
    (.removeItem storage key)
    (dispatch-remove-event! storage key)))

(defn remove-local-storage! [k]
  (remove-html-storage! js/localStorage k))

(defn remove-session-storage! [k]
  (remove-html-storage! js/sessionStorage k))
