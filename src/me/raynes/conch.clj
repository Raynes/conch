(ns me.raynes.conch
  (:require [me.raynes.conch.low-level :as conch]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [useful.seq :as seq])
  (:import java.util.concurrent.LinkedBlockingQueue))

(defprotocol Redirectable
  (redirect [this options k proc]))

(extend-type java.io.File
  Redirectable
  (redirect [f options k proc]
    (with-open [writer (java.io.FileWriter. f)]
      (doseq [x (get proc k)]
        (.write writer x)))))

(extend-type clojure.lang.IFn
  Redirectable
  (redirect [f options k proc]
    (doseq [buffer (get proc k)]
      (f buffer proc))))

(extend-type java.io.Writer
  Redirectable
  (redirect [w options k proc]
    (doseq [x (get proc k)]
      (.write w x))))

(defn seqify? [options k]
  (let [seqify (:seq options)]
    (or (= seqify k)
        (true? seqify))))

(extend-type nil
  Redirectable
  (redirect [_ options k proc]
    (let [seqify (:seq options)
          s (k proc)]
      (if
       (seqify? options k) s
       (string/join s)))))

(defprotocol Drinkable
  (drink [this proc]))

(extend-type clojure.lang.ISeq
  Drinkable
  (drink [s proc]
    (with-open [writer (java.io.PrintWriter. (:in proc))]
      (binding [*out* writer]
        (doseq [x s]
          (println x))))
    (conch/done proc)))

(extend-type java.io.Reader
  Drinkable
  (drink [r proc]
    (conch/feed-from proc r)
    (conch/done proc)))

(extend-type java.io.File
  Drinkable
  (drink [f proc]
    (drink (io/reader f) proc)))

(extend-type java.lang.String
  Drinkable
  (drink [s proc]
    (conch/feed-from-string proc s)
    (conch/done proc)))

(defn get-drunk [item proc]
  (drink
   (if (coll? item)
     (seq item)
     item)
   proc))

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
     (let [cbuf (make-array Character/TYPE kind)
           size (.read reader cbuf)]
       (when (pos? size)
         (string/join
          (if (= size kind)
            cbuf
            (take size cbuf)))))
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

(defn compute-buffer [options]
  (update-in options [:buffer]
             #(if-let [buffer %]
                buffer
                (if (or (:seq options)
                        (:pipe options)
                        (ifn? (:out options))
                        (ifn? (:err options)))
                  :line
                  1024))))

(defn run-command [name args options]
  (let [proc (apply conch/proc name (add-proc-args args options))
        options (compute-buffer options) 
        {:keys [buffer out in err timeout verbose]} options 
        proc (queue-output proc buffer)
        exit-code (future (if timeout
                            (conch/exit-code proc timeout)
                            (conch/exit-code proc)))]
    (when in (future (get-drunk in proc)))
    (let [proc-out (future (redirect out options :out proc))
          proc-err (future (redirect err options :err proc))
          proc-out @proc-out
          proc-err @proc-err]
      (cond
       verbose {:proc proc
                :exit-code exit-code
                :stdout proc-out
                :stderr proc-err}
       (= (:seq options) :err) proc-err
       :else proc-out))))

(defn execute [name & args]
  (let [[[options] args] ((juxt filter remove) map? args)]
    (if (:background options)
      (future (run-command name args options))
      (run-command name args options))))

(defn execute [name & args]
  (let [end (last args)
        in-arg (first (filter #(seq? %) args))
        args (remove #(seq? %) args)
        options (when (map? end) end)
        args (if options (drop-last args) args)
        options (if in-arg (assoc options :in in-arg) options)]
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
