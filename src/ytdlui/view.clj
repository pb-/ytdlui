(ns ytdlui.view
  (:require [clojure.string :as string]
            [hiccup.page :refer [html5]]
            [hiccup.util :refer [escape-html]]))

(def ^:private status-color
  {"pending" "gray"
   "done" "mediumseagreen"
   "error" "indianred"
   "running" "darkslateblue"})

(defn svg-path [path-def]
  [:path {:d path-def
          :stroke "currentColor"
          :stroke-linecap "round"
          :stroke-linejoin "round"}])

(defn svg-icon [& content]
  (vec
    (concat
      [:svg
       {:width 24
        :height 24
        :stroke-width 1.5
        :viewBox "0 0 24 24"
        :fill "none"
        :xmlns "http://www.w3.org/2000/svg"}]
      content)))

(def svg-icon-download
  (svg-icon (svg-path "M6 20L18 20")
            (svg-path "M12 4V16M12 16L15.5 12.5M12 16L8.5 12.5")))

(def svg-icon-cancel
  (svg-icon
    (svg-path "M6.75827 17.2426L12.0009 12M17.2435 6.75736L12.0009 12M12.0009 12L6.75827 6.75736M12.0009 12L17.2435 17.2426")))

(def svg-icon-check
  (svg-icon (svg-path "M5 13L9 17L19 7")))

(def svg-icon-cloud-download
  (svg-icon
    (svg-path "M12 13V22M12 22L15.5 18.5M12 22L8.5 18.5")
    (svg-path "M20 17.6073C21.4937 17.0221 23 15.6889 23 13C23 9 19.6667 8 18 8C18 6 18 2 12 2C6 2 6 6 6 8C4.33333 8 1 9 1 13C1 15.6889 2.50628 17.0221 4 17.6073")))

(def svg-icon-hourglass
  (svg-icon
    (svg-path "M12 12C15.3137 12 18 9.31371 18 6H6C6 9.31371 8.68629 12 12 12ZM12 12C15.3137 12 18 14.6863 18 18H6C6 14.6863 8.68629 12 12 12Z")
    (svg-path "M6 3L12 3L18 3")
    (svg-path "M6 21H12L18 21")))

(def svg-icon-page-flip
  (svg-icon
    (svg-path "M12 11H14.5H17")
    (svg-path "M12 7H14.5H17")
    (svg-path "M8 15V3.6C8 3.26863 8.26863 3 8.6 3H20.4C20.7314 3 21 3.26863 21 3.6V17C21 19.2091 19.2091 21 17 21V21")
    (svg-path "M5 15H8H12.4C12.7314 15 13.0031 15.2668 13.0298 15.5971C13.1526 17.1147 13.7812 21 17 21H8H6C4.34315 21 3 19.6569 3 18V17C3 15.8954 3.89543 15 5 15Z")))

(def ^:private status-icon
  {"pending" svg-icon-hourglass
   "done" svg-icon-check
   "error" svg-icon-cancel
   "running" svg-icon-download})

(defn page [body version refresh?]
  (html5
    [:head
     [:title "Get 🎶︎"]
     [:link {:rel "stylesheet" :href (format "/assets/local.css?v=%s" version)}]
     (when refresh?
       [:meta {:http-equiv "refresh"
               :content "2"}])
     [:meta {:name "viewport"
             :content "width=device-width, initial-scale=1"}]]
    [:body body
     [:p.footer
      "Running " [:a {:href "https://github.com/pb-/ytdlui"} "ytdlui"] " " version]]))

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
   [:p.hint "Enter a YouTube/SoundCloud/… URL to extract the audio track from it."]
   [:form
    {:method "post" :action ""}
    [:div.input-form
     [:input.query {:type "text"
                    :placeholder "YouTube/SoundCloud/… URL"
                    :name "url"}]
     [:input {:type "submit" :value "Get 🎶︎"}]]]
   [:div
    (for [job jobs]
      [:div.job-container
       [:div.job-head
        [:div.job-status-icon
         {:style (format "background-color: %s;" (status-color (:status job)))
          :title (string/capitalize (:status job))}
         (status-icon (:status job))]
        [:div [:a {:href (:url job)} (or (:title job) (:url job))]]]
       [:div.job-actions
        (when (or (:stdout job) (:stderr job) (:exception job))
          [:a
           {:href (format "/job/%d/logs" (:job_id job))}
           [:div.job-action svg-icon-page-flip "Logs"]])
        (when (#{"done"} (:status job))
          [:a
           {:href (format "/job/%d/download/%s" (:job_id job) (escape-html (:filename job)))}
           [:div.job-action svg-icon-download "Download"]])]])]])
