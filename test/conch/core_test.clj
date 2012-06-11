(ns conch.core-test
  (:use clojure.test
        [clojure.java.io :only [file]]
        [clojure.string :only [trim split]])
  (:require [conch.core :as c]))

(defn parse-env [env]
  (into {}
        (for [[k v] (map #(split % #"=") (split env #"\r?\n"))]
          [k v])))

(deftest proc-test
  (testing "proc returns :in, :out, and :err."
    (let [p (c/proc "ls")]
      (is (instance? java.io.InputStream (:out p)))
      (is (instance? java.io.OutputStream (:in p)))
      (is (instance? java.io.InputStream (:err p)))))
  (testing "proc with :dir executes inside of the directory"
    (is (= (.getAbsolutePath (file "test/conch"))
           (trim (c/stream-to-string (c/proc "pwd" :dir "test/conch") :out)))))
  (testing "proc with :env executes with env vars set."
    (is (= "BAR"
           (get (parse-env
                 (c/stream-to-string
                  (c/proc "env" :env {"FOO" "BAR"})
                  :out))
                "FOO")))))

(deftest stream-to-string-test
  (testing "output is put in a string and returned"
    (is (= "foo\n" (c/stream-to-string (c/proc "echo" "foo") :out)))))

(deftest exit-code-test
  (testing "exit-code blocls until a process exists and returns an exit code."
    (is (= 0 (c/exit-code (c/proc "pwd"))))))

(deftest read-line-test
  (is (= "foo" (c/read-line (c/proc "echo" "foo") :out))))

(deftest feed-from-test
  (testing "Can feed from a reader."
    (let [p (c/proc "cat")]
      (c/feed-from-string p "foo\n")
      (is (= "foo" (c/read-line p :out)))
      (c/destroy p))))

(deftest stream-to-test
  (testing "Can stream to a writer."
    (let [writer (java.io.StringWriter.)]
      (is (= "foo\n"
             (do (c/stream-to (c/proc "echo" "foo") :out writer)
                 (str writer)))))))