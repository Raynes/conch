(defproject me.raynes/conch "0.6.0"
  :license {:name "Eclipse Public License - v 1.0"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :url "https://github.com/Raynes/conch"
  :description "A better shell-out library for Clojure."
  :dependencies [[org.clojure/clojure "1.4.0"]
                 [org.flatland/useful "0.10.6"]]
  :aliases {"testall" ["with-profile" "dev,default:dev,1.2,default:dev,1.3,default:dev,1.5,default" "test"]}
  :profiles {:1.5 {:dependencies [[org.clojure/clojure "1.5.0-master-SNAPSHOT"]]}
             :release {:deploy-repositories {"releases" {:url "https://oss.sonatype.org/service/local/staging/deploy/maven2"
                                                         :creds :gpg}
                                             "snapshots" {:url "http://oss.sonatype.org/content/repositories/snapshots"
                                                          :creds :gpg}}}}
  :repositories {"snapshots" {:url "http://oss.sonatype.org/content/repositories/snapshots"}}
  :pom-addition [:developers [:developer
                              [:name "Anthony Grimes"]
                              [:url "http://blog.raynes.me"]
                              [:email "i@raynes.me"]
                              [:timezone "-6"]]])
