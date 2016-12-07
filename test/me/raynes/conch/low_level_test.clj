(ns me.raynes.conch.low-level-test
  (:use clojure.test
        [clojure.java.io :only [file]]
        [clojure.string :only [trim split]])
  (:require [me.raynes.conch.low-level :as c]))

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
    (is (= (.getAbsolutePath (file "test/me"))
           (trim (c/stream-to-string (c/proc "pwd" :dir "test/me") :out)))))
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

(deftest variable-parameters-test
  (testing "zero parameters can be consumed."
    (is (= "\n" (c/stream-to-string (c/proc "echo") :out))))
  (testing "empty parameters can be consumed."
      (is (= "\n" (c/stream-to-string (c/proc "echo" []) :out))))
  (testing "single parameter can be consumed."
      (is (= "fee\n" (c/stream-to-string (c/proc "echo" ["fee"]) :out))))
  (testing "variable number of parameters can be consumed."
    (is (= "fee fie foe fum\n" (c/stream-to-string (c/proc "echo" ["fee" "fie" "foe" "fum"]) :out))))
  (testing "variable number of parameters can be consumed with any nesting."
    (is (= "fee fie foe fum\n" (c/stream-to-string (c/proc ["echo" ["fee" ["fie" ["foe"]] "fum"]]) :out)))))

(deftest exit-code-test
  (testing "exit-code blocks until a process exists and returns an exit code."
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

(deftest exit-code-timeout-test
  (testing "Returns :timeout if a timeout and destroy was necessary."
    (is (= :timeout (c/exit-code (c/proc "cat") 500))))
  (testing "Exit code is returned if the timeout doesn't get hit."
    (is (= 0 (c/exit-code (c/proc "ls") 10000)))))
