(ns me.raynes.conch-test
  (:use clojure.test)
  (:require [me.raynes.conch :as sh])
  (:import clojure.lang.ExceptionInfo))

(deftest output-test
  (sh/let-programs [errecho "test/testfiles/errecho"]
    (sh/with-programs [echo]
      (testing "By default, output is accumulated into a monolitic string"
        (is (= "hi\n" (echo "hi"))))
      (testing "Output can be a lazy sequence"
        (is (= ["hi" "there"] (echo "hi\nthere" {:seq true}))))
      (testing "Can redirect output to a file"
        (let [output "hi\nthere\n"
              testfile (java.io.File/createTempFile "test-output" ".txt")]
          (echo "hi\nthere" {:out testfile})
          (is (= output (slurp testfile)))
          (errecho "hi\nthere" {:err testfile})
          (is (= output (slurp testfile)))))
      (testing "Can redirect output to a callback function"
        (let [x (atom [])
              ex (atom [])]
          (echo "hi\nthere" {:out (fn [line _] (swap! x conj line))})
          (is (= ["hi" "there"] @x))
          (errecho "hi\nthere" {:err (fn [line _] (swap! ex conj line))})
          (is (= ["hi" "there"] @ex))))
      (testing "Can redirect output to a writer"
        (let [writer (java.io.StringWriter.)]
          (echo "hi" {:out writer})
          (is (= (str writer) "hi\n")))))))

(defn input-stream-to-byte-array [is]
  "Convert an input stream is to byte array"
  (with-open [baos (java.io.ByteArrayOutputStream.)]
    (let [ba (byte-array 2000)]
      (loop [n (.read is ba 0 2000)]
        (when (> n 0)
          (.write baos ba 0 n)
          (recur (.read is ba 0 2000))))
      (.toByteArray baos))))

(defn file-to-bytearray [file]
  "Convert a file to a byte array"
  (let [is (clojure.java.io/input-stream file)]
    (input-stream-to-byte-array is)))

(deftest output-test-binary
  (sh/let-programs [errecho "test/testfiles/errecho"]
    (sh/with-programs [cat]
      (let [binary-file-path (.getAbsolutePath (java.io.File. "test/testfiles/bytes_0_through_255"))
            bytes-0-through-255 (byte-array (map sh/ubyte (range 256)))]
        (testing "By default, output is accumulated as a single byte array"
          (let [result (cat binary-file-path {:binary true})]
            (is (sh/byte-array? result))
            (is (= (seq bytes-0-through-255) (seq result)))))
        (testing "Output can be a lazy sequence of byte arrays"
          (let [result (cat binary-file-path {:binary true :seq true :buffer 10})]
            (is (every? sh/byte-array? result))
            (is (= (seq bytes-0-through-255) (mapcat seq result)))))
        (testing "Can redirect output to a file"
          (let [temp-file (java.io.File/createTempFile "binary-file" ".bin")]
            (cat binary-file-path {:binary true :buffer 10 :out temp-file})
            (let [result (file-to-bytearray temp-file)]
              (is (sh/byte-array? result))
              (is (= (seq bytes-0-through-255) (seq result))))))
        (testing "Can redirect output to a callback function. A small buffer will result in a seq of byte arrays."
          (let [result (atom [])]
            (cat binary-file-path {:binary true :buffer 10 :out (fn [line _] (swap! result conj line))})
            (is (every? sh/byte-array? @result))
            (is (= (seq bytes-0-through-255) (seq (byte-array (mapcat seq @result)))))))))))

(deftest timeout-test
  (binding [sh/*throw* false]
    (sh/let-programs [sloop "test/testfiles/sloop"]
      (testing "Process exits and doesn't block forever"
        (sloop {:timeout 1000})) ; If the test doesn't sit here forever, we have won.
      (testing "Accumulate output before process dies from timeout"
        ;; We have to test a non-exact value here. We're measuring time in two
        ;; different places/languages, so there may be three his on some runs
        ;; and two on others.
        (is (.startsWith (sloop {:timeout 2000}) "hi\nhi\n"))))))

(deftest background-test
  (testing "Process runs in a future"
    (let [f (sh/with-programs [echo] (echo "hi" {:background true}))]
      (is (future? f))
      (is (= "hi\n" @f)))))

(deftest convert-test
  (testing "Stringifies args"
    (is (= "lol\n" (sh/with-programs [echo] (echo (java.io.File. "lol")))))))

(deftest pipe-test
  (sh/let-programs [errecho "test/testfiles/errecho"]
    (sh/with-programs [echo cat]
      (testing "Can pipe the output of one command as the input to another."
        (is (= "hi\n" (cat {:in (echo "hi" {:seq true})})))
        (is (= "hi\n" (cat {:in (errecho "hi" {:seq :err})})))
        (is (= "hi\n" (cat (echo "hi" {:seq true}))))))))

(deftest in-test
  (sh/with-programs [echo cat]
    (testing "Can input from string"
      (is (= "hi" (cat {:in "hi"}))))
    (testing "Can input a seq"
      (is (= "hi\nthere\n" (cat {:in ["hi" "there"]}))))
    (testing "Can input a file"
      (is (= "we\nwear\nshort\nshorts" (cat {:in (java.io.File. "test/testfiles/inputdata")}))))
    (testing "Can input a reader"
      (is (= "we\nwear\nshort\nshorts" (cat {:in (java.io.FileReader. "test/testfiles/inputdata")}))))))

(deftest exception-test
  (sh/with-programs [ls]
    (testing "Throws exceptions when *throw* is true."
      (is sh/*throw*)
      (is (thrown? ExceptionInfo (ls "-2")))
      (is (string?
           (try (ls "-2")
                (catch ExceptionInfo e
                  (:stderr (ex-data e))))))
      (testing "But can override"
        (is (= "" (ls "-2" {:throw false})))))
    (testing "Can turn it off"
      (binding [sh/*throw* false]
        (is (not sh/*throw*))
        (is (= "" (ls "-2")))
        (testing "But can override"
          (is (thrown? ExceptionInfo (ls "-2" {:throw true}))))))))

(deftest env-test
  (sh/with-programs [env]
    (testing "Environment is cleared if a program is called with :clear-env"
      (is (= "" (env {:throw true :clear-env true})))
      (is (not= "" (env {:throw true}))))))
