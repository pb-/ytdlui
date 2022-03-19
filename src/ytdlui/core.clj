(ns ytdlui.core
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.java.shell :refer [sh]]
            [clojure.java.jdbc :as jdbc]
            [ring.adapter.jetty :refer [run-jetty]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.resource :refer [wrap-resource]]
            [ring.middleware.content-type :refer [wrap-content-type]]
            [ring.middleware.not-modified :refer [wrap-not-modified]]
            [compojure.core :refer [GET POST defroutes]]
            [hiccup.page :refer [html5]]
            [hiccup.util :refer [escape-html]]
            [ytdlui.store :as store]))

(def storage-path (System/getenv "STORAGE_PATH"))
(def db-path (str storage-path "/db"))
(def downloads-path (str storage-path "/downloads"))

(def db-spec {:classname "org.sqlite.JDBC"
              :subprotocol "sqlite"
              :subname db-path})

(def status-color {"pending" "gray"
                   "done" "mediumseagreen"
                   "error" "indianred"
                   "running" "darkslateblue"})

(def status-icon {"pending" "hourglass"
                  "done" "check"
                  "error" "cancel"
                  "running" "cloud-download"})

(defn now []
  (quot (System/currentTimeMillis) 1000))

(defn url-encode' [s]
  (string/join
    (for [ub (.getBytes s)
          :let [b (if (neg? ub) (+ 256 ub) ub)]]
      (if (or (<= 48 b 57)
              (<= 65 b 90)
              (<= 97 b 122))
        (char b)
        (format "%%%02X" b)))))

(defn metadata [log]
  (let [lines (string/split log #"\n")]
    {:title (some #(when (.startsWith % "title: ") (subs % 7)) lines)
     :filename (last lines)}))

(defn download! [url path]
  (let [result (sh "bash" "-s" "-" url path :in (slurp (io/resource "dl.sh")))]
    (if (zero? (:exit result))
      (merge result (metadata (:out result)))
      result)))

(defn not-found [& request]
  {:status 404
   :body "Not here"})

(defn wrap-html-content [handler]
  (fn [request]
    {:status 200
     :headers {"content-type" "text/html; charset=utf-8"}
     :body (html5
             [:head
              [:link {:rel "stylesheet" :href "/assets/pure-min.css"}]
              [:link {:rel "stylesheet" :href "/assets/local.css"}]
              (when (store/working? (:db request))
                [:meta {:http-equiv "refresh"
                        :content "2"}])
              [:meta {:name "viewport"
                      :content "width=device-width, initial-scale=1"}]]
             [:body
              {:style "max-width: 30em; padding: 0.75em; margin: auto;"}
              (handler request)])}))

(defn logs [request]
  (let [job (store/get-job (:db request) (get-in request [:params :job-id]))]
    [:div
     (when (seq (:stderr job))
       [:div
        [:h2 "stderr"]
        [:pre
         {:style "overflow-x: auto; color: firebrick; background-color: mistyrose; padding: .5em;"}
         (:stderr job)]])
     [:div
      [:h2 "stdout"]
      [:pre
       {:style "overflow-x: auto; background-color: whitesmoke; padding: .5em;"}
       (:stdout job)]]]))

(defn home [request]
  [:div
   [:form.pure-form
    {:method "post"
     :action ""}
    [:div
     {:style "display: flex; gap: .5em; align-items: center; justify-content: center; margin-bottom: 1em;"}
     [:input {:type "text"
              :style "margin: 0;"
              :placeholder "YouTube/SoundCloud/… URL"
              :name "url"}]
     [:input.pure-button.pure-button-primary
      {:type "submit"
       :value "Get"}]]]
   [:div
    (for [job (store/list-jobs (:db request))]
      [:div
       {:style "padding: 1em 0"}
       [:div
        {:style "display: flex; align-items: flex-start; gap: 1em;"}
        [:div
         {:style (format "background-color: %s; padding: 0.5em; border-radius: 3em;" (status-color (:status job)))}
         [:img {:style "vertical-align: middle;"
                :title (string/capitalize (:status job))
                :src (str "assets/icons/" (status-icon (:status job)) ".svg")}]]
        [:div [:a {:href (:url job)} (or (:title job) (:url job))]]]
       [:div
        {:style "display: flex; gap: 1.5em; justify-content: flex-end; margin-top: .75em;"}
        (when (or (:stdout job) (:stderr job))
          [:a
           {:href (str "/job/" (:job_id job) "/logs")}
           [:div
            {:style "display: flex; align-items: center; gap: .2em;"}
            [:img {:src "assets/icons/page-flip.svg"}] "Logs"]])
        (when (#{"done"} (:status job))
          [:a
           {:href (str "/job/" (:job_id job) "/download/" (escape-html (:filename job)))} 
           [:div
            {:style "display: flex; align-items: center; gap: .2em"}
            [:img {:src "assets/icons/download.svg"}] "Download"]])]])]])

(defn wrap-db [handler]
  (fn [request]
    (jdbc/with-db-connection [db db-spec]
      (handler (assoc request :db db)))))

(defn download-local [request]
  (let [job (store/get-job (:db request) (get-in request [:params :job-id]))]
    (if (and job (#{"done"} (:status job)))
      (let [file (io/file (str downloads-path "/" (:filename job)))]
        {:status 200
         :headers {"content-type" "application/octet-stream"
                   "content-length" (str (.length file))
                   "content-disposition" (str "attachment; filename*=UTF-8''" (url-encode' (:filename job)))}
         :body file})
      (not-found))))

(defn enqueue [request]
  (let [url (get-in request [:form-params "url"])]
    (when (re-matches #"https://.*" url)
      (store/enqueue-job! (:db request) url (now)))
    {:status 302
     :headers {"location" "/"}}))

(defroutes routes
  (GET "/" [] (wrap-html-content home))
  (POST "/" [] enqueue)
  (GET ["/job/:job-id/download/:display-name" :job-id #"\d+" :display-name #".*"] [] download-local)
  (GET ["/job/:job-id/logs" :job-id #"\d+"] [] (wrap-html-content logs))
  not-found)

(def app
  (-> routes
      (wrap-resource "public")
      wrap-content-type
      wrap-not-modified
      wrap-db
      wrap-params))

(defn run-job [db job]
  (let [result (download! (:url job) downloads-path)]
    (if (zero? (:exit result))
      (store/job-success!
        db (now) (:job_id job) (:out result) (:title result) (:filename result))
      (store/job-failure!
        db (now) (:job_id job) (:out result) (:err result)))))

(defn worker []
  (jdbc/with-db-connection [db db-spec]
    (while true
      (if-let [job (store/claim-job! db (now))]
        (run-job db job)
        (Thread/sleep 3000)))))

(defn start-worker []
  (.start (Thread. worker "worker thread")))

(defn -main []
  (start-worker)
  (run-jetty app {:port 8080}))

(comment
  ;; evaluate this to start the development server
  (start-worker)
  (do
    (require '[ring.middleware.reload :refer [wrap-reload]])
    (run-jetty (wrap-reload #'app) {:port 4711 :join? false})))
