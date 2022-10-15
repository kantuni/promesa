;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) Andrey Antukh <niwi@niwi.nz>

(ns ^:no-doc promesa.impl
  "Implementation of promise protocols."
  (:require [promesa.protocols :as pt]
            [promesa.util :as pu]
            [promesa.exec :as exec])
  #?(:clj (:import
           java.util.concurrent.CompletableFuture
           java.util.concurrent.CompletionStage
           java.util.concurrent.TimeoutException
           java.util.concurrent.ExecutionException
           java.util.concurrent.CompletionException
           java.util.concurrent.Executor
           java.util.function.Function
           java.util.function.Supplier)))

;; --- Global Constants

#?(:cljs (def ^:dynamic *default-promise* js/Promise))

(defn resolved
  [v]
  #?(:cljs (.resolve *default-promise* v)
     :clj (CompletableFuture/completedFuture v)))

(defn rejected
  [v]
  #?(:cljs (.reject *default-promise* v)
     :clj (let [p (CompletableFuture.)]
            (.completeExceptionally ^CompletableFuture p v)
            p)))

;; --- Promise Impl

(defn deferred
  []
  #?(:clj (CompletableFuture.)
     :cljs
     (let [state #js {}
           obj (new *default-promise*
                    (fn [resolve reject]
                      (set! (.-resolve state) resolve)
                      (set! (.-reject state) reject)))]
       (specify! obj
         pt/ICompletable
         (-resolve! [_ v]
           (.resolve state v))
         (-reject! [_ v]
           (.reject state v))))))

#?(:cljs
   (defn extend-promise!
     [t]
     (extend-type t
       pt/IPromiseFactory
       (-promise [p] p)

       pt/IPromise
       (-map
         ([it f] (.then it #(f %)))
         ([it f e] (.then it #(f %))))
       (-bind
         ([it f] (.then it #(f %)))
         ([it f e] (.then it #(f %))))
       (-then
         ([it f] (.then it #(f %)))
         ([it f e] (.then it #(f %))))
       (-mapErr
         ([it f] (.catch it #(f %)))
         ([it f e] (.catch it #(f %))))
       (-thenErr
         ([it f] (.catch it #(f %)))
         ([it f e] (.catch it #(f %))))
       (-handle
         ([it f] (.then it #(f % nil) #(f nil %)))
         ([it f e] (.then it #(f % nil) #(f nil %))))
       (-finally
         ([it f] (.then it #(f % nil) #(f nil %)) it)
         ([it f executor] (.then it #(f % nil) #(f nil %)) it)))))

#?(:cljs
   (extend-promise! js/Promise))

#?(:clj (def fw-identity (pu/->FunctionWrapper identity)))

#?(:clj
   (extend-protocol pt/IPromise
     CompletionStage
     (-map
       ([it f]
        (.thenApply ^CompletionStage it
                    ^Function (pu/->FunctionWrapper f)))

       ([it f executor]
        (.thenApplyAsync ^CompletionStage it
                         ^Function (pu/->FunctionWrapper f)
                         ^Executor (exec/resolve-executor executor))))

     (-bind
       ([it f]
        (.thenCompose ^CompletionStage it
                      ^Function (pu/->FunctionWrapper f)))

       ([it f executor]
        (.thenComposeAsync ^CompletionStage it
                           ^Function (pu/->FunctionWrapper f)
                           ^Executor (exec/resolve-executor executor))))

     (-then
       ([it f]
        (.thenCompose ^CompletionStage it
                      ^Function (pu/->FunctionWrapper (comp pt/-promise f))))

       ([it f executor]
        (.thenComposeAsync ^CompletionStage it
                           ^Function (pu/->FunctionWrapper (comp pt/-promise f))
                           ^Executor (exec/resolve-executor executor))))

     (-mapErr
       ([it f]
        (letfn [(handler [e]
                  (if (instance? CompletionException e)
                    (f (.getCause ^Exception e))
                    (f e)))]
          (.exceptionally ^CompletionStage it
                          ^Function (pu/->FunctionWrapper handler))))

       ([it f executor]
        (letfn [(handler [e]
                  (if (instance? CompletionException e)
                    (f (.getCause ^Exception e))
                    (f e)))]
          ;; ONLY on JDK >= 12 it is there but in jdk<12 will throw an
          ;; error
          (.exceptionallyAsync ^CompletionStage it
                               ^Function (pu/->FunctionWrapper handler)
                               ^Executor (exec/resolve-executor executor)))))

     (-thenErr
       ([it f]
        (letfn [(handler [v e]
                  (if e
                    (if (instance? CompletionException e)
                      (pt/-promise (f (.getCause ^Exception e)))
                      (pt/-promise (f e)))
                    it))]
          (as-> ^CompletionStage it $$
            (.handle $$ ^BiFunction (pu/->BiFunctionWrapper handler))
            (.thenCompose $$ ^Function fw-identity))))

       ([it f executor]
        (letfn [(handler [v e]
                  (if e
                    (if (instance? CompletionException e)
                      (pt/-promise (f (.getCause ^Exception e)))
                      (pt/-promise (f e)))
                    (pt/-promise v)))]
          (as-> ^CompletionStage it $$
            (.handleAsync $$
                          ^BiFunction (pu/->BiFunctionWrapper handler)
                          ^Executor (exec/resolve-executor executor))
            (.thenCompose $$ ^Function fw-identity)))))

     (-handle
       ([it f]
        (as-> ^CompletionStage it $$
          (.handle $$ ^BiFunction (pu/->BiFunctionWrapper (comp pt/-promise f)))
          (.thenCompose $$ ^Function fw-identity)))

       ([it f executor]
        (as-> ^CompletionStage it $$
          (.handleAsync $$
                        ^BiFunction (pu/->BiFunctionWrapper (comp pt/-promise f))
                        ^Executor (exec/resolve-executor executor))
          (.thenCompose $$ ^Function fw-identity))))

     (-finally
       ([it f]
        (.whenComplete ^CompletionStage it
                       ^BiConsumer (pu/->BiConsumerWrapper f)))

       ([it f executor]
        (.whenCompleteAsync ^CompletionStage it
                            ^BiConsumer (pu/->BiConsumerWrapper f)
                            ^Executor (exec/resolve-executor executor))))

     ))

#?(:clj
   (extend-type CompletableFuture
     pt/ICancellable
     (-cancel! [it]
       (.cancel it true))
     (-cancelled? [it]
       (.isCancelled it))

     pt/ICompletable
     (-resolve! [f v] (.complete f v))
     (-reject! [f v] (.completeExceptionally f v))

     pt/IState
     (-extract [it]
       (try
         (.getNow it nil)
         (catch ExecutionException e
           (.getCause e))
         (catch CompletionException e
           (.getCause e))))

     (-resolved? [it]
       (and (not (.isCompletedExceptionally it))
            (not (.isCancelled it))
            (.isDone it)))

     (-rejected? [it]
       (.isCompletedExceptionally it))

     (-pending? [it]
       (not (.isDone it)))))

;; --- Promise Factory

;; This code is responsible of coercing the incoming value to a valid
;; promise type. In some cases we will receive a valid promise object,
;; in this case we return it as is. This is useful when you want to
;; `then` or `map` over a plain value that can be o can not be a
;; promise object

#?(:clj
   (extend-protocol pt/IPromiseFactory
     CompletionStage
     (-promise [cs] cs)

     Throwable
     (-promise [e]
       (rejected e))

     Object
     (-promise [v]
       (resolved v))

     nil
     (-promise [v]
       (resolved v)))

   :cljs
   (extend-protocol pt/IPromiseFactory
     js/Error
     (-promise [e]
       (rejected e))

     default
     (-promise [v]
       (resolved v))))

;; --- Pretty printing

(defn promise->str
  [p]
  "#<Promise[~]>")

#?(:clj
   (defmethod print-method java.util.concurrent.CompletionStage
     [p ^java.io.Writer writer]
     (let [status (cond
                    (pt/-pending? p) "pending"
                    (pt/-rejected? p) "rejected"
                    :else "resolved")]
       (.write writer ^String (format "#object[java.util.concurrent.CompletableFuture 0x%h \"%s\"]" (hash p) status)))))

#?(:cljs
   (extend-type js/Promise
     IPrintWithWriter
     (-pr-writer [p writer opts]
       (-write writer (promise->str p)))))
