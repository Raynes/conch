(ns conch.core
  "A simple but flexible library for shelling out from Clojure."
  (:refer-clojure :exclude [flush])
  (:require [clojure.java.io :as io]))

(defn env-strings
  "Turns an array of strings of environment variable settings given
  a map."
  [m]
  (when m
    (if (map? m)
      (into-array String (for [[k v] m] (str (name k) "=" v)))
      m)))

(defn proc
  "Spin off another process. Returns the process's input stream,
  output stream, and err stream as a map of :in, :out, and :err keys
  If passed the optional :dir and/or :env keyword options, the dir
  and enviroment will be set to what you specify."
  [& args]
  (let [[cmd args] (split-with (complement keyword?) args)
        args (apply hash-map args)
        process (.exec (Runtime/getRuntime)
                       (into-array String cmd)
                       (env-strings (:env args))
                       (when-let [dir (:dir args)]
                         (io/file dir)))]
    {:out (.getInputStream process)
     :in  (.getOutputStream process)
     :err (.getErrorStream process)
     :process process}))

(defn destroy
  "Destroy a process. Kills the process and closes its streams."
  [process]
  (doseq [[_ std-stream] (dissoc process :process)]
    (.close std-stream))
  (.destroy (:process process)))

;; .waitFor returns the exit code. This makes this function useful for
;; both getting an exit code and stopping the thread until a process
;; terminates.
(defn exit-code
  "Waits for the process to terminate (blocking the thread) and returns
  the exit code."
  [process]
  (.waitFor (:process process)))

(defn flush
  "Flush the output stream of a process."
  [process]
  (.flush (:in process)))

(defn stream-to
  "Stream :out or :err from a process to an input stream.
  If :enc option is specified, stream with that encoding. The
  default encoding is UTF-8."
  [process from to & {:keys [enc] :or {enc "UTF-8"}}]
  (io/copy (io/reader (process from)) to :encoding enc))

(defn feed-from
  "Feed to a process's input stream with optional :enc encoding. if
  :flush isn't specified as false, flushes the output stream after
  writing."
  [process from & {:keys [enc flush] :or {enc "UTF-8", flush true}}]
  (io/copy from (:in process) :encoding enc)
  (when flush (conch.core/flush process)))

(defn stream-to-string
  "Streams the output of the process to a string and returns it."
  [process from & args]
  (with-open [writer (java.io.StringWriter.)]
    (apply stream-to process from writer args)
    (str writer)))

(defn stream-to-out
  "Streams the output of the process to *out*."
  [process from & args]
  (apply stream-to process from *out* args))

(defn feed-from-string
  "Feed the process some data from a string."
  [process s & args]
  (apply feed-from process (java.io.StringReader. s) args))

