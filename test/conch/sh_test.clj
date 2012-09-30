(ns conch.sh-test
  (:use clojure.test)
  (:require [conch.sh :as sh]))

(deftest output-test
  (sh/let-programs [errecho "test/testfiles/errecho"]
    (sh/with-programs [echo]
      (testing "By default, output is accumulated into a monolitic string"
        (is (= "hi\n" (echo "hi"))))
      (testing "Output can be a lazy sequence"
        (is (= ["hi" "there"] (echo "hi\nthere" {:seq true}))))
      (testing "Can redirect output to a file"
        (let [output "hi\nthere\n"
              testfile "test/testfiles/foo"]
          (echo "hi\nthere" {:out (java.io.File. testfile)})
          (is (= output (slurp testfile)))
          (errecho "hi\nthere" {:err (java.io.File. testfile)})
          (is (= output (slurp testfile)))))
      (testing "Can redirect output to a callback function"
        (let [x (atom [])
              ex (atom [])]
          (echo "hi\nthere" {:out (fn [line _] (swap! x conj line))})
          (is (= ["hi" "there"] @x))
          (errecho "hi\nthere" {:err (fn [line _] (swap! ex conj line))})
          (is (= ["hi" "there"] @ex)))))))