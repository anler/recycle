(defproject recycle "0.1.0-SNAPSHOT"
  :description "Clojure library designed to manage application state heavily inspired by stuartsierra's component."
  :url "https://github.com/anler/recycle"
  :license {:name "BSD (2-Clause)"
            :url  "http://opensource.org/licenses/BSD-2-Clause"}
  :dependencies [[org.clojure/clojure "1.8.0" :scope "provided"]
                 [anler/try "0.3.0"]
                 [org.clojure/core.async "0.3.443"]
                 [org.clojure/tools.logging "0.4.0"]]
  :source-paths ["src/clj" "src/cljc" "src/cljs"]
  :test-paths ["test/clj" "test/cljc" "test/cljs"]
  :plugins [[funcool/codeina "0.4.0"
             :exclusions [org.clojure/clojure]]]
  :codeina {:sources ["src/clj"]
            :reader  :clojure})
