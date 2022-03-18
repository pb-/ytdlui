(ns ytdlui.core
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.java.shell :refer [sh]]
            [clojure.java.jdbc :as jdbc]
            [ring.adapter.jetty :refer [run-jetty]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.resource :refer [wrap-resource]]
            [compojure.core :refer [GET POST defroutes context]]
            [hiccup.page :refer [html5]]
            [hiccup.util :refer [escape-html]])
  (:import [org.sqlite SQLiteException SQLiteErrorCode]))

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

(defn metadata [log]
  (let [lines (string/split log #"\n")]
    {:title (some #(when (.startsWith % "title: ") (subs % 7)) lines)
     :filename (last lines)}))

(defn download [url path]
  (let [result (sh "bash" "-s" "-" url path :in (slurp (io/resource "dl.sh")))]
    (if (zero? (:exit result))
      (merge result (metadata (:out result)))
      result)))

(defn enqueue-job! [db url timestamp]
  (try
    (jdbc/insert! db :job {:created_at timestamp
                           :url url
                           :status "pending"})
    (catch SQLiteException e
      (when (not= (.getResultCode e) SQLiteErrorCode/SQLITE_CONSTRAINT_UNIQUE)
        (throw e)))))

(defn claim-job! [db timestamp]
  (first
    (jdbc/query db ["UPDATE job SET
                    status = 'running',
                    attempts = attempts + 1,
                    updated_at = ?
                    WHERE job_id IN (
                    SELECT job_id FROM job WHERE status = 'pending' LIMIT 1)
                    RETURNING *" timestamp])))

(defn job-success! [db timestamp job-id stdout title filename]
  (jdbc/execute! db ["UPDATE job SET
                     status = 'done',
                     updated_at = ?,
                     stdout = ?,
                     title = ?,
                     filename = ?
                     WHERE job_id = ?" timestamp stdout title filename job-id]))

(defn job-failure! [db timestamp job-id stdout stderr]
  (jdbc/execute! db ["UPDATE job SET
                     status = 'error',
                     updated_at = ?,
                     stdout = ?,
                     stderr = ?
                     WHERE job_id = ?" timestamp stdout stderr job-id]))

(defn list-jobs [db]
  (jdbc/query db "SELECT * FROM job ORDER BY created_at DESC"))

(defn get-job [db job-id]
  (first (jdbc/query db ["SELECT * FROM job WHERE job_id = ?" job-id])))

(defn working? [db]
  (pos? (second (first (jdbc/query db "SELECT EXISTS (SELECT job_id FROM job WHERE status IN ('pending', 'running'))" {:result-set-fn first})))))

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
              (when (working? (:db request))
                [:meta {:http-equiv "refresh"
                        :content "2"}])
              [:meta {:name "viewport"
                      :content "width=device-width, initial-scale=1"}]]
             [:body
              {:style "max-width: 30em; padding: 0.75em; margin: auto;"}
              (handler request)])}))

(defn wrap-db [handler]
  (fn [request]
    (jdbc/with-db-connection [db db-spec]
      (handler (assoc request :db db)))))

(defn logs [request]
  (let [job (get-job (:db request) (get-in request [:params :job-id]))]
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

(defn download-local [request]
  (let [job (get-job (:db request) (get-in request [:params :job-id]))]
    (if (and job (#{"done"} (:status job)))
      {:status 200
       :headers {"content-type" "application/octet-stream"
                 "content-disposition" (str "attachment; filename=\"" (:filename job) "\"")}
       :body (io/file (str downloads-path "/" (:filename job)))}
      (not-found))))

(defn enqueue [request]
  (let [url (get-in request [:form-params "url"])]
    (when (re-matches #"https://.*" url)
      (enqueue-job! (:db request) url (now)))
    {:status 302
     :headers {"location" "/"}}))

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
    (for [job (list-jobs (:db request))]
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

(defroutes routes
  (GET "/" [] (wrap-html-content home))
  (POST "/" [] enqueue)
  (GET ["/job/:job-id/download/:display-name" :job-id #"\d+"] [] download-local)
  (GET ["/job/:job-id/logs" :job-id #"\d+"] [] (wrap-html-content logs))
  not-found)

(def app
  (-> routes
      (wrap-resource "public")
      wrap-db
      wrap-params))

(defn run-job [db job]
  (let [result (download (:url job) downloads-path)]
    (if (zero? (:exit result))
      (job-success! db (now) (:job_id job) (:out result) (:title result) (:filename result))
      (job-failure! db (now) (:job_id job) (:out result) (:err result)))))

(defn worker []
  (jdbc/with-db-connection [db db-spec]
    (while true
      (if-let [job (claim-job! db (now))]
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
