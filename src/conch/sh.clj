(ns conch.sh
  (require [conch.core :as conch]
           [clojure.java.io :as io]
           [clojure.string :as string]
           [useful.seq :as seq]))

(defn char-seq [reader]
  (map char (take-while #(not= % -1) (repeatedly #(.read reader)))))

(defn buffer-stream [stream buffer]
  (let [reader (io/reader stream)]
    (cond
     (= :none buffer) (char-seq reader)
     (number? buffer) (map string/join (partition buffer (char-seq reader)))
     :else (line-seq reader))))

(defn callback [f buffer stream proc]
  (.start
   (Thread.
    #(doseq [buffer (buffer-stream (stream proc) buffer)]
       (f buffer proc)))))

(defn output [proc k options]
  (if (:seq options)
    (buffer-stream (k proc) (:buffer options))
    (conch/stream-to-string proc k)))

(defn run-command [name args options]
  (let [proc (apply conch/proc name args)
        {:keys [buffer out in err]} options]
    (when in  (conch/feed-from-string proc (:in proc)))
    (when out (callback out buffer :out proc))
    (when err (callback err buffer :err proc))
    (let [proc-out (when-not out (output proc :out options))
          proc-err (when-not err (output proc :err options))]
      (if (:verbose options)
        {:proc proc
         :exit-code (conch/exit-code proc)
         :stdout proc-out
         :stderr proc-err}
        proc-out))))

(defn execute [name & args]
  (let [end (last args)
        options (and (map? end) end)
        args (if options (drop-last args) args)]
    (if (:background options)
      (future (run-command name args options))
      (run-command name args options))))

(defmacro programs
  "Creates functions corresponding to progams on the PATH, named by names."
  [& names]
  `(do ~@(for [name names]
           `(defn ~name [& ~'args]
              (apply execute ~(str name) ~'args)))))

(defn- program-form [prog]
  `(fn [& args#] (apply execute ~prog args#)))

(defmacro let-programs
  "Like let, but expects bindings to be symbols to strings of paths to
   programs."
  [bindings & body]
  `(let [~@(seq/map-nth #(program-form %) 1 2 bindings)]
     ~@body))

(defmacro with-programs
  "Like programs, but only binds names in the scope of the with-programs call."
  [programs & body]
  `(let [~@(interleave programs (map (comp program-form str) programs))]
     ~@body))

