(ns refactor-nrepl.integration-tests
  (:require [clojure.java.io :as io]
            [clojure.test :refer :all]
            [clojure.tools.nrepl.server :as nrserver]
            [me.raynes.fs :as fs]
            [refactor-nrepl find-unbound refactor
             [client :refer [connect find-usages remove-debug-invocations rename-symbol resolve-missing]]]
            refactor-nrepl.ns.resolve-missing)
  (:import java.io.File))

(defn- create-temp-dir
  "Creates and returns a new temporary directory java.io.File."
  [name]
  (let [temp-file (File/createTempFile name nil)]
    (.delete temp-file)
    (.mkdirs temp-file)
    temp-file))

(defn create-test-project []
  (let [temp-dir (create-temp-dir "refactor-nrepl-test")
        orig-src (io/file "resources/testproject/src")]

    (fs/copy-dir orig-src temp-dir)

    temp-dir))

(defn start-up-repl-server []
  (let [server
        (nrserver/start-server
         :port 7777
         :handler (nrserver/default-handler
                    #'refactor-nrepl.refactor/wrap-refactor
                    #'refactor-nrepl.find-unbound/wrap-find-unbound
                    #'refactor-nrepl.ns.resolve-missing/wrap-resolve-missing))]
    server))

(defn stop-repl-server [server]
  (nrserver/stop-server server))

(defn wrap-setup-once [f]
  (let [server (start-up-repl-server)]

    (f)

    (stop-repl-server server)))

(use-fixtures :once wrap-setup-once)

(deftest test-find-two-foo
  (let [tmp-dir (create-test-project)
        transport (connect :port 7777)
        result (find-usages :transport transport :ns 'com.example.two
                            :file (str tmp-dir "/src/com/example/one.clj")
                            :loc-line 6 :loc-column 19
                            :name "foo" :clj-dir (str tmp-dir))]
    (is (= 3 (count result)) (format "expected 3 results but got %d" (count result)))
    (is (every? (partial re-matches #"(?s).*(one|two)\.clj.*") result) "one.clj or two.clj not found in result")

    (is (re-matches #"(?s).*\[2\].*" (first result)) "call of foo not found in ns com.example.one")

    (is (re-matches #"(?s).*\[6\].*" (second result)) "call of foo not found in ns com.example.one")

    (is (re-matches #"(?s).*\[3\].*" (last result)) "def of foo not found in ns com.example.two")

    ;; clean-up
    (.delete tmp-dir)))

(deftest test-find-fn-in-similarly-named-ns
  (let [tmp-dir (create-test-project)
        transport (connect :port 7777)
        result (find-usages :transport transport :ns 'com.example.three
                            :file (str tmp-dir "/src/com/example/four.clj")
                            :loc-line 11 :loc-column 3
                            :name "thre" :clj-dir (str tmp-dir))]
    (is (= 3 (count result)) (format "expected 3 results but got %d" (count result)))))

(deftest test-find-fn-in-dashed-ns
  (let [tmp-dir (create-test-project)
        transport (connect :port 7777)
        result (find-usages :transport transport :ns 'com.example.twenty-four
                            :file (str tmp-dir "/src/com/example/four.clj")
                            :loc-line 14 :loc-column 4
                            :name "stuff" :clj-dir (str tmp-dir))]
    (is (= 3 (count result)) (format "expected 3 results but got %d" (count result)))))

(deftest test-find-dashed-fn
  (let [tmp-dir (create-test-project)
        transport (connect :port 7777)
        result (find-usages :transport transport :ns 'com.example.twenty-four
                            :file (str tmp-dir "/src/com/example/four.clj")
                            :loc-line 16 :loc-column 4
                            :name "more-stuff" :clj-dir (str tmp-dir))]
    (is (= 3 (count result)) (format "expected 3 results but got %d" (count result)))))

(deftest test-rename-foo
  (let [tmp-dir (create-test-project)
        transport (connect :port 7777)
        new-one "(ns com.example.one
  (:require [com.example.two :as two :refer [baz]]
            [com.example.four :as four]))

(defn bar []
  (str \"bar\" (two/baz)))

(defn from-registry [k]
  (k four/registry))
"
        new-two "(ns com.example.two)

(defn ^{:doc \"some text\"} baz []
  \"foo\")
"]
    (rename-symbol :transport transport :ns 'com.example.two :name "foo"
                   :clj-dir (str tmp-dir) :new-name "baz")

    (is (= new-one (slurp (str tmp-dir "/src/com/example/one.clj")))
        "rename failed in com.example.one")

    (is (= new-two (slurp (str tmp-dir "/src/com/example/two.clj")))
        "rename failed in com.example.two")
    ;; clean-up
    (.delete tmp-dir)))

(deftest test-remove-println
  (let [tmp-dir (create-test-project)
        three-file (str tmp-dir "/src/com/example/three.clj")
        transport (connect :port 7777)
        new-three "(ns com.example.three)

(defn fn-with-println [a]
  (if a
    (str a)
    a))

(defn fn-with-let [left]
  (let [right 100]
    (+ left right)
    (let [right (+ left 10)]
      (+ right left))))

(defn other-fn-with-let [left]
  (let [right 100]
    (+ left right)
    (let [right (+ left 10)]
      (+ right left))))

(defn thre [])
"]
    (remove-debug-invocations :transport transport :file three-file)

    (is (= new-three (slurp three-file)) "remove println failed")))

(defrecord Foo [])
(deftype Bar [])
(definterface Baz)

(deftest test-resolve-missing
  (let [transport (connect :port 7777)
        split-res (resolve-missing :transport transport :symbol "split")
        date-res (resolve-missing :transport transport :symbol "Date")
        foo-res (resolve-missing :transport transport :symbol "Foo")
        bar-res (resolve-missing :transport transport :symbol "Bar")
        baz-res (resolve-missing :transport transport :symbol "Baz")
        split-type (second (first (filter #(= (first %) 'clojure.string) split-res)))
        date-type (second (first (filter #(= (first %) 'java.util.Date) date-res)))
        foo-type (second (first (filter #(= (first %) 'refactor_nrepl.integration_tests.Foo)
                                        foo-res)))
        bar-type (second (first (filter #(= (first %) 'refactor_nrepl.integration_tests.Bar) bar-res)))
        baz-type (second (first (filter #(= (first %) 'refactor_nrepl.integration_tests.Baz) baz-res)))]
    (is ((set (map first split-res)) 'clojure.string))
    (is ((set (map first date-res)) 'java.util.Date))
    (is ((set (map first foo-res)) 'refactor_nrepl.integration_tests.Foo))
    (is ((set (map first bar-res)) 'refactor_nrepl.integration_tests.Bar))
    (is ((set (map first baz-res)) 'refactor_nrepl.integration_tests.Baz))
    (is (= date-type :class))
    (is (= foo-type :type))
    (is (= bar-type :type))
    (is (= baz-type :class))
    (is (= split-type :ns))))

(deftest find-local-arg
  (let [tmp-dir (create-test-project)
        three-file (str tmp-dir "/src/com/example/three.clj")
        transport (connect :port 7777)
        result (find-usages :transport transport :name "a" :file three-file :loc-line 3 :loc-column 24)]
    (is (= 5 (count result)) (format "expected 5 results but got %d" (count result)))))

(deftest find-local-let
  (let [tmp-dir (create-test-project)
        three-file (str tmp-dir "/src/com/example/three.clj")
        transport (connect :port 7777)
        result (find-usages :transport transport :name "right" :file three-file :loc-line 12 :loc-column 12)]
    (is (= 2 (count result)) (format "expected 2 results but got %d" (count result)))))

;; commented out because the other tests depend on the classpath being
;; in a pristine condition
;; (deftest test-find-unbound-vars
;;   (let [transport (connect :port 7777)]
;;     (is (= (find-unbound :transport transport :ns "refactor-nrepl.integration-tests")
;;            '#{}))
;;     (is (= (find-unbound :transport transport :ns "resources.test-unbound")
;;            '#{s sep}))))
