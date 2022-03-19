(ns ytdlui.core
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.java.shell :refer [sh]]
            [clojure.java.jdbc :as jdbc]
            [clojure.stacktrace :refer [print-stack-trace]]
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

(def rfc-3986-unreserved "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~")

(def version (slurp (io/resource "version")))

(defn now []
  (quot (System/currentTimeMillis) 1000))

(defn url-encode [s]
  (let [unreserved (set (map int rfc-3986-unreserved))]
    (string/join
      (for [ub (.getBytes s)
            :let [b (bit-and ub 0xff)]]
        (if (unreserved b)
          (char b)
          (format "%%%02X" b))))))

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
              [:link {:rel "stylesheet" :href (format "/assets/pure-min.css?v=%s" version)}]
              [:link {:rel "stylesheet" :href (format "/assets/local.css?v=%s" version)}]
              (when (store/working? (:db request))
                [:meta {:http-equiv "refresh"
                        :content "2"}])
              [:meta {:name "viewport"
                      :content "width=device-width, initial-scale=1"}]]
             [:body (handler request)]
             [:p.footer
              "Running " [:a {:href "https://github.com/pb-/ytdlui"
                              :style "text-decoration: underline;"} "ytdlui"] " " version])}))

(defn logs [request]
  (let [job (store/get-job (:db request) (get-in request [:params :job-id]))]
    [:div
     (when (seq (:exception job))
       [:div
        [:h2 "Stack trace"]
        [:pre.error (:exception job)]])
     (when (seq (:stderr job))
       [:div
        [:h2 "stderr"]
        [:pre.error (:stderr job)]])
     (when (seq (:stdout job))
       [:div
        [:h2 "stdout"]
        [:pre (:stdout job)]])]))

(defn home [request]
  [:div
   [:form.pure-form
    {:method "post" :action ""}
    [:div.input-form
     [:input.query {:type "text"
                    :placeholder "YouTube/SoundCloud/… URL"
                    :name "url"}]
     [:input.pure-button.pure-button-primary {:type "submit" :value "Get"}]]]
   [:div
    (for [job (store/list-jobs (:db request))]
      [:div.job-container
       [:div.job-head
        [:div.job-status-icon
         {:style (format "background-color: %s;" (status-color (:status job)))}
         [:img.job-status-img {:title (string/capitalize (:status job))
                               :src (format "/assets/icons/%s.svg" (status-icon (:status job)))}]]
        [:div [:a {:href (:url job)} (or (:title job) (:url job))]]]
       [:div.job-actions
        (when (or (:stdout job) (:stderr job) (:exception job))
          [:a
           {:href (format "/job/%d/logs" (:job_id job))}
           [:div.job-action
            [:img {:src "assets/icons/page-flip.svg"}] "Logs"]])
        (when (#{"done"} (:status job))
          [:a
           {:href (format "/job/%d/download/%s" (:job_id job) (escape-html (:filename job)))} 
           [:div.job-action
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
                   "content-disposition" (str "attachment; filename*=UTF-8''" (url-encode (:filename job)))}
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
  (try
    (let [result (download! (:url job) downloads-path)]
      (if (zero? (:exit result))
        (store/job-success!
          db (now) (:job_id job) (:out result) (:title result) (:filename result))
        (store/job-failure!
          db (now) (:job_id job) (:out result) (:err result))))
    (catch Throwable tr
      (store/job-exception!
        db (now) (:job_id job) (with-out-str (print-stack-trace tr))))))

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
