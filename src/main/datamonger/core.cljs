(ns datamonger.core
  (:require [clojure.core]
            [clojure.pprint]
            [clojure.string :as str]
            [datafrisk.core :as d]
            [goog.object :as gobj]
            [sci.core :as sci]
            ["react" :as react]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- explode-step [path v]
  (cond
    (map? v)
    (->> v
         (mapcat (fn [[mk mv]]
                   (explode-step (conj path mk) mv))))
    (vector? v)
    (->> v
         (map vector (range))
         (mapcat (fn [[idx vv]]
                   (explode-step (conj path [:VEC idx]) vv))))
    :else
    [(conj path v)]))

(defn explode [v]
  (explode-step [] v))

(defn assoc-vec [ve idx v]
  (assert (vector? ve))
  (let [{:keys [last-seen cur mapping]} (meta ve)
        new-idx (cond
                  (and (some? last-seen) (< idx last-seen))
                  (throw (ex-info "Increasing idx expected" {:idx idx
                                                             :last-seen last-seen}))
                  (not cur)
                  0
                  (= idx last-seen)
                  cur
                  :else
                  (inc cur))]
    (-> (assoc ve new-idx v)
        (with-meta {:last-seen idx
                    :cur new-idx
                    :mapping (assoc mapping idx new-idx)}))))

(defn patch [m k v]
  ;; FIXME: avoid collision
  (if (and (vector? k) (= 2 (count k)) (= :VEC (first k)))
    (assoc-vec (or m []) (second k) v)
    (assoc m k v)))

(defn patch-in
  [m [k & ks :as xxx] v]
  (if ks
    (if (and (vector? k) (= 2 (count k)) (= :VEC (first k)))
      (let [{:keys [mapping]} (meta m)]
        (patch m k (patch-in (if (get mapping (second k))
                               (get m (get mapping (second k)))
                               nil)
                             ks
                             v)))
      (patch m k (patch-in (get m k) ks v)))
    (patch m k v)))

(defn implode [cs]
  (->> cs
       (reduce (fn [acc v]
                 (patch-in acc (pop v) (peek v)))
               nil)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn transform-sci [code v]
  (try
    (if (str/blank? code)
      v
      (sci/eval-string code {:bindings {'% v}}))
    (catch :default e
      (js/console.error e)
      {:error e})))

(defn transform-flat [s v]
  (try
    (->> v
         explode
         (filter (fn [c]
                   (str/includes? (pr-str c) (or s ""))))
         implode)
    (catch :default e
      (js/console.error e)
      {:error e})))

(defn transform-ui [opts co transform transform-fn v]
  (let [!el (atom nil)
        ls-key (str (name transform) ":"(-> opts :pathname))
        [code set-code] (react/useState (js/localStorage.getItem ls-key))
        submit (fn [s]
                 (set-code s))]
    [:div
     [:div {:style {:width 600}}
      [:textarea {:ref (fn [el] (reset! !el el))
                  :style {:width 600 :height 200 :padding 6}
                  :default-value (or code "")
                  :on-change (fn [^js/Event e]
                               (js/localStorage.setItem ls-key
                                                        (-> e .-target .-value)))
                  :on-key-down (fn [^js/Event e]
                                 (when (and (= "Enter" (gobj/get e "key"))
                                            (or (gobj/get e "ctrlKey")
                                                (gobj/get e "metaKey")))
                                   (submit (-> e .-target .-value))
                                   (.preventDefault e)))}]
      [:a.click {:on-click (fn [] (submit (-> @!el .-value)))}
       "apply"]]
     [co (transform-fn code v)]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn preview-ui [v]
  (binding [clojure.core/*print-length* 3]
    [:div (pr-str v)]))

(defn pprint-ui [v]
  [:pre.pprint (with-out-str (clojure.pprint/pprint v))])

(defn interactive-ui [v]
  (d/DataFriskView v))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def the-modes
  {:preview preview-ui
   :pprint pprint-ui
   :interactive interactive-ui})

(def the-transforms
  {:flat transform-flat
   :sci transform-sci})

(defn view-ui [opts mode transform v]
  (let [co (or (the-modes mode) (throw "Unknown mode"))]
    ^{:key (name transform)}
    [transform-ui opts co transform (the-transforms transform) v]))

(defn menu-ui [{:keys [opts set-opts]} v]
  (let [mode (or (some-> opts :params :mode keyword)
                 (first (keys the-modes)))
        transform (or (some-> opts :params :transform keyword)
                      (first (keys the-transforms)))]
    [:div
     [:div.back [:a.click {:on-click (fn [] (set-opts {}))} "<< back"]]
     (->> (keys the-modes)
          (map (fn [k]
                 [:li.menu-item
                  [:a.click
                   {:class (when (= k mode) "selected")
                    :on-click
                    (fn [] (set-opts (fn [opts]
                                       (assoc-in opts [:params :mode] (name k)))))}
                   (name k)]]))
          (into [:ul.menu]))
     (->> (keys the-transforms)
          (map (fn [k]
                 [:li.menu-item
                  [:a.click
                   {:class (when (= k transform) "selected")
                    :on-click
                    (fn [] (set-opts (fn [opts]
                                       (assoc-in opts [:params :transform] (name k)))))}
                   (name k)]]))
          (into [:ul.menu]))
     [view-ui opts mode transform v]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn load+ [fname]
  (js/Promise.resolve
   (if-let [id (some-> (re-matches #"^/blob/(.*)$" fname) second)]
     (-> (js/localStorage.getItem id)
         js/JSON.parse
         (js->clj :keywordize-keys true))
     (-> (js/fetch (str "/examples/" fname))
         (.then (fn [r]
                  (.json r)))
         (.then (fn [r]
                  (js->clj r :keywordize-keys true)))))))

(defn url->opts [url]
  (let [[pathname search] (str/split url #"\?")
        params (js/URLSearchParams. (or search ""))]
    {:pathname pathname
     :params (->> params
                  (map (fn [[k v :as xxx]]
                         [(keyword k) v]))
                  (into {}))}))
(defn opts->url [{:keys [pathname params]}]
  (str (str (if (str/starts-with? (or pathname "") "/")
              nil
              "/")
            pathname)
       (when (seq params)
         (str "?" (->> params
                       (map (fn [[k v]]
                              (str (name k) "=" (str v))))
                       (str/join "&"))))))

(defn select-ui [{:keys [set-opts]}]
  (->> ["/widget.json" "/countries.json" "/package.json"]
       (map (fn [fname]
              [:div
               [:a.click
                {:on-click
                 (fn [] (set-opts (fn [opts] (assoc opts :pathname fname))))}
                fname]]))
       (into [:div])))

(defn load-ui [{:keys [opts] :as ctx}]
  (let [[v update-v] (react/useState nil)]
    (react/useEffect
     (fn []
       (-> (load+ (:pathname opts))
           (.then (fn [result]
                    (update-v result))))
       js/undefined)
     #js[])
    (if v
      [menu-ui ctx v]
      [:div])))

(defn main-ui []
  ;; FIXME: prefix with /app
  (let [[opts set-opts] (react/useState (url->opts (str js/location.pathname js/location.search)))
        ctx {:opts opts :set-opts set-opts}
        new-url (opts->url opts)]
    (react/useEffect (fn []
                       (js/history.pushState {} nil (str js/location.origin new-url))
                       js/undefined)
                     #js[new-url])
    (cond
      (and (seq (:pathname opts))
           (not= "/" (:pathname opts)))
      [load-ui ctx]
      :else
      [select-ui ctx])))

(defn init []
  (when (= "/json" js/location.pathname)
    (let [params (js/URLSearchParams. (-> js/location.hash
                                          (str/replace #"^#" "")))
          data (.get params "data")
          id (random-uuid)]
      (js/localStorage.setItem (str id) data)
      (js/history.pushState {} nil (str js/location.origin (str "/blob/" id))))))
