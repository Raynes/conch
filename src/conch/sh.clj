(ns conch.sh
  (require [conch.core :as conch]
           [clojure.java.io :as io]))

(defn run-command [name args options]
  (let [proc (apply conch/proc name args)]
    (when-let [in (:in options)] (conch/feed-from-string proc in))
    (if-let [callback (:out options)]
      (doseq [line (line-seq (io/reader (:out proc)))]
        (callback line proc))
      (conch/stream-to-string proc :out))))

(defn execute [name & args]
  (let [end (last args)
        options (and (map? end) end)
        args (if options (butlast args) args)]
    (if (or (:out options) (:background options))
      (future (run-command name args options))
      (run-command name args options))))

(defmacro programs
  "Creates functions corresponding to progams on the PATH, named by names."
  [& names]
  `(do ~@(for [name names]
           `(defn ~name [& ~'args] (apply execute ~(str name) ~'args)))))