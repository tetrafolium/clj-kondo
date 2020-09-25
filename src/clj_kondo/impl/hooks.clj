(ns clj-kondo.impl.hooks
  {:no-doc true}
  (:require [clj-kondo.impl.findings :as findings]
            [clj-kondo.impl.utils :refer [vector-node list-node token-node
                                          sexpr]]
            [clojure.java.io :as io]
            [clojure.zip :as zip]
            [sci.core :as sci :refer [copy-var]]))

(set! *warn-on-reflection* true)

(def ^:dynamic *ctx* nil)

(defn reg-finding! [m]
  (let [ctx *ctx*
        filename (:filename ctx)]
    (findings/reg-finding! ctx (assoc m :filename filename))))

(def zip-ns (sci/create-ns 'clojure.zip nil))

(def zip-namespace
  {'zipper       (copy-var zip/zipper zip-ns)
   'seq-zip      (copy-var zip/seq-zip zip-ns)
   'vector-zip   (copy-var zip/vector-zip zip-ns)
   'xml-zip      (copy-var zip/xml-zip zip-ns)
   'node         (copy-var zip/node zip-ns)
   'branch?      (copy-var zip/branch? zip-ns)
   'children     (copy-var zip/children zip-ns)
   'make-node    (copy-var zip/make-node zip-ns)
   'path         (copy-var zip/path zip-ns)
   'lefts        (copy-var zip/lefts zip-ns)
   'rights       (copy-var zip/rights zip-ns)
   'down         (copy-var zip/down zip-ns)
   'up           (copy-var zip/up zip-ns)
   'root         (copy-var zip/root zip-ns)
   'right        (copy-var zip/right zip-ns)
   'rightmost    (copy-var zip/rightmost zip-ns)
   'left         (copy-var zip/left zip-ns)
   'leftmost     (copy-var zip/leftmost zip-ns)
   'insert-left  (copy-var zip/insert-left zip-ns)
   'insert-right (copy-var zip/insert-right zip-ns)
   'replace      (copy-var zip/replace zip-ns)
   'edit         (copy-var zip/edit zip-ns)
   'insert-child (copy-var zip/insert-child zip-ns)
   'append-child (copy-var zip/append-child zip-ns)
   'next         (copy-var zip/next zip-ns)
   'prev         (copy-var zip/prev zip-ns)
   'end?         (copy-var zip/end? zip-ns)
   'remove       (copy-var zip/remove zip-ns)})

(defn time*
  "Evaluates expr and prints the time it took.  Returns the value of
  expr."
  [_ _ expr]
  `(let [start# (. System (nanoTime))
         ret# ~expr]
     (prn (str "Elapsed time: " (/ (double (- (. System (nanoTime)) start#)) 1000000.0) " msecs"))
     ret#))

(defn find-file-on-classpath ^java.io.File
  [base-path]
  (some (fn [cp-entry]
          (let [f (io/file cp-entry base-path)]
            (when (.exists f) f)))
        (:classpath *ctx*)))

(def sci-ctx
  (sci/init {:namespaces {'clojure.core {'time (with-meta time* {:sci/macro true})}
                          'clojure.zip zip-namespace
                          'clj-kondo.hooks-api {'token-node token-node
                                                'vector-node vector-node
                                                'list-node list-node
                                                'sexpr sexpr
                                                'reg-finding! reg-finding!}}
             :classes {'java.io.Exception Exception
                       'java.lang.System System}
             :imports {'Exception 'java.io.Exception
                       'System java.lang.System}
             :load-fn (fn [{:keys [:namespace]}]
                        (let [^String ns-str (munge (name namespace))
                              base-path (.replace ns-str "." "/")
                              base-path (str base-path ".clj")]
                          (if-let [f (find-file-on-classpath base-path)]
                            {:file (.getAbsolutePath f)
                             :source (slurp f)}
                            (binding [*out* *err*]
                              (println "WARNING: file" base-path "not found while loading hook")
                              nil))))}))

(def hook-fn
  (let [delayed-cfg
        (fn [ctx config key ns-sym var-sym]
          (try (let [sym (symbol (str ns-sym)
                                 (str var-sym))]
                 (when-let [x (get-in config [:hooks key sym])]
                   (sci/binding [sci/out *out*
                                 sci/err *err*]
                     (let [code (if (string? x) x
                                    ;; x is a function symbol
                                    (let [ns (namespace x)]
                                      (format "(require '%s)\n%s" ns x)))]
                       (binding [*ctx* ctx]
                         (sci/eval-string* sci-ctx code))))))
               (catch Exception e
                 (binding [*out* *err*]
                   (println "WARNING: error while trying to read hook for"
                            (str ns-sym "/" var-sym ":")
                            (.getMessage e))
                   (when (= "true" (System/getenv "CLJ_KONDO_DEV"))
                     (println e)))
                 nil)))
        delayed-cfg (memoize delayed-cfg)]
    delayed-cfg))
