(ns me.raynes.conch
  (:require [me.raynes.conch.low-level :as conch]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [flatland.useful.seq :as seq])
  (:import java.util.concurrent.LinkedBlockingQueue))

(def ^:dynamic *throw*
  "If set to false, exit codes are ignored. If true (default),
   throw exceptions for non-zero exit codes."
  true)

(defprotocol Redirectable
  (redirect [this options k proc]))

(defn byte? [x]
  (and (not (nil? x))
       (= java.lang.Byte (.getClass x))))

(defn test-array
  [t]
  (let [check (type (t []))]
    (fn [arg] (instance? check arg))))

(def byte-array?
  (test-array byte-array))


(defn write-to-writer [writer s is-binary]
  (cond
   (byte? (first s)) (.write writer (byte-array s))
   (or (not is-binary)
       (byte-array? (first s))) (if (char? (first s))
                                  (.write writer (apply str s))
                                  (doseq [x s] (.write writer x)))))

(extend-type java.io.File
  Redirectable
  (redirect [f options k proc]
    (let [s (k proc)
          is-binary (:binary options)]
      (with-open [writer (if is-binary (io/output-stream f) (java.io.FileWriter. f))]
        (write-to-writer writer s is-binary)))))

(extend-type clojure.lang.IFn
  Redirectable
  (redirect [f options k proc]
    (doseq [buffer (get proc k)]
      (f buffer proc))))

(extend-type java.io.Writer
  Redirectable
  (redirect [w options k proc]
    (let [s (get proc k)]
      (write-to-writer w s (:binary options)))))

(defn seqify? [options k]
  (let [seqify (:seq options)]
    (or (= seqify k)
        (true? seqify))))

(extend-type nil
  Redirectable
  (redirect [_ options k proc]
    (let [seqify (:seq options)
          s (k proc)]
      (cond
       (seqify? options k) s
       (byte? (first s)) (byte-array s)
       (byte-array? (first s)) (byte-array (mapcat seq s))
       :else (string/join s)))))

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

(defmulti buffer (fn [kind _ _]
                   (if (number? kind)
                     :number
                     kind)))

(defmethod buffer :number [kind reader binary]
  #(try
     (let [cbuf (make-array (if binary Byte/TYPE Character/TYPE) kind)
           size (.read reader cbuf)]
       (when-not (neg? size)
         (let [result (if (= size kind)
                        cbuf
                        (take size cbuf))]
           (if binary
             (if (seq? result) (byte-array result) result)
             (string/join result)))))
     (catch java.io.IOException _)))

(defn ubyte [val]
   (if (>= val 128)
     (byte (- val 256))
     (byte val)))

(defmethod buffer :none [_ reader binary]
  #(try
     (let [c (.read reader)]
       (when-not (neg? c)
         (if binary
           ;; Return a byte (convert from unsigned value)
           (ubyte c)
           ;; Return a char
           (char c))))
     (catch java.io.IOException _)))

(defmethod buffer :line [_ reader binary]
  #(try
     (.readLine reader)
     (catch java.io.IOException _)))

(defn queue-stream [stream buffer-type binary]
  (let [queue (LinkedBlockingQueue.)
        read-object (if binary stream (io/reader stream))]
    (.start
     (Thread.
      (fn []
        (doseq [x (take-while identity (repeatedly (buffer buffer-type read-object binary)))]
          (.put queue x))
        (.put queue :eof))))
    (queue-seq queue)))

(defn queue-output [proc buffer-type binary]
  (assoc proc
    :out (queue-stream (:out proc) buffer-type binary)
    :err (queue-stream (:err proc) buffer-type binary)))

(defn compute-buffer [options]
  (update-in options [:buffer]
             #(if-let [buffer %]
                buffer
                (if (and (not (:binary options))
                         (or (:seq options)
                             (:pipe options)
                             (ifn? (:out options))
                             (ifn? (:err options))))
                  :line
                  1024))))

(defn exit-exception [verbose]
  (throw (ex-info (str "Program returned non-zero exit code "
                       @(:exit-code verbose))
                  verbose)))

(defn run-command [name args options]
  (let [proc (apply conch/proc name (add-proc-args (map str args) options))
        options (compute-buffer options)
        {:keys [buffer out in err timeout verbose binary]} options
        proc (queue-output proc buffer binary)
        exit-code (future (if timeout
                            (conch/exit-code proc timeout)
                            (conch/exit-code proc)))]
    (when in (future (get-drunk in proc)))
    (let [proc-out (future (redirect out options :out proc))
          proc-err (future (redirect err options :err proc))
          proc-out @proc-out
          proc-err @proc-err
          verbose-out {:proc proc
                       :exit-code exit-code
                       :stdout proc-out
                       :stderr proc-err}
          result (cond
                  verbose verbose-out
                  (= (:seq options) :err) proc-err
                  :else proc-out)]
      ;; Not using `zero?` here because exit-code can be a keyword.
      (if (= 0 @exit-code)
        result
        (cond (and (contains? options :throw)
                   (:throw options))
              (exit-exception verbose-out)

              (and (not (contains? options :throw))
                   *throw*)
              (exit-exception verbose-out)

              :else result)))))

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
