(ns conch.sh-test
  (:use clojure.test)
  (:require [conch.sh :as sh]))

(deftest output-test
  (testing "By default, output is accumulated into a monolitic string"
    (is (= "hi\n" (sh/with-programs [echo] (echo "hi")))))
  (testing "Output can be a lazy sequence"
    (is (= ["hi" "there"] (sh/with-programs [echo] (echo "hi\nthere" {:seq true})))))
  (testing "Can redirect output to a file"
    (let [output "hi\nthere\n"]
      (sh/with-programs [echo] (echo "hi\nthere" {:out (java.io.File. "testfile")}))
      (is (= output (slurp "testfile")))))
  (testing "Can redirect output to a callback function"
    (let [x (atom [])]
      (sh/with-programs [echo]
        (echo "hi\nthere" {:out (fn [line _] (swap! x conj line))}))
      (is (= ["hi" "there"] @x)))))