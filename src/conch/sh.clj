(ns conch.sh
  (:require [conch.core :as conch]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [useful.seq :as seq])
  (:import java.util.concurrent.LinkedBlockingQueue))

(defprotocol Redirectable
  (redirect [this options k proc]))

(extend-type java.io.File
  Redirectable
  (redirect [f options k proc]
    (conch/stream-to proc k f)))

(extend-type clojure.lang.IFn
  Redirectable
  (redirect [f options k proc]
    (future
      (doseq [buffer (get proc k)]
        (f buffer proc)))))

(defn seqify? [options k]
  (let [seqify (:seq options)]
    (or (= seqify k)
        (= seqify :out k)
        (and (true? seqify) (= k :out)))))

(extend-type nil
  Redirectable
  (redirect [_ options k proc]
    (let [seqify (:seq options)
          s (k proc)]
      (if
       (seqify? options k) s
       (string/join s)))))

(defn add-proc-args [args options]
  (if (seq options)
    (apply concat args
           (select-keys options
                        [:redirect-err
                         :env
                         :dir]))
    args))

(defn queue-seq [q]
  (lazy-seq
   (let [x (.take q)]
     (when-not (= x :eof)
       (cons x (queue-seq q))))))

(defmulti buffer (fn [kind _]
                   (if (number? kind)
                     :number
                     kind)))

(defmethod buffer :number [kind reader]
  #(try
     (let [buf (make-array Character/TYPE kind)]
       (when (pos? (.read reader buf))
         (apply str buf)))
     ;; and wave 'em like we just don't care
     (catch java.io.IOException _)))

(defmethod buffer :none [_ reader]
  #(try
     (let [c (.read reader)]
       (when (pos? c)
         (char c)))
     (catch java.io.IOException _)))

(defmethod buffer :line [_ reader]
  #(try
     (.readLine reader)
     (catch java.io.IOException _)))

(defn queue-stream [stream buffer-type]
  (let [reader (io/reader stream)
        queue (LinkedBlockingQueue.)]
    (.start
     (Thread.
      (fn []
        (doseq [x (take-while identity (repeatedly (buffer buffer-type reader)))]
          (.put queue x))
        (.put queue :eof))))
    (queue-seq queue)))

(defn queue-output [proc buffer-type]
  (assoc proc
    :out (queue-stream (:out proc) buffer-type)
    :err (queue-stream (:err proc) buffer-type)))

(defn run-command [name args options]
  (let [proc (apply conch/proc name (add-proc-args args options))
        options (update-in options [:buffer] #(or %
                                                  (if (:seq options)
                                                    :line
                                                    1024)))
        {:keys [buffer out in err timeout verbose]} options 
        proc (queue-output proc buffer)
        exit-code (future (if timeout
                            (conch/exit-code proc timeout)
                            (conch/exit-code proc)))]
    (when in (conch/feed-from-string proc (:in proc))) ;; This will become more sophisticated.
    (let [proc-out (redirect out options :out proc)
          proc-err (redirect err options :err proc)]
      (cond
       verbose {:proc proc
                :exit-code @exit-code
                :stdout proc-out
                :stderr proc-err}
       (= (:seq options) :err) proc-err
       :else proc-out))))

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