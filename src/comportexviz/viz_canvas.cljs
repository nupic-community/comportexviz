(ns comportexviz.viz-canvas
  (:require [comportexviz.viz-layouts :as lay
             :refer [layout-bounds
                     element-xy
                     fill-elements
                     group-and-fill-elements]]
            [reagent.core :as reagent :refer [atom]]
            [goog.dom :as dom]
            [comportexviz.dom :refer [offset-from-target]]
            [comportexviz.helpers :as helpers :refer [resizing-canvas
                                                      window-resize-listener]]
            [comportexviz.bridge.channel-proxy :as channel-proxy]
            [comportexviz.util :as utilv :refer [tap-c]]
            [comportexviz.selection :as sel]
            [monet.canvas :as c]
            [org.nfrac.comportex.protocols :as p]
            [org.nfrac.comportex.util :as util]
            [cljs.core.async :as async :refer [<! put!]])
  (:require-macros [cljs.core.async.macros :refer [go go-loop alt!]]
                   [comportexviz.macros :refer [with-cache]]))

;;; ## Colours

(defn hsl
  ([h s l] (hsl h s l 1.0))
  ([h s l a]
   (let [h2 (if (keyword? h)
              (case h
                :red 0
                :orange 30
                :yellow 60
                :yellow-green 90
                :green 120
                :blue 210
                :purple 270
                :pink 300)
              ;; otherwise angle
              h)]
     (str "hsla(" h2 ","
          (long (* s 100)) "%,"
          (long (* l 100)) "%,"
          a ")"))))

(defn grey
  [z]
  (let [v (long (* z 255))]
    (str "rgb(" v "," v "," v ")")))

(def state-colors
  {:background "#eee"
   :inactive "white"
   :inactive-syn "black"
   :growing (hsl :green 1.0 0.5)
   :disconnected (hsl :red 1.0 0.5)
   :active (hsl :red 1.0 0.5)
   :predicted (hsl :blue 1.0 0.5 0.5)
   :active-predicted (hsl :purple 1.0 0.4)
   :highlight (hsl :yellow 1 0.65 0.6)
   :temporal-pooling (hsl :green 1 0.5 0.4)
   })

(def default-viz-options
  {:input {:active true
           :predicted true
           :refresh-index 0}
   :columns {:active true
             :overlaps nil
             :boosts nil
             :active-freq nil
             :n-segments nil
             :predictive true
             :temporal-pooling true
             :refresh-index 0}
   :ff-synapses {:to :selected ;; :selected, :all, :none
                 :growing true
                 :inactive nil
                 :disconnected nil
                 :permanences true}
   :distal-synapses {:from :selected ;; :selected, :all, :none
                     :growing true
                     :inactive nil
                     :disconnected nil
                     :permanences true}
   :keep-steps 50
   ;; triggers a rebuild & redraw of the layouts when changed:
   :drawing {:display-mode :one-d ;; :one-d, :two-d
             :draw-steps 16
             :height-px nil ;; set on resize
             :width-px nil ;; set on resize
             :top-px 30
             :bit-w-px 4
             :bit-h-px 3
             :bit-shrink 0.85
             :col-d-px 5
             :col-shrink 0.85
             :cell-r-px 10
             :seg-w-px 30
             :seg-h-px 8
             :seg-h-space-px 55
             :h-space-px 45
             :anim-go? true
             :anim-every 1}})

(defn draw-image-dt
  [ctx lay dt img]
  (let [[x y] (lay/origin-px-topleft lay dt)]
    (c/draw-image ctx img x y)))

(defn all-layout-paths
  [m]
  (let [input-paths (for [inp-id (keys (:inputs m))]
                      [:inputs inp-id])
        layer-paths (for [[rgn-id rgn] (:regions m)
                          lyr-id (keys rgn)]
                      [:regions rgn-id lyr-id])]
    (concat input-paths layer-paths)))

(defn map-layouts
  ([f layouts]
   (map-layouts f (all-layout-paths layouts)))
  ([f layouts paths]
   (->> paths
        (map (fn [path]
               (get-in layouts path)))
        (map f))))

(defn reset-layout-caches
  [m]
  (reduce (fn [m path]
            (update-in m path vary-meta
                       (fn [mm]
                         (assoc mm ::cache (atom {})))))
          m
          (all-layout-paths m)))

(defn init-grid-layouts
  [step opts]
  (let [inputs (:inputs step)
        regions (:regions step)
        layerseq (mapcat (fn [rgn-id]
                           (map vector (repeat rgn-id)
                                (keys (regions rgn-id))))
                         (keys regions))
        d-opts (:drawing opts)
        display-mode (:display-mode d-opts)
        spacer (:h-space-px d-opts)
        top-px (:top-px d-opts)
        height-px (- (:height-px d-opts) top-px)
        ;; for now draw inputs and layers in a horizontal stack
        [i-lays i-right]
        (reduce (fn [[lays left] inp-id]
                  (let [topo (:topology (inputs inp-id))
                        lay (lay/grid-layout topo top-px left height-px d-opts
                                             true display-mode)]
                    [(assoc lays inp-id lay)
                     (+ (lay/right-px lay) spacer)]))
                [{} 20]
                (keys inputs))
        [r-lays r-right]
        (reduce (fn [[lays left] [rgn-id lyr-id]]
                  (let [topo (:topology (get-in regions [rgn-id lyr-id]))
                        lay (lay/grid-layout topo top-px left height-px d-opts
                                             false display-mode)]
                    [(assoc-in lays [rgn-id lyr-id] lay)
                     (+ (lay/right-px lay) spacer)]))
                [{} i-right]
                layerseq)]
    {:inputs i-lays
     :regions r-lays}))

(defn rebuild-layouts
  "Used when the model remains the same but the display has
  changed. Maintains any sorting and facets on each layer/input
  layout. I.e. replaces the GridLayout within each OrderableLayout."
  [viz-layouts step opts]
  (let [grid-layouts (init-grid-layouts step opts)]
    (->
     (reduce (fn [m path]
               (update-in m path assoc :layout (get-in grid-layouts path)))
             viz-layouts
             (all-layout-paths viz-layouts))
     (reset-layout-caches))))

(defn init-layouts
  [step opts]
  (let [grid-layouts (init-grid-layouts step opts)]
    (->
     (reduce (fn [m path]
               (update-in m path
                          (fn [lay]
                            (lay/orderable-layout lay (p/size-of lay)))))
             grid-layouts
             (all-layout-paths grid-layouts))
     (reset-layout-caches))))

(defn update-dt-offsets!
  [viz-layouts sel-dt opts]
  (swap! viz-layouts
         (fn [m]
           (let [draw-steps (get-in opts [:drawing :draw-steps])
                 dt0 (max 0 (- sel-dt (quot draw-steps 2)))]
             (-> (reduce (fn [m path]
                           (update-in m path assoc-in [:layout :dt-offset] dt0))
                         m
                         (all-layout-paths m))
                 (reset-layout-caches))))))

(def path->invalidate-path
  {:regions :columns
   :inputs :input})

(defn invalidate!
  [viz-options paths]
  (swap! viz-options
         #(reduce (fn [m [t]]
                    (update-in m [(path->invalidate-path t) :refresh-index]
                               inc))
                  % paths)))

(defn scroll!
  [viz-layouts viz-options paths down?]
  (swap! viz-layouts
         (fn [m]
           (reduce (fn [m path]
                     (update-in m path lay/scroll down?))
                   m
                   paths)))
  (invalidate! viz-options paths))

(defn active-ids
  [viz-step path]
  (let [[lyr-type & _] path]
    (-> (:inbits-cols viz-step)
        (get-in path)
        (get (case lyr-type
               :regions :active-columns
               :inputs :active-bits))
        sort)))

(defn add-facets!
  [viz-layouts viz-options paths step]
  (swap! viz-layouts
         (fn [m]
           (reduce (fn [m path]
                     (update-in m path
                                (fn [lay]
                                  (lay/add-facet lay
                                                 (active-ids step path)
                                                 (:timestep step)))))
                   m
                   paths)))
  (invalidate! viz-options paths))

(defn clear-facets!
  [viz-layouts viz-options paths]
  (swap! viz-layouts
         (fn [m]
           (reduce (fn [m path]
                     (update-in m path lay/clear-facets))
                   m
                   paths)))
  (invalidate! viz-options paths))

(defn sort!
  [viz-layouts viz-options paths viz-steps sel-dt]
  (let [use-steps (max 2 (get-in @viz-options [:drawing :draw-steps]))
        viz-steps (->> viz-steps (drop sel-dt) (take use-steps))]
    (swap! viz-layouts
           (fn [m]
             (reduce (fn [m path]
                       (update-in m path
                                  (fn [lay]
                                    (->> (map active-ids viz-steps (repeat path))
                                         (lay/sort-by-recent-activity lay)))))
                     m
                     paths))))
  (invalidate! viz-options paths))

(defn clear-sort!
  [viz-layouts viz-options paths]
  (swap! viz-layouts
         (fn [m]
           (reduce (fn [m path]
                     (update-in m path lay/clear-sort))
                   m
                   paths)))
  (invalidate! viz-options paths))

(defn draw-ff-synapses
  [ctx ff-synapses-response steps r-lays i-lays]
  (c/save ctx)
  (c/stroke-width ctx 1)
  (c/alpha ctx 1)

  (doseq [ff-synapse-group (vals ff-synapses-response)
          [sel1 ff-synapses] ff-synapse-group
          :let [{:keys [model-id]} sel1
                dt (utilv/index-of steps #(= model-id (:model-id %)))]]
    (doseq [[[rgn-id lyr-id col] synapses] ff-synapses
            :let [this-lay (get-in r-lays [rgn-id lyr-id])
                  [this-x this-y] (element-xy this-lay col dt)]]
      (doseq [{:keys [src-id src-col perm src-lyr syn-state]} synapses
              :let [src-lay (or (get i-lays src-id)
                                (get-in r-lays [src-id src-lyr]))
                    [src-x src-y] (element-xy src-lay src-col dt)]]
        (doto ctx
          (c/stroke-style (state-colors syn-state))
          (c/alpha (if perm perm 1))
          (c/begin-path)
          (c/move-to (- this-x 1) this-y) ;; -1 avoid obscuring colour
          (c/line-to (+ src-x 1) src-y)
          (c/stroke)))))

  (c/restore ctx)
  ctx)

(defn natural-curve
  [ctx x0 y0 x1 y1]
  (let [x-third (/ (- x1 x0) 3)]
    (c/bezier-curve-to ctx
                       (- x1 x-third) y0
                       (+ x0 x-third) y1
                       x1 y1)))

(defprotocol PCellsSegmentsLayout
  (seg-xy [this ci si])
  (cell-xy [this ci])
  (col-cell-line [this ctx ci])
  (cell-seg-line [this ctx ci si])
  (clicked-seg [this x y]))

(defn cells-segments-layout
  [col nsegbycell cols-lay dt cells-left opts]
  (let [nsegbycell-pad (map (partial max 1) nsegbycell)
        nseg-pad (apply + nsegbycell-pad)
        d-opts (:drawing opts)
        segs-left (+ cells-left (:seg-h-space-px d-opts))
        col-d-px (:col-d-px d-opts)
        col-r-px (* col-d-px 0.5)
        cell-r-px (:cell-r-px d-opts)
        seg-h-px (:seg-h-px d-opts)
        seg-w-px (:seg-w-px d-opts)
        our-height (:height-px d-opts)
        our-top (+ (:top-px d-opts) cell-r-px)
        [col-x col-y] (element-xy cols-lay col dt)]
    (reify PCellsSegmentsLayout
      (seg-xy
        [_ ci si]
        (let [i-all (apply + si (take ci nsegbycell-pad))
              frac (/ i-all nseg-pad)]
          [segs-left
           (+ our-top (* frac our-height))]))
      (cell-xy
        [this ci]
        (let [[_ sy] (seg-xy this ci 0)]
          [cells-left sy]))
      (col-cell-line
        [this ctx ci]
        (let [[cell-x cell-y] (cell-xy this ci)]
          (doto ctx
            (c/begin-path)
            (c/move-to (+ col-x col-r-px 1) col-y) ;; avoid obscuring colour
            (natural-curve col-x col-y cell-x cell-y)
            (c/stroke))))
      (cell-seg-line
        [this ctx ci si]
        (let [[cell-x cell-y] (cell-xy this ci)
              [sx sy] (seg-xy this ci si)]
          (doto ctx
            (c/begin-path)
            (c/move-to sx sy)
            (c/line-to (+ cell-x cell-r-px) cell-y)
            (c/stroke))))
      (clicked-seg
        [this x y]
        (when (<= (- cells-left cell-r-px) x
                  (+ segs-left seg-w-px 5))
          (first (for [[ci nsegs] (map-indexed vector nsegbycell)
                       si (range nsegs)
                       :let [[_ seg-y] (seg-xy this ci si)]
                       :when (<= (- seg-y seg-h-px) y
                                 (+ seg-y seg-h-px 5))]
                   [ci si])))))))

(defn draw-cell-segments
  [ctx cell-segments-response steps r-lays i-lays opts cells-left
   current-cell-segments-layout]
  (c/save ctx)
  (let [[sel1 cell-segments] cell-segments-response
        {col :bit model-id :model-id sel-ci-si :cell-seg} sel1
        [sel-rgn sel-lyr] (sel/layer sel1)
        dt (utilv/index-of steps #(= model-id (:model-id %)))]
    (when (and dt col sel-lyr)
      (let [lay (get-in r-lays [sel-rgn sel-lyr])
            n-segs-by-cell (->> cell-segments
                                (into (sorted-map))
                                (map (fn [[_ d]]
                                       (-> d :segments count))))
            cslay (cells-segments-layout col n-segs-by-cell lay dt cells-left opts)
            col-d-px (get-in opts [:drawing :col-d-px])
            cell-r-px (get-in opts [:drawing :cell-r-px])
            seg-h-px (get-in opts [:drawing :seg-h-px])
            seg-w-px (get-in opts [:drawing :seg-w-px])
            seg-r-px (* seg-w-px 0.5)]
        ;; for the click handler to use
        (reset! current-cell-segments-layout cslay)
        (doseq [[ci cell-data] cell-segments
                :let [[cell-x cell-y] (cell-xy cslay ci)
                      {:keys [cell-active? cell-predictive? selected-cell?
                              cell-state segments]} cell-data]]
          ;; draw background lines to cell from column and from segments
          (c/stroke-width ctx col-d-px)
          (c/stroke-style ctx (:background state-colors))
          (col-cell-line cslay ctx ci)
          (doseq [si (range (count segments))]
            (cell-seg-line cslay ctx ci si))
          (when cell-active?
            (doto ctx
              (c/stroke-style (:active state-colors))
              (c/stroke-width 2))
            (col-cell-line cslay ctx ci))
          ;; draw the cell itself
          (when selected-cell?
            (doto ctx
              (c/fill-style (:highlight state-colors))
              (c/circle {:x cell-x :y cell-y :r (+ cell-r-px 8)})
              (c/fill)))
          (doto ctx
            (c/fill-style (state-colors cell-state))
            (c/stroke-style "black")
            (c/stroke-width 1)
            (c/circle {:x cell-x :y cell-y :r cell-r-px})
            (c/stroke)
            (c/fill))
          (c/fill-style ctx "black")
          (c/text ctx {:text (str "cell " ci)
                       :x (+ cell-x 10) :y (- cell-y cell-r-px 5)})
          ;; draw each segment
          (doseq [[si seg] segments
                  :let [[sx sy] (seg-xy cslay ci si)
                        {:keys [learn-seg? selected-seg? n-conn-act n-conn-tot
                                n-dis-act n-dis-tot stimulus-th learning-th
                                syns-by-state]} seg
                        scale-factor (/ seg-w-px stimulus-th)
                        scale #(-> % (* scale-factor) int)

                        h2 (int (/ seg-h-px 2))
                        conn-th-r {:x sx :y (- sy h2)
                                   :w seg-w-px :h seg-h-px}
                        conn-tot-r (assoc conn-th-r :w (scale n-conn-tot))
                        conn-act-r (assoc conn-th-r :w (scale n-conn-act))
                        disc-th-r {:x sx :y (+ sy h2)
                                   :w (scale learning-th) :h seg-h-px}
                        disc-tot-r (assoc disc-th-r :w (scale n-dis-tot))
                        disc-act-r (assoc disc-th-r :w (scale n-dis-act))]]
            ;; draw segment as a rectangle
            (when selected-seg?
              (doto ctx
                (c/fill-style (:highlight state-colors))
                (c/fill-rect {:x (- sx 5) :y (- sy h2 5)
                              :w (+ seg-w-px 5 5) :h (+ (* 2 seg-h-px) 5 5)})))
            (doto ctx
              (c/fill-style "white") ;; overlay on highlight rect
              (c/fill-rect conn-th-r)
              (c/fill-rect disc-th-r)
              (c/fill-style (:background state-colors))
              (c/fill-rect conn-tot-r)
              (c/fill-rect disc-tot-r)
              (c/stroke-style "black")
              (c/stroke-width 1)
              (c/fill-style (:active state-colors))
              (c/fill-rect conn-act-r)
              (c/stroke-rect conn-th-r)
              (c/alpha 0.5)
              (c/fill-rect disc-act-r)
              (c/stroke-rect disc-th-r)
              (c/alpha 1.0))
            (when (>= n-conn-act stimulus-th)
              (doto ctx
                (c/stroke-style (:active state-colors))
                (c/stroke-width 2))
              (cell-seg-line cslay ctx ci si))
            (c/fill-style ctx "black")
            (c/text-align ctx :right)
            (c/text ctx {:text (str "seg " si "") :x (- sx 3) :y sy})
            (c/text-align ctx :start)
            (when learn-seg?
              (c/text ctx {:text (str "learning") :x (+ sx seg-w-px 10) :y sy}))
            ;; draw distal synapses
            (c/stroke-width ctx 1)
            (doseq [[syn-state syns] syns-by-state]
              (c/stroke-style ctx (state-colors syn-state))
              (doseq [{:keys [src-col src-id src-lyr perm]} syns
                      :let [src-lay (or (get i-lays src-id)
                                        (get-in r-lays [src-id src-lyr]))
                            [src-x src-y] (element-xy src-lay src-col (inc dt))]]
                (when perm (c/alpha ctx perm))
                (doto ctx
                  (c/begin-path)
                  (c/move-to sx sy)
                  (c/line-to (+ src-x 1) src-y) ;; +1 avoid obscuring colour
                  (c/stroke))))
            (c/alpha ctx 1.0)))
        (c/restore ctx))))
  ctx)

(defn image-buffer
  [{:keys [w h]}]
  (let [el (dom/createElement "canvas")]
    (set! (.-width el) w)
    (set! (.-height el) h)
    el))

(defn bg-image
  [lay]
  (let [el (image-buffer (layout-bounds lay))
        ctx (c/get-context el "2d")]
    (c/fill-style ctx (:background state-colors))
    (fill-elements lay ctx (lay/ids-onscreen lay))
    el))

(defn fill-ids-image
  [lay fill-style ids]
  (let [el (image-buffer (layout-bounds lay))
        ctx (c/get-context el "2d")]
    (c/fill-style ctx fill-style)
    (fill-elements lay ctx ids)
    el))

(defn fill-ids-alpha-image
  [lay fill-style id->alpha]
  (let [el (image-buffer (layout-bounds lay))
        ctx (c/get-context el "2d")]
    (c/fill-style ctx fill-style)
    (group-and-fill-elements lay ctx id->alpha c/alpha)
    el))

(defn break-image
  [lay]
  (let [el (image-buffer (layout-bounds lay))]
    (doto (c/get-context el "2d")
      (c/stroke-style "black")
      (c/stroke-width 2)
      (c/begin-path)
      (c/move-to 0.5 0)
      (c/line-to 0.5 (.-height el))
      (c/stroke)
      (c/stroke-width 1))
    el))

(defn scroll-status-str
  [lay inbits?]
  (let [idx (lay/scroll-position lay)
        page-n (lay/ids-onscreen-count lay)
        n-ids (p/size-of lay)]
    (str page-n
         " of "
         n-ids
         (if inbits? " bits" " cols")
         (if (pos? idx)
           (str " @ "
                (long (* 100 (/ idx (- n-ids page-n))))
                "%")
           ""))))

(defn should-draw? [steps opts]
  (let [{:keys [anim-go? anim-every height-px]} (:drawing opts)
        most-recent (first steps)]
    (when (and anim-go? most-recent height-px)
      (let [t (:timestep most-recent)]
        (zero? (mod t anim-every))))))

(defn draw-timeline!
  [ctx steps sel opts]
  (let [sel-dts (into #{} (map :dt sel))
        current-t (:timestep (first steps))
        keep-steps (:keep-steps opts)
        width-px (.-width (.-canvas ctx))
        height-px (.-height (.-canvas ctx))
        t-width (/ width-px keep-steps)
        y-px (/ height-px 2)
        r-px (min y-px (* t-width 0.5))
        sel-r-px y-px]
    (c/clear-rect ctx {:x 0 :y 0 :w width-px :h height-px})
    (c/text-align ctx :center)
    (c/text-baseline ctx :middle)
    (c/font-style ctx "bold 10px sans-serif")
    (doseq [dt (reverse (range keep-steps))
            :let [t (- current-t dt)
                  kept? (< dt (count steps))
                  sel? (contains? sel-dts dt)
                  x-px (- (dec width-px) r-px (* dt t-width))]]
      (c/fill-style ctx "black")
      (c/alpha ctx (cond sel? 1.0 kept? 0.3 :else 0.1))
      (c/circle ctx {:x x-px :y y-px :r (if sel? sel-r-px r-px)})
      (c/fill ctx)
      (when (or sel?
                (and kept? (< keep-steps 100)))
        (c/fill-style ctx "white")
        (c/text ctx {:x x-px :y y-px :text (str t)})))
    (c/alpha ctx 1.0)))

(defn timeline-click
  [e steps selection opts]
  (let [{:keys [x y]} (offset-from-target e)
        append? (.-metaKey e)
        keep-steps (:keep-steps opts)
        width-px (.-width (.-target e))
        t-width (/ width-px keep-steps)
        click-dt (quot (- (dec width-px) x) t-width)]
    (when (< click-dt (count steps))
      (let [sel1 {:dt click-dt
                  :model-id (nth steps click-dt)}]
       (if append?
         (let [same-dt? #(= click-dt (:dt %))]
           (if (some same-dt? @selection)
             (if (> (count @selection) 1)
               (swap! selection
                      (fn [sel]
                        (into (empty sel) (remove same-dt? sel))))
               (swap! selection sel/clear))
             (swap! selection conj sel1)))
         (swap! selection #(conj (empty %)
                                 (merge (peek @selection) sel1))))))))

(defn viz-timeline [viz-steps selection viz-options]
  [resizing-canvas
   {:on-click #(timeline-click % @viz-steps selection @viz-options)
    :style {:width "100%"
            :height "2em"}}
   [viz-steps selection viz-options]
   (fn [ctx]
     (let [steps @viz-steps
           opts @viz-options]
       (when (should-draw? steps opts)
         (draw-timeline! ctx steps @selection opts))))
   nil])

(defn draw-viz!
  [ctx viz-steps ff-synapses-response cell-segments-response layouts sel opts
   current-cell-segments-layout]
  (let [i-lays (:inputs layouts)
        r-lays (:regions layouts)

        d-opts (:drawing opts)

        draw-steps (case (:display-mode d-opts)
                     :one-d (:draw-steps d-opts)
                     :two-d 1)

        center-dt (:dt (peek sel))
        draw-dts (if (== 1 center-dt)
                   [center-dt]
                   ;; in case scrolled back in history
                   (let [dt0 (max 0 (- center-dt (quot draw-steps 2)))]
                     (range dt0 (min (+ dt0 draw-steps)
                                     (count viz-steps)))))

        label-top-px 0
        cells-left (->> (mapcat vals (vals r-lays))
                        (map lay/right-px)
                        (apply max)
                        (+ (:seg-h-space-px d-opts)))]

    ;; Draw whenever a new step appears, even when the step's inbits / column
    ;; data is not yet available. This keeps the viz-canvas in sync with the
    ;; timeline without having to share viz-canvas internal state, and it
    ;; handles cases where e.g. the ff-synapse data arrives before the
    ;; inbits-cols data.

    (c/clear-rect ctx {:x 0 :y 0
                       :w (.-width (.-canvas ctx))
                       :h (.-height (.-canvas ctx))})

    ;; draw labels
    (c/text-align ctx :start)
    (c/text-baseline ctx :top)
    (c/font-style ctx "10px sans-serif")
    (c/fill-style ctx "black")

    (doseq [[inp-id lay] i-lays]
      (c/text ctx {:text (name inp-id)
                   :x (:x (layout-bounds lay))
                   :y label-top-px})
      (c/text ctx {:text (scroll-status-str lay true)
                   :x (:x (layout-bounds lay))
                   :y (+ label-top-px 10)}))
    (doseq [[rgn-id lyr-lays] r-lays
            [lyr-id lay] lyr-lays]
      (c/text ctx {:text (str (name rgn-id) " " (name lyr-id))
                   :x (:x (layout-bounds lay))
                   :y label-top-px})
      (c/text ctx {:text (scroll-status-str lay false)
                   :x (:x (layout-bounds lay))
                   :y (+ label-top-px 10)}))

    (when cell-segments-response
      (c/text ctx {:text "Cells and distal dendrite segments."
                   :x cells-left :y label-top-px}))

    (doseq [dt draw-dts
            :let [{sc :cache inbits-cols :inbits-cols} (nth viz-steps dt)
                  {:keys [inputs regions]} inbits-cols]]
      ;; draw encoded inbits
      (doseq [[inp-id {:keys [active-bits pred-bits-alpha]}] inputs
              :let [lay (i-lays inp-id)
                    lay-cache (::cache (meta lay))]]
        (->> (bg-image lay)
             (with-cache lay-cache [::bg inp-id] opts #{:drawing})
             (draw-image-dt ctx lay dt))
        (when active-bits
          (->> active-bits
               (fill-ids-image lay (:active state-colors))
               (with-cache sc [::abits inp-id] opts #{:input :drawing})
               (draw-image-dt ctx lay dt)))
        (when pred-bits-alpha
          (->> pred-bits-alpha
               (fill-ids-alpha-image lay (:predicted state-colors))
               (with-cache sc [::pbits inp-id] opts #{:input :drawing})
               (draw-image-dt ctx lay dt))))

      ;; draw regions / layers
      (doseq [[rgn-id rgn-data] regions]
        (doseq [[lyr-id {:keys [overlaps-columns-alpha
                                boost-columns-alpha
                                active-freq-columns-alpha
                                n-segments-columns-alpha
                                active-columns
                                pred-columns
                                tp-columns
                                break?]}] rgn-data
                :let [uniqix (str (name rgn-id) (name lyr-id))
                      lay (get-in r-lays [rgn-id lyr-id])
                      lay-cache (::cache (meta lay))]]
          (->> (bg-image lay)
               (with-cache lay-cache [::bg uniqix] opts #{:drawing})
               (draw-image-dt ctx lay dt))
          (when overlaps-columns-alpha
            (->> overlaps-columns-alpha
                 (fill-ids-alpha-image lay "black")
                 (with-cache sc [::ocols uniqix] opts #{:columns :drawing})
                 (draw-image-dt ctx lay dt)))
          (when boost-columns-alpha
            (->> boost-columns-alpha
                 (fill-ids-alpha-image lay "black")
                 (with-cache sc [::boosts uniqix] opts #{:columns :drawing})
                 (draw-image-dt ctx lay dt)))
          (when active-freq-columns-alpha
            (->> active-freq-columns-alpha
                 (fill-ids-alpha-image lay "black")
                 (with-cache sc [::afreq uniqix] opts #{:columns :drawing})
                 (draw-image-dt ctx lay dt)))
          (when n-segments-columns-alpha
            (->> n-segments-columns-alpha
                 (fill-ids-alpha-image lay "black")
                 (with-cache sc [::nsegcols uniqix] opts #{:columns :drawing})
                 (draw-image-dt ctx lay dt)))
          (when (and active-columns
                     (get-in opts [:columns :active]))
                (->> active-columns
                     (fill-ids-image lay (:active state-colors))
                     (with-cache sc [::acols uniqix] opts #{:columns :drawing})
                     (draw-image-dt ctx lay dt)))
          (when pred-columns
            (->> pred-columns
                 (fill-ids-image lay (:predicted state-colors))
                 (with-cache sc [::pcols uniqix] opts #{:columns :drawing})
                 (draw-image-dt ctx lay dt)))
          (when tp-columns
            (->> tp-columns
                 (fill-ids-image lay (:temporal-pooling state-colors))
                 (with-cache sc [::tpcols uniqix] opts #{:columns :drawing})
                 (draw-image-dt ctx lay dt)))
          (when break?
            (->> (break-image lay)
                 (draw-image-dt ctx lay dt))))))

    ;; mark facets
    (doseq [lay (vals i-lays)]
      (lay/draw-facets lay ctx))
    (doseq [lay (mapcat vals (vals r-lays))]
      (lay/draw-facets lay ctx))
    ;; highlight selection
    (when (and (> draw-steps 1)
               (= 1 (count sel)))
      (doseq [dt (map :dt sel)]
        (doseq [lay (vals i-lays)]
          (lay/highlight-dt lay ctx dt (:highlight state-colors)))
        (doseq [lay (mapcat vals (vals r-lays))]
          (lay/highlight-dt lay ctx dt (:highlight state-colors)))))
    (doseq [{:keys [path bit dt]} sel
            :when path
            :let [lay (get-in layouts path)]]
      (lay/highlight-layer lay ctx (:highlight state-colors))
      (when bit
        (lay/highlight-element lay ctx dt bit bit (:highlight state-colors))))

    ;; draw ff synapses
    (draw-ff-synapses ctx ff-synapses-response viz-steps r-lays i-lays)

    ;; draw selected cells and segments
    (draw-cell-segments ctx cell-segments-response viz-steps r-lays i-lays opts
                        cells-left current-cell-segments-layout)))

(def code-key
  {32 :space
   33 :page-up
   34 :page-down
   37 :left
   38 :up
   39 :right
   40 :down})

(def key->control-k
  {:left :step-backward
   :right :step-forward
   :up :bit-up
   :down :bit-down
   :page-up :scroll-up
   :page-down :scroll-down
   :space :toggle-run})

(defn viz-key-down
  [e commands-in]
  (if-let [k (code-key (.-keyCode e))]
    (do
      (put! commands-in [(key->control-k k)])
      (.preventDefault e))
    true))

(defn viz-click
  [e steps selection layouts current-cell-segments-layout]
  (let [{:keys [x y]} (offset-from-target e)
        append? (.-metaKey e)
        i-lays (:inputs layouts)
        r-lays (:regions layouts)
        ;; we need to assume there is a previous step, so:
        max-dt (max 0 (- (count steps) 2))
        hit? (atom false)]
    ;; check inputs and regions
    (doseq [path (all-layout-paths layouts)
            :let [lay (get-in layouts path)
                  [dt id] (lay/clicked-id lay x y)]
            :when dt
            :let [dt (if (== 1 (count (p/dims-of lay)))
                       (min dt max-dt)
                       (:dt (peek @selection)))
                  sel1 {:path path :bit id :dt dt
                        :model-id (:model-id (nth steps dt))}]]
      (reset! hit? true)
      (if append?
        (let [same-bit? #(and (= dt (:dt %))
                              (= id (:bit %))
                              (= path (:path %)))]
          (if (some same-bit? @selection)
            (if (> (count @selection) 1)
              (swap! selection #(into (empty %) (remove same-bit? %)))
              (swap! selection sel/clear))
            (swap! selection conj sel1)))
        (swap! selection #(conj (empty %) sel1))))
    ;; check cells
    (when ((every-pred sel/layer #(pos? (:bit %))) (peek @selection))
      (when-let [cslay @current-cell-segments-layout]
        (when-let [[ci si] (clicked-seg cslay x y)]
          (reset! hit? true)
          (swap! selection #(conj (pop %)
                                  (assoc (peek %)
                                         :cell-seg [ci si]))))))
    (when-not (or append? @hit?)
      ;; checked all, nothing clicked
      (swap! selection sel/clear))))

(defn absorb-step-template [step-template viz-options viz-layouts]
  (reset! viz-layouts
          (init-layouts step-template @viz-options)))

(defn fetch-ff-synapses!
  [into-journal ff-synapses-response sel opts viewport-token local-targets]
  (let [;; dt may be outdated when this is consumed
        sels (map #(dissoc % :dt) sel)]
    (swap! ff-synapses-response
           #(-> %
                (update :in-synapses select-keys sels)
                (update :out-synapses select-keys sels)))
    (doseq [{:keys [bit model-id] :as sel1} sels]
      ;; in-synapses
      (when-let [[rgn-id lyr-id] (sel/layer sel1)]
        (let [to (get-in opts [:ff-synapses :to])
              [continue? only-ids] (case to
                                     :all [true nil]
                                     :selected (if bit
                                                 [true [bit]]
                                                 [false])
                                     :default [false])]
          (when continue?
            (let [response-c (async/chan)]
              (go
                (swap! ff-synapses-response assoc-in
                       [:in-synapses sel1] (<! response-c)))
              (put! @into-journal [:get-ff-in-synapses model-id rgn-id lyr-id
                                   only-ids viewport-token
                                   (channel-proxy/register! local-targets
                                                            response-c)])
              true))))
      ;; out-synapses
      (when bit
        (when-let [inp-id (sel/input sel1)]
          (let [response-c (async/chan)]
            (go
              (swap! ff-synapses-response assoc-in
                     [:out-synapses sel1] (<! response-c)))
            (put! @into-journal [:get-ff-out-synapses model-id inp-id bit
                                 viewport-token
                                 (channel-proxy/register! local-targets
                                                          response-c)])
            true))))))

(defn fetch-cell-segments!
  [into-journal cell-segments-response sel viewport-token local-targets]
  (when-not (and (= (count sel) 1)
                 (let [sel1 (peek sel)
                       {:keys [path bit model-id cell-seg]} sel1
                       [rgn-id lyr-id] (sel/layer sel1)]
                   (when (and rgn-id bit)
                     (let [response-c (async/chan)]
                       (go
                         ;; dt may be outdated at this point
                         (reset! cell-segments-response [(dissoc sel1 :dt)
                                                         (<! response-c)]))
                       (put! @into-journal
                             [:get-cell-segments model-id rgn-id lyr-id bit
                              cell-seg viewport-token
                              (channel-proxy/register! local-targets
                                                       response-c)])
                       true))))
    (reset! cell-segments-response nil)))

(defn fetch-inbits-cols!
  [into-journal steps-data steps viewport-token local-targets]
  (doseq [step steps
          :let [model-id (:model-id step)
                response-c (async/chan)]]
    (put! @into-journal [:get-inbits-cols model-id viewport-token
                         (channel-proxy/register! local-targets
                                                  response-c)])
    (go
      (swap! steps-data assoc-in [step :inbits-cols] (<! response-c)))))

(defn push-new-viewport!
  [into-journal viewport-token step-template layouts opts local-targets]
  (let [paths (all-layout-paths step-template)
        path->ids-onscreen (zipmap paths (map-layouts lay/ids-onscreen layouts
                                                      paths))
        viewport [opts path->ids-onscreen]
        response-c (async/chan)]
    (put! @into-journal [:register-viewport viewport
                         (channel-proxy/register! local-targets response-c)])
    (go
      (reset! viewport-token (<! response-c)))))

;; A "viz-step" is a step with viz-canvas-specific data added.
(defn make-viz-step
  [step steps-data]
  (merge step (get steps-data step)))

(defn make-viz-steps
  [steps steps-data]
  (map make-viz-step steps (repeat steps-data)))

(defn absorb-new-steps!
  [steps-v steps-data into-journal viewport-tok local-targets]
  (let [new-steps (->> steps-v
                       (remove (partial contains?
                                        @steps-data)))]
    (swap! steps-data
           #(into (select-keys % steps-v) ;; remove old steps
                  (for [step new-steps] ;; insert new caches
                    [step {:cache (atom {})}])))
    (fetch-inbits-cols! into-journal steps-data new-steps
                        viewport-tok local-targets)))

(defn ids-onscreen-changed?
  [before after]
  (let [paths (all-layout-paths after)
        ;; TODO should also consider height
        extractor (juxt lay/scroll-position :order)]
    (not= (map-layouts extractor before paths)
          (map-layouts extractor after paths))))

(defn viz-canvas
  [_ steps selection step-template viz-options into-viz into-sim
   into-journal local-targets]
  (let [steps-data (atom {})
        ff-synapses-response (atom nil)
        cell-segments-response (atom nil)
        viz-layouts (atom nil)
        viewport-token (atom nil)
        current-cell-segments-layout (clojure.core/atom nil)
        resizes (async/chan)
        size-invalidates-c (async/chan)
        into-viz (or into-viz (async/chan))
        teardown-c (async/chan)]
    (go-loop []
      (when-let [[command & xs] (alt! teardown-c nil
                                      into-viz ([v] v)
                                      :priority true)]
        (case command
          :sort (let [[apply-to-all?] xs]
                  (sort! viz-layouts viz-options
                         (if apply-to-all?
                           (all-layout-paths @viz-layouts)
                           (map :path @selection))
                         (make-viz-steps @steps @steps-data)
                         (:dt (peek @selection))))
          :clear-sort (let [[apply-to-all?] xs]
                        (clear-sort! viz-layouts viz-options
                                     (if apply-to-all?
                                       (all-layout-paths @viz-layouts)
                                       (map :path @selection))))
          :add-facet (let [[apply-to-all?] xs]
                       (add-facets! viz-layouts viz-options
                                    (if apply-to-all?
                                      (all-layout-paths @viz-layouts)
                                      (map :path @selection))
                                    (make-viz-step (nth @steps (:dt @selection))
                                                   @steps-data)))
          :clear-facets (let [[apply-to-all?] xs]
                          (clear-facets! viz-layouts viz-options
                                         (if apply-to-all?
                                           (all-layout-paths @viz-layouts)
                                           (map :path @selection))))
          :step-backward (let [ ;; we need to assume there is a previous step, so:
                               max-dt (max 0 (- (count @steps) 2))]
                          (swap! selection
                                 #(conj (pop %)
                                        (let [sel1 (peek %)
                                              dt (min (inc (:dt sel1)) max-dt)]
                                          (assoc sel1
                                                 :dt dt
                                                 :model-id (:model-id
                                                            (nth @steps dt)))))))
          :step-forward (if (some #(zero? (:dt %)) @selection)
                          (when (and into-sim @into-sim)
                            (put! @into-sim [:step]))
                          (swap! selection
                                 #(conj (pop %)
                                        (let [sel1 (peek %)
                                              dt (dec (:dt (peek %)))]
                                          (assoc sel1
                                                 :dt dt
                                                 :model-id (:model-id
                                                            (nth @steps dt)))))))
          :bit-up (let [{:keys [path bit]} (peek @selection)]
                    (when bit
                      (let [lay (get-in @viz-layouts path)
                            order (:order lay)
                            idx (order bit)
                            next-bit (if (zero? idx)
                                       nil
                                       (let [next-idx (dec idx)]
                                         (key (first (subseq order >= next-idx
                                                             <= next-idx)))))]
                        (swap! selection #(conj (pop %)
                                                (assoc (peek %)
                                                       :bit next-bit))))))
          :bit-down (let [{:keys [path bit]} (peek @selection)
                          lay (get-in @viz-layouts path)
                          order (:order lay)
                          next-idx (if bit
                                     (inc (order bit))
                                     0)
                          next-bit (if (< next-idx (p/size-of lay))
                                     (key (first (subseq order >= next-idx <=
                                                         next-idx)))
                                     nil)]
                      (swap! selection #(conj (pop %)
                                              (assoc (peek %)
                                                     :bit next-bit))))
          :scroll-down (let [[apply-to-all?] xs]
                         (scroll! viz-layouts viz-options
                                  (if apply-to-all?
                                    (all-layout-paths @viz-layouts)
                                    [(:path @selection)])
                                  true))
          :scroll-up (let [[apply-to-all?] xs]
                       (scroll! viz-layouts viz-options
                                (if apply-to-all?
                                  (all-layout-paths @viz-layouts)
                                  [(:path @selection)])
                                false))
          :toggle-run (when (and into-sim @into-sim)
                        (put! @into-sim [:toggle])))
        (recur)))

    (go-loop []
      (when-let [[width-px height-px] (<! resizes)]
        (swap! viz-options (fn [opts]
                             (-> opts
                                 (assoc-in [:drawing :height-px] height-px)
                                 (assoc-in [:drawing :width-px] width-px))))
        (recur)))

    (reagent/create-class
     {:component-will-mount
      (fn [_]
        (when @step-template
          (absorb-step-template @step-template viz-options viz-layouts))

        (when (not-empty @steps)
          (add-watch viewport-token ::initial-steps-fetch
                     (fn [_ _ _ token]
                       (remove-watch viewport-token ::initial-steps-fetch)
                       (absorb-new-steps! @steps steps-data into-journal token
                                         local-targets))))

        (add-watch steps ::init-caches-and-request-data
                   (fn init-caches-and-request-data [_ _ _ xs]
                     (absorb-new-steps! xs steps-data into-journal
                                       @viewport-token local-targets)))
        (add-watch viewport-token ::fetch-everything
                   (fn fetch-everything [_ _ old-token token]
                     (fetch-inbits-cols! into-journal steps-data @steps token
                                         local-targets)
                     (fetch-ff-synapses! into-journal ff-synapses-response
                                         @selection @viz-options @viewport-token
                                         local-targets)
                     (fetch-cell-segments! into-journal cell-segments-response
                                           @selection @viewport-token
                                           local-targets)
                     (when old-token
                       (put! @into-journal [:unregister-viewport old-token]))))
        (add-watch viz-options ::viewport
                   (fn viewport<-opts [_ _ _ opts]
                     (when @into-journal
                       (push-new-viewport! into-journal viewport-token
                                           @step-template @viz-layouts opts
                                           local-targets))))
        (add-watch viz-layouts ::viewport
                   (fn viewport<-layouts [_ _ prev layouts]
                     (when (and @into-journal
                                prev
                                (ids-onscreen-changed? prev layouts))
                       (push-new-viewport! into-journal viewport-token
                                           @step-template layouts @viz-options
                                           local-targets))))
        (add-watch step-template ::absorb-step-template
                   (fn step-template-changed [_ _ _ template]
                     (absorb-step-template template viz-options viz-layouts)
                     (push-new-viewport! into-journal viewport-token
                                         template @viz-layouts @viz-options
                                         local-targets)))
        (add-watch viz-options ::rebuild-layouts
                   (fn layouts<-viz-options [_ _ old-opts opts]
                     (when (not= (:drawing opts)
                                 (:drawing old-opts))
                       (swap! viz-layouts rebuild-layouts @step-template
                              opts))))
        (add-watch selection ::update-dt-offsets
                   (fn dt-offsets<-selection [_ _ old-sel sel]
                     (let [dt-sel (:dt (peek sel))]
                       (when (not= dt-sel (:dt (peek old-sel)))
                         (update-dt-offsets! viz-layouts dt-sel @viz-options)))))
        (add-watch selection ::syns-segments
                   (fn fetch-selection-change [_ _ _ sel]
                     (fetch-ff-synapses! into-journal ff-synapses-response sel
                                         @viz-options @viewport-token
                                         local-targets)
                     (fetch-cell-segments! into-journal cell-segments-response
                                           sel @viewport-token
                                           local-targets))))

      :component-will-unmount
      (fn [_]
        (remove-watch steps ::init-caches-and-request-data)
        (remove-watch viewport-token ::fetch-everything)
        (remove-watch viz-options ::viewport)
        (remove-watch viz-layouts ::viewport)
        (remove-watch step-template ::absorb-step-template)
        (remove-watch viz-options ::rebuild-layouts)
        (remove-watch selection ::update-dt-offsets)
        (remove-watch selection ::syns-segments)
        (put! teardown-c :teardown))

      :display-name "viz-canvas"

      :reagent-render
      (fn [props _ _ _ _ _]
        [:div nil
         [window-resize-listener size-invalidates-c]
         [resizing-canvas
          (cond-> props
            @into-journal (assoc
                           :on-click #(viz-click % @steps selection @viz-layouts
                                                 current-cell-segments-layout)
                           :on-key-down #(viz-key-down % into-viz)))
          [selection steps steps-data ff-synapses-response
           cell-segments-response viz-layouts viz-options]
          (fn [ctx]
            (let [viz-steps (make-viz-steps @steps @steps-data)
                  opts @viz-options]
              (when (should-draw? viz-steps opts)
                (draw-viz! ctx viz-steps @ff-synapses-response
                           @cell-segments-response @viz-layouts @selection
                           opts current-cell-segments-layout))))
          size-invalidates-c
          resizes]])})))
