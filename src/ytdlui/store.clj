(ns ytdlui.store
  (:require [clojure.java.jdbc :as jdbc])
  (:import [org.sqlite SQLiteException SQLiteErrorCode]))

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

(defn job-exception! [db timestamp job-id exception]
  (jdbc/execute! db ["UPDATE job SET
                     status = 'error',
                     updated_at = ?,
                     exception = ?
                     WHERE job_id = ?" timestamp exception job-id]))

(defn list-jobs [db]
  (jdbc/query db "SELECT * FROM job ORDER BY created_at DESC"))

(defn get-job [db job-id]
  (first (jdbc/query db ["SELECT * FROM job WHERE job_id = ?" job-id])))

(defn working? [db]
  (pos? (second (first (jdbc/query db "SELECT EXISTS (SELECT job_id FROM job WHERE status IN ('pending', 'running'))" {:result-set-fn first})))))
