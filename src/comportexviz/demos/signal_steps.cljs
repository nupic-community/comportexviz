(ns comportexviz.demos.signal-steps
  (:require [org.nfrac.comportex.core :as core]
            [org.nfrac.comportex.encoders :as enc]
            [org.nfrac.comportex.util :as util]
            [comportexviz.parameters]))

(def bit-width 300)
(def cat-bit-width 60)
(def numb-bit-width (- bit-width cat-bit-width))
(def numb-max 7)
(def numb-domain [0 numb-max])
(def on-bits 30)

(def initial-input [:up 0])

(defn input-transform
  [[dir i]]
  (let [new-i (-> (case dir
                    :up (inc i)
                    :down (dec i))
                  (min numb-max)
                  (max 0))
        new-dir (util/rand-nth [:up :down])]
    [new-dir new-i]))

(def encoder
  (enc/encat 2
             (enc/category-encoder cat-bit-width [:down :up])
             (enc/linear-encoder numb-bit-width on-bits numb-domain)))

(defn ^:export model
  []
  (let [gen (core/input-generator initial-input input-transform encoder)
        spec comportexviz.parameters/small]
    (core/tree core/cla-region spec [gen])))
