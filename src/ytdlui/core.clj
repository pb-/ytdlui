(ns ytdlui.core
  (:require [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [clojure.java.jdbc :as jdbc]
            [clojure.stacktrace :refer [print-stack-trace]]
            [clojure.string :as string]
            [ring.adapter.jetty :refer [run-jetty]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.resource :refer [wrap-resource]]
            [ring.middleware.content-type :refer [wrap-content-type]]
            [ring.middleware.not-modified :refer [wrap-not-modified]]
            [ring.middleware.gzip :refer [wrap-gzip]]
            [compojure.core :refer [GET POST defroutes]]
            [ytdlui.store :as store]
            [ytdlui.view :as view]))

(def storage-path (System/getenv "STORAGE_PATH"))
(def db-path (str storage-path "/db"))
(def downloads-path (str storage-path "/downloads"))

(def db-spec {:classname "org.sqlite.JDBC"
              :subprotocol "sqlite"
              :subname db-path})

(def archive-threshold-seconds
  (* 30 ;; days
     24 ;; hours
     60 ;; minutes
     60 ;; seconds
    ))

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

(defn archive! [db]
  (let [threshold (- (now) archive-threshold-seconds)
        jobs (store/list-archivable db threshold)]
    (doseq [job jobs]
      (io/delete-file (str downloads-path \/ (:filename job)) true))
    (store/archive! db (now) threshold)))

(defn not-found [& request]
  {:status 404
   :body "Not here"})

(defn wrap-html-content [handler]
  (fn [request]
    {:status 200
     :headers {"content-type" "text/html; charset=utf-8"}
     :body (view/page (handler request) version)}))

(defn logs [request]
  (view/logs (store/get-job (:db request) (get-in request [:params :job-id]))))

(defn home [request]
  (archive! (:db request))
  (view/home (store/list-jobs (:db request))))

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
    (when (and url (re-matches #"https://.*" url))
      (store/enqueue-job! (:db request) url (now)))
    {:status 302
     :headers {"location" "/"}}))

(defn retry [request]
  (when-let [job-id (get-in request [:form-params "job-id"])]
    (store/retry-job! (:db request) (now) job-id))
  {:status 302
   :headers {"location" "/"}})

(defn dispatch-mutation [request]
  (case (get-in request [:form-params "action"])
    "enqueue" enqueue
    "retry" retry
    not-found))

(defn job-fragment [request]
  (let [job (store/get-job (:db request) (get-in request [:params :job-id]))
        icon-visible? (= (get-in request [:params "icon-visible"]) "true")]
    (if job
      (view/fragment (view/job-container job icon-visible?))
      not-found)))

(defroutes routes
  (GET "/" [] (wrap-html-content home))
  (POST "/" [] dispatch-mutation)
  (GET ["/job/:job-id" :job-id #"\d+"] [] job-fragment)
  (GET ["/job/:job-id/download/:display-name" :job-id #"\d+" :display-name #".*"] [] download-local)
  (GET ["/job/:job-id/logs" :job-id #"\d+"] [] (wrap-html-content logs))
  not-found)

(def app
  (-> routes
      (wrap-resource "public")
      wrap-content-type
      wrap-not-modified
      wrap-db
      wrap-params
      wrap-gzip))

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
  (do
    (start-worker)
    (require '[ring.middleware.reload :refer [wrap-reload]])
    (run-jetty (wrap-reload #'app) {:port 4711 :join? false})))
