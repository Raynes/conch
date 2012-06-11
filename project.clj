(defproject conch "0.3.0"
  :description "A better shell-out library for Clojure."
  :dependencies [[org.clojure/clojure "1.4.0"]]
  :aliases {"test-all" ["with-profile" "dev,default:dev,1.2,default:dev,1.3,default" "test"]}
  :profiles {:1.2 {:dependencies [[org.clojure/clojure "1.2.1"]]}
             :1.3 {:dependencies [[org.clojure/clojure "1.3.0"]]}})
