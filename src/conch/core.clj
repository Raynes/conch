(ns conch.core
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
  output stream, and err stream as a map of :in, :out, and :err keys. 
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
     :in (.getOutputStream process)
     :err (.getErrorStream process)
     :process process}))

(defn stream-to
  "Stream :out or :err from a process to an input stream.
  If :enc option is specified, stream with that encoding. The
  default encoding is UTF-8."
  [process from to & {:keys [enc] :or {enc "UTF-8"}}]
  (io/copy (io/reader (process from)) to :encoding enc))

(defn feed-to
  "Feed to a process's input stream with optional :enc encoding."
  [process from & {:keys [enc] :or {end "UTF-8"}}]
  (io/copy from (io/writer (:in process)) :encoding enc))

(defmulti feed (constantly nil))

(defmulti consume (fn [process from to & args] to))

(defmethod consume :string [process from to & args]
  (with-open [writer (java.io.StringWriter.)] 
    (apply stream-to process from writer args)
    (str writer)))

(defmethod consume :*out* [process from to & args]
  (apply stream-to process from *out* args))

(defn stream [process from to & args]
  (if (= to :in)
    (apply feed process from args)
    (apply consume process from to args)))
