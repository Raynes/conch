(ns conch.sh
  (require [conch.core :as conch]
           [clojure.java.io :as io]
           [clojure.string :as string]
           [useful.seq :as seq]))

(defprotocol FeedIn
  (feed [this]))

(defn char-seq [reader]
  (map char (take-while #(not= % -1) (repeatedly #(.read reader)))))

(defn buffer-stream [stream buffer]
  (let [reader (io/reader stream)]
    (cond
     (= :none buffer) (char-seq reader)
     (number? buffer) (map string/join (partition buffer (char-seq reader)))
     :else (line-seq reader))))

(defprotocol Redirectable
  (redirect [this buffer k proc]))

(extend-type java.io.File
  Redirectable
  (redirect [f buffer k proc]
    (conch/stream-to proc k f)))

(extend-type clojure.lang.IFn
  Redirectable
  (redirect [f buffer k proc]
    (.start
     (Thread.
      #(doseq [buffer (buffer-stream (k proc) buffer)]
         (f buffer proc))))))

(defn output [proc k options]
  (let [seqify (:seq options)]
    (if (or (= seqify k)
            (= seqify :out k)
            (and (true? seqify) (= k :out)))
      (buffer-stream (k proc) (:buffer options))
      (let [result (conch/stream-to-string proc k)]
        (when-not (empty? result) result)))))

(defn add-proc-args [args options]
  (if (seq options)
    (apply concat args
           (select-keys options
                        [:redirect-err
                         :env
                         :dir]))
    args))

(defn run-command [name args options]
  (let [proc (apply conch/proc name (add-proc-args args options))
        {:keys [buffer out in err timeout verbose]} options]
    (when in  (conch/feed-from-string proc (:in proc)))
    (when out (redirect out buffer :out proc))
    (when err (redirect err buffer :err proc))
    (let [exit-code (if timeout
                      (conch/exit-code proc timeout)
                      (conch/exit-code proc))]
      (if (= :timeout exit-code)
        (if verbose
          {:proc proc
           :exit-code :timeout}
          :timeout)
        (let [proc-out (when-not out (output proc :out options))
              proc-err (when-not err (output proc :err options))]
          (cond
           verbose {:proc proc
                    :exit-code exit-code
                    :stdout proc-out
                    :stderr proc-err}
           (= (:seq options) :err) proc-err
           :else proc-out))))))

(defn execute [name & args]
  (let [end (last args)
        options (when (map? end) end)
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

