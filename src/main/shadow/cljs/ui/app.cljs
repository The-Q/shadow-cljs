(ns shadow.cljs.ui.app
  (:require
    ["react-dom" :as rdom]
    ["react" :as react]
    [cljs.core.async :as async :refer (go)]
    [fulcro.client :as fc]
    [fulcro.client.primitives :as fp :refer (defsc)]
    [fulcro.client.data-fetch :as fdf]
    [fulcro.client.network :as fnet]
    [shadow.cljs.model :as m]
    [shadow.cljs.ui.model :as ui-model]
    [shadow.markup.react :as html :refer ($ defstyled)]
    [shadow.cljs.ui.transactions :as tx]
    [shadow.cljs.ui.fulcro-mods :as fm]
    [shadow.cljs.ui.style :as s]
    [shadow.cljs.ui.env :as env]
    [shadow.cljs.ui.websocket :as ws]
    [shadow.cljs.ui.util :as util]
    [shadow.cljs.ui.routing :as routing]
    [shadow.cljs.ui.pages.loading :as page-loading]
    [shadow.cljs.ui.pages.dashboard :as page-dashboard]
    [shadow.cljs.ui.pages.build :as page-build])
  (:import [goog.history Html5History]))

(defsc MainNavBuild [this props {:keys [selected]}]
  {:ident
   (fn []
     [::m/build-id (::m/build-id props)])
   :query
   (fn []
     [::m/build-id
      ::m/build-worker-active])}

  (let [{::m/keys [build-id build-worker-active]} props]
    (when build-id
      (s/nav-build-item {:key build-id}
        (s/nav-build-checkbox
          (html/input
            {:type "checkbox"
             :checked build-worker-active
             :onChange
             (fn [e]
               (fp/transact! this [(if (.. e -target -checked)
                                     (tx/build-watch-start {:build-id build-id})
                                     (tx/build-watch-stop {:build-id build-id}))]))}))

        (s/nav-build-link {:href (str "/builds/" (name build-id))} (name build-id))))))


(def ui-main-nav-build (fp/factory MainNavBuild {:keyfn ::m/build-id}))

(defsc MainNav [this props]
  {:query
   (fn []
     [{::ui-model/build-list (fp/get-query MainNavBuild)}
      [::ui-model/ws-connected '_]])

   :ident
   (fn []
     [::ui-model/globals ::ui-model/nav])

   :initial-state
   (fn [props]
     {::ui-model/build-list []})}

  (let [{::ui-model/keys [build-list]} props]
    (s/main-nav
      (s/main-nav-title
        (s/nav-link {:href "/dashboard"} "shadow-cljs"))

      (s/nav-item
        (s/nav-item-title
          (s/nav-link {:href "/repl"} "REPL")))

      (s/nav-item
        (s/nav-item-title
          (s/nav-link {:href "/dashboard"} "Builds"))
        (s/nav-sub-items
          (html/for [{::m/keys [build-id] :as build} build-list]
            (ui-main-nav-build build))))

      (s/nav-fill {})
      (s/nav-item
        (html/div (if (::ui-model/ws-connected props) "✔" "WS DISCONNECTED!"))))))

(def ui-main-nav (fp/factory MainNav {}))

(routing/register ::ui-model/root-router ::m/build-id
  {:class page-build/BuildOverview
   :factory page-build/ui-build-overview
   :keyfn ::m/build-id})

(routing/register ::ui-model/root-router ::ui-model/page-dashboard
  {:class page-dashboard/Page
   :factory page-dashboard/ui-page})

(routing/register ::ui-model/root-router ::ui-model/page-loading
  {:class page-loading/Page
   :factory page-loading/ui-page
   :default true})

(defsc Root [this {::ui-model/keys [main-nav builds-loaded] :as props}]
  {:initial-state
   (fn [p]
     {::ui-model/root-router {}
      ::ui-model/main-nav (fp/get-initial-state MainNav {})
      ::ui-model/builds-loaded false})

   :query
   [::ui-model/builds-loaded
    {::ui-model/root-router (routing/get-query ::ui-model/root-router)}
    {::ui-model/main-nav (fp/get-query MainNav)}]}

  (if-not builds-loaded
    (html/div "Loading ...")

    (s/page-container
      (ui-main-nav main-nav)
      (routing/render ::ui-model/root-router this props))))

(fm/handle-mutation tx/select-build
  (fn [state {:keys [ref] :as env} {:keys [build-id] :as params}]
    (-> state
        (assoc-in [::ui-model/globals ::ui-model/nav ::ui-model/active-build] build-id))))

(fm/handle-mutation tx/ws-open
  (fn [state env params]
    (assoc state ::ui-model/ws-connected true)))

(fm/handle-mutation tx/ws-close
  (fn [state env params]
    (assoc state ::ui-model/ws-connected false)))

(fm/handle-mutation tx/build-watch-start
  {:remote-returning page-build/BuildOverview})

(fm/handle-mutation tx/build-watch-stop
  {:remote-returning page-build/BuildOverview})

(fm/handle-mutation tx/build-watch-compile
  {:remote-returning page-build/BuildOverview})

(fm/handle-mutation tx/build-compile
  {:remote-returning page-build/BuildOverview})

(fm/handle-mutation tx/build-release
  {:remote-returning page-build/BuildOverview})

(defn update-worker-active [state env {:keys [op build-id] :as params}]
  (case op
    :worker-start
    (assoc-in state [::m/build-id build-id ::m/build-worker-active] true)
    :worker-stop
    (assoc-in state [::m/build-id build-id ::m/build-worker-active] false)
    ))

(defn update-dashboard [state env params]
  (let [active-builds
        (->> (::m/build-id state)
             (vals)
             (filter ::m/build-worker-active)
             (sort-by ::m/build-id)
             (map #(vector ::m/build-id (::m/build-id %)))
             (into []))]
    (assoc-in state [::ui-model/page-dashboard 1 ::ui-model/active-builds] active-builds)))

(fm/handle-mutation tx/builds-loaded
  [(fn [state env params]
     (assoc state ::ui-model/builds-loaded true))
   update-dashboard])

(fm/handle-mutation tx/process-supervisor
  [update-worker-active
   update-dashboard])

(fm/handle-mutation tx/process-repl-input
  (fn [state {:keys [ref] :as env} {:keys [text] :as params}]
    (update-in state (conj ref ::ui-model/repl-history) util/conj-vec text)
    ))

(fm/handle-mutation tx/process-worker-broadcast
  (fn [state env {:keys [build-id type] :as params}]
    (case type
      :build-status
      (assoc-in state [::m/build-id build-id ::m/build-status] (:state params))
      ;; ignore
      state)))

(defn start
  {:dev/after-load true}
  []
  (reset! env/app-ref (fc/mount @env/app-ref Root "root")))

(defn stop [])

(defn ^:export init []
  (let [ws-in
        (-> (async/sliding-buffer 10)
            (async/chan (map util/transit-read)))

        ws-out
        (async/chan 10 (map util/transit-str))

        history
        (doto (Html5History.)
          (.setPathPrefix "/")
          (.setUseFragment false))

        app
        (fc/new-fulcro-client
          :networking
          (fnet/make-fulcro-network "/api/graph"
            :global-error-callback
            (fn [& args] (js/console.warn "GLOBAL ERROR" args)))

          :started-callback
          (fn [{:keys [reconciler] :as app}]
            (ws/open reconciler ws-in ws-out)

            (routing/setup-history reconciler history)

            (async/put! ws-out
              {::m/op ::m/subscribe
               ::m/topic ::m/supervisor})

            (async/put! ws-out
              {::m/op ::m/subscribe
               ::m/topic ::m/worker-broadcast})

            (fdf/load app ::m/http-servers page-dashboard/HttpServer
              {:target [::ui-model/page-dashboard 1 ::ui-model/http-servers]})

            (fdf/load app ::m/build-configs page-build/BuildOverview
              {:target [::ui-model/globals ::ui-model/nav ::ui-model/build-list]
               :post-mutation `tx/builds-loaded
               :refresh [::ui-model/builds-loaded]}))

          :initial-state
          (fp/get-initial-state Root {})

          :reconciler-options
          {:shared
           {::env/ws-in ws-in
            ::env/ws-out ws-out
            ::env/history history}

           ;; the defaults access js/ReactDOM global which we don't have/want
           :root-render
           #(rdom/render %1 %2)

           :root-unmount
           #(rdom/unmountComponentAtNode %)})]

    (reset! env/app-ref app)
    (start)))