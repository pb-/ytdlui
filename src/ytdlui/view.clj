(ns ytdlui.view
  (:require [clojure.string :as string]
            [hiccup.page :refer [html5]]
            [hiccup.util :refer [escape-html]]))

(def ^:private status-color
  {"pending" "gray"
   "done" "mediumseagreen"
   "error" "indianred"
   "running" "darkslateblue"})

(def ^:private status-icon
  {"pending" "hourglass"
   "done" "check"
   "error" "cancel"
   "running" "cloud-download"})

(defn page [body version refresh?]
  (html5
    [:head
     [:link {:rel "stylesheet" :href (format "/assets/pure-min.css?v=%s" version)}]
     [:link {:rel "stylesheet" :href (format "/assets/local.css?v=%s" version)}]
     (when refresh?
       [:meta {:http-equiv "refresh"
               :content "2"}])
     [:meta {:name "viewport"
             :content "width=device-width, initial-scale=1"}]]
    [:body body]
    [:p.footer
     "Running " [:a {:href "https://github.com/pb-/ytdlui"
                     :style "text-decoration: underline;"} "ytdlui"] " " version]))

(defn logs [job]
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
      [:pre (:stdout job)]])])

(defn home [jobs]
  [:div
   [:form.pure-form
    {:method "post" :action ""}
    [:div.input-form
     [:input.query {:type "text"
                    :placeholder "YouTube/SoundCloud/… URL"
                    :name "url"}]
     [:input.pure-button.pure-button-primary {:type "submit" :value "Get"}]]]
   [:div
    (for [job jobs]
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
