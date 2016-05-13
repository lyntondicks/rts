(ns ^:figwheel-always game.client.overlay
  (:require
    [cljs.pprint :as pprint]
    [com.stuartsierra.component :as component]
    [jayq.core :as jayq :refer [$]]
    [promesa.core :as p]
    [cats.core :as m]
    [rum.core :as rum]
    [game.client.common :as common :refer [new-jsobj list-item data unique-id]]
    [game.client.math :as math]
    [game.client.selection :as selection]
    [game.client.engine :as engine]
    [game.client.scene :as scene]
    [sablono.core :as sablono :refer-macros [html]]
    [clojure.string :as string :refer [join]]
    [game.shared.state :as state :refer [with-simple-cause]]
    )
  (:require-macros
    [infix.macros :refer [infix]]
    [game.shared.macros :as macros :refer [defcom]])
  )

(def xyz-size 3)
(def xyzw-size 4)

(defn new-cache [] #js [ #js [ #js [] #js [] #js [] #js [] #js [] ] ])
(defonce sprite-cache (new-cache))
(defonce new-sprite-cache (new-cache))

(defn get-cached-sprite
  [texture new-fn]
  (let
    [texture-id (-> texture .-rts-id)]
    (if
      (> (count (aget (aget sprite-cache 0) texture-id)) 0)
      (let
        [cached (-> (aget (aget sprite-cache 0) texture-id) .pop)]
        (do
          (-> (aget (aget new-sprite-cache 0) texture-id) (.push cached))
          cached
          ))
      (let
        [not-cached (new-fn)]
        (-> (aget (aget new-sprite-cache 0) texture-id) (.push not-cached))
        not-cached))))

(defn get-pixi-filter
  []
  (let
    [shader nil
     TestFilter
     (fn []
       (this-as
         this
         (-> js/PIXI.Filter
           (.call this nil shader #js { :gray #js { :type "1f" :value 1 } }))))
     ]
    (set!
      (-> TestFilter .-prototype)
      (-> js/Object (.create (-> js/PIXI.Filter .-prototype))))
    (set!
      (-> TestFilter .-prototype .-constructor)
      TestFilter)
    (new js/TestFilter)))

(def bar-block-width 12)
(def bar-height 8)
(def light-opacity 0.2)
(def shadow-opacity 0.4)
(def shadow-width 1)
(def shadow-height 2)
; max-blocks is important for performance, because a screen-box can exceed canvas width
(def max-blocks 20)
(def min-blocks 4)
(def line-width 1)

(defn
  get-texture
  [pixi-renderer width height color transparent]
  (let
    [
     line-width 1
     render-texture (-> js/PIXI .-RenderTexture
                      (.create (+ width (* 2 line-width)) (+ height (* 2 line-width))))
     graphics (new js/PIXI.Graphics)
     x1 line-width
     y1 line-width
     ]
    ; main block
    (-> graphics (.lineStyle 1 0x000000 1))
    (-> graphics (.beginFill color (if transparent 0 1)))
    (-> graphics
      (.drawRect x1 y1 (- width line-width) height))
    (-> graphics .endFill)
    ; top lighter
    (-> graphics (.lineStyle 0))
    (-> graphics (.beginFill 0xFFFFFF light-opacity))
    (-> graphics
      (.drawRect x1 y1 width shadow-height))
    (-> graphics .endFill)
    ; bottom darker
    (-> graphics (.lineStyle 0))
    (-> graphics (.beginFill 0x000000 shadow-opacity))
    (-> graphics
      (.drawRect x1 (+ height (- shadow-height) (+ 1)) width 1))
    (-> graphics .endFill)
    ; bottom darker level 2
    (-> graphics (.lineStyle 0))
    (-> graphics (.beginFill 0x000000 (/ shadow-opacity 2)))
    (-> graphics
      (.drawRect x1 (+ height (- shadow-height)) width 1))
    (-> graphics .endFill)
    ; left/right shadow
    (-> graphics (.lineStyle 0))
    (-> graphics (.beginFill 0x000000 shadow-opacity))
    (-> graphics
      (.drawRect x1 y1 shadow-width height))
    (-> graphics
      (.drawRect (+ width (- shadow-width) (- x1)) y1 shadow-width height))
    (-> graphics .endFill)

    (-> pixi-renderer (.render graphics render-texture))
    render-texture
    ))

(defn select-texture
  [red-texture orange-texture yellow-texture green-texture health]
  (cond
    (< health 0.25)
    red-texture
    (< health 0.5)
    orange-texture
    (< health 0.75)
    yellow-texture
    :else
    green-texture))

(defn draw-health-bar
  [component stage partial-select-texture transparent-texture index box]
  (let
    [mesh (aget box "mesh")
     unit (engine/get-unit-for-mesh (:units component) mesh)
     [x1 y1 x2 y2] box
     box-width (- x2 x1)
     bar-width (* bar-block-width (min (max (math/round (/ box-width bar-block-width)) min-blocks) max-blocks))
     ; center bar on box horizontally
     x1 (infix (box-width - bar-width) / 2 + x1)
;         x1 (- x1 (rem x1 bar-block-width))
     height (- y2 y1)
     health (/ (:health unit) (:max-health (:model unit)))
     health-width (* health bar-width)
     remainder (rem health-width bar-block-width)
     last-block-opacity (/ remainder bar-block-width)
     health-width (- health-width remainder)
     y1 (- y1 bar-height)
     texture (partial-select-texture health)
     ]
    (do
      ; full blocks
      (doseq
        [i (range 0 health-width bar-block-width)]
        (let
          [sprite (get-cached-sprite texture #(new js/PIXI.Sprite texture))]
          (-> stage (.addChild sprite))
          (-> sprite .-position .-x (set! (+ x1 i)))
          (-> sprite .-position .-y (set! y1))
          (-> sprite .-alpha (set! 1))))
      ; last semi-transparent block
      (let
        [sprite (get-cached-sprite texture #(new js/PIXI.Sprite texture))]
        (-> stage (.addChild sprite))
        (-> sprite .-position .-x (set! (+ x1 health-width)))
        (-> sprite .-position .-y (set! y1))
        (-> sprite .-alpha (set! last-block-opacity)))
      ; transparent blocks
      (doseq
        [i (range health-width bar-width bar-block-width)]
        (let
          [sprite (get-cached-sprite transparent-texture #(new js/PIXI.Sprite transparent-texture))]
          (-> stage (.addChild sprite))
          (-> sprite .-position .-x (set! (+ x1 i)))
          (-> sprite .-position .-y (set! y1)))))))

(defn on-render
  [init-renderer component]
  (let
    [health-bars (:health-bars component)
     t (common/game-time)
     screen-boxes (selection/get-screen-boxes component)
     mesh-to-screenbox-map (get-in component [:units :mesh-to-screenbox-map])
     new-mts-map (reduce (fn [dict box] (assoc dict (aget box "mesh") box)) {} screen-boxes)
     pixi-filter (get-pixi-filter)
     stage (:stage component)
     health-bars (new js/PIXI.Graphics)
     scene-width @(get-in component [:scene-properties :width])
     scene-height @(get-in component [:scene-properties :height])
     last-frame-time @(:last-60-average init-renderer)
     pixi-renderer (:pixi-renderer component)
     get-texture #(get-texture pixi-renderer bar-block-width bar-height %1 %2)
     green-texture (or @(:green-texture component) (get-texture 0x00FF00 false))
     yellow-texture (or @(:yellow-texture component) (get-texture 0xFFFF00 false))
     orange-texture (or @(:orange-texture component) (get-texture 0xFFA500 false))
     red-texture (or @(:red-texture component) (get-texture 0xFF0000 false))
     transparent-texture (or @(:transparent-texture component) (get-texture 0x000000 true))
     partial-select-texture (partial select-texture red-texture orange-texture yellow-texture green-texture)
     ]
    (reset! mesh-to-screenbox-map new-mts-map)
    (-> green-texture .-rts-id (set! 0))
    (-> yellow-texture .-rts-id (set! 1))
    (-> orange-texture .-rts-id (set! 2))
    (-> red-texture .-rts-id (set! 3))
    (-> transparent-texture .-rts-id (set! 4))
    (reset! (:green-texture component) green-texture)
    (reset! (:yellow-texture component) yellow-texture)
    (reset! (:orange-texture component) orange-texture)
    (reset! (:red-texture component) red-texture)
    (reset! (:transparent-texture component) transparent-texture)
    (-> stage .removeChildren)
    (-> stage (.addChild health-bars))
    (doseq
      [[i box] (map-indexed vector screen-boxes)]
      (draw-health-bar component stage partial-select-texture transparent-texture i box))
    (aset sprite-cache 0 (aget new-sprite-cache 0))
    (aset new-sprite-cache 0 (aget (new-cache) 0))))

(defcom
  new-overlay
  [$overlay params units camera renderer scene-properties]
  [pixi-renderer stage
   green-texture orange-texture yellow-texture red-texture transparent-texture]
  (fn [component]
    (let
      [$container (:$page params)
       $overlay (data $overlay)
       running (atom true)
       view (aget $overlay 0)
       width (-> $overlay .width)
       height (-> $overlay .height)
       pixi-renderer
       (or pixi-renderer
        (new js/PIXI.WebGLRenderer
             width
             height
             #js
             {
              :view view
              :antialias true
              :transparent true
              :autoResize true
              }))
       stage (new js/PIXI.Container)
       component
       (-> component
         (assoc :green-texture (atom nil))
         (assoc :orange-texture (atom nil))
         (assoc :yellow-texture (atom nil))
         (assoc :red-texture (atom nil))
         (assoc :transparent-texture (atom nil))
         (assoc :stage stage)
         (assoc :pixi-renderer pixi-renderer))]
      component))
  (fn [component] component))

; TODO: externalize
(def vertex-shader
"
attribute float boundingSphereRadius;
attribute float health;
attribute vec4 wmat1;
attribute vec4 wmat2;
attribute vec4 wmat3;
attribute vec4 wmat4;

uniform float screen_width;
uniform float screen_height;
uniform float fov;

varying vec2 vSize;
varying float vHealth;

const float PI = 3.141592653589793238462643383;

float getScreenRadius() {
  float fov2 = fov / 2.0 * PI / 180.0;
  float d = length(cameraPosition - position);
  float r3 = boundingSphereRadius;
  float r2 = 1.0 / tan(fov) * r3 / sqrt(d * d - r3 * r3);
  return r2;
}

void main() {

  mat4 matrixWorld = mat4(wmat1, wmat2, wmat3, wmat4);

  float radius = getScreenRadius();
  const float block_width = 14.0;
  const float max_blocks = 20.0;
  const float min_blocks = 4.0;
  float width = min(max(block_width * min_blocks, radius * screen_width), block_width * max_blocks);
  width = width - mod(width, block_width);
  float orig_height = width;

  vSize = vec2(width, orig_height);
  vHealth = health;

  gl_Position = projectionMatrix * viewMatrix * matrixWorld * vec4(vec3(0.0), 1.0);
  gl_Position.y += (radius - width / (2.0 * screen_width)) * gl_Position.w;
  gl_PointSize = width;
}
")

; TODO: externalize
(def fragment-shader
"

varying vec2 vSize;
varying float vHealth;

uniform sampler2D green_texture;
uniform sampler2D yellow_texture;
uniform sampler2D orange_texture;
uniform sampler2D red_texture;
uniform sampler2D transparent_texture;

void main() {
  vec4 color = vec4(0.0);
  float maxY = 10.0 / vSize.x;
  float step = 0.0 / vSize.x;
  if (gl_PointCoord.y <= maxY) {
    float modX = 14.0 / vSize.x;
    float newX = (mod(gl_PointCoord.x, modX) + step) / (modX + 2.0 * step);
    newX *= 0.85;
    float newY = 1.0 - gl_PointCoord.y / maxY;
    float block = floor(gl_PointCoord.x / modX);
    float last_block = floor(vHealth / modX);
    float remainder = mod(vHealth, modX);
    float last_block_opacity = remainder / modX;
    vec2 texCoord = vec2(newX, newY);
    if (gl_PointCoord.x < vHealth + modX - remainder) {
      if (vHealth < 0.25) {
        color = vec4(1.0, 0.0, 0.0, 1.0);
        color = texture2D(red_texture, texCoord);
      } else if (vHealth < 0.5) {
        color = vec4(1.0, 165.0 / 255.0, 0.0, 1.0);
        color = texture2D(orange_texture, texCoord);
      } else if (vHealth < 0.75) {
        color = vec4(1.0, 1.0, 0.0, 1.0);
        color = texture2D(yellow_texture, texCoord);
      } else {
        color = vec4(0.0, 1.0, 0.0, 1.0);
        color = texture2D(green_texture, texCoord);
      }
      if (block == last_block) {
        color = mix(texture2D(transparent_texture, texCoord), color, last_block_opacity);
      }
    } else {
      color = texture2D(transparent_texture, texCoord);
    }
  }
  gl_FragColor = color;
}
"
)

(defn
  on-xp-render
  [init-renderer component]
  (let
    [meshes (engine/get-unit-meshes (:units component))
     geo (new js/THREE.BufferGeometry)
     c (* (count meshes) xyz-size)
     d (* (count meshes) xyzw-size)
     positions (new js/Float32Array c)
     healths (new js/Float32Array (count meshes))
     bounding-sphere-radiuses (new js/Float32Array (count meshes))
     camera (data (:camera component))
     world-matrix-array
     [(new js/Float32Array d)
      (new js/Float32Array d)
      (new js/Float32Array d)
      (new js/Float32Array d)
      ]
     ]
    (doseq
      [[index mesh] (map-indexed vector meshes)]
      (let
        [unit (engine/get-unit-for-mesh (:units component) mesh)
         health (/ (:health unit) (:max-health (:model unit)))]
        (aset healths index health))

      (aset positions (+ (* index xyz-size) 0) (-> mesh .-position .-x))
      (aset positions (+ (* index xyz-size) 1) (-> mesh .-position .-y))
      (aset positions (+ (* index xyz-size) 2) (-> mesh .-position .-z))
      
      (aset bounding-sphere-radiuses index (-> mesh .-geometry .-boundingSphere .-radius))

      (aset (nth world-matrix-array 0) (+ (* index xyzw-size) 0) (aget (-> mesh .-matrixWorld .-elements) 0))
      (aset (nth world-matrix-array 0) (+ (* index xyzw-size) 1) (aget (-> mesh .-matrixWorld .-elements) 1))
      (aset (nth world-matrix-array 0) (+ (* index xyzw-size) 2) (aget (-> mesh .-matrixWorld .-elements) 2))
      (aset (nth world-matrix-array 0) (+ (* index xyzw-size) 3) (aget (-> mesh .-matrixWorld .-elements) 3))

      (aset (nth world-matrix-array 1) (+ (* index xyzw-size) 0) (aget (-> mesh .-matrixWorld .-elements) 4))
      (aset (nth world-matrix-array 1) (+ (* index xyzw-size) 1) (aget (-> mesh .-matrixWorld .-elements) 5))
      (aset (nth world-matrix-array 1) (+ (* index xyzw-size) 2) (aget (-> mesh .-matrixWorld .-elements) 6))
      (aset (nth world-matrix-array 1) (+ (* index xyzw-size) 3) (aget (-> mesh .-matrixWorld .-elements) 7))

      (aset (nth world-matrix-array 2) (+ (* index xyzw-size) 0) (aget (-> mesh .-matrixWorld .-elements) 8))
      (aset (nth world-matrix-array 2) (+ (* index xyzw-size) 1) (aget (-> mesh .-matrixWorld .-elements) 9))
      (aset (nth world-matrix-array 2) (+ (* index xyzw-size) 2) (aget (-> mesh .-matrixWorld .-elements) 10))
      (aset (nth world-matrix-array 2) (+ (* index xyzw-size) 3) (aget (-> mesh .-matrixWorld .-elements) 11))

      (aset (nth world-matrix-array 3) (+ (* index xyzw-size) 0) (aget (-> mesh .-matrixWorld .-elements) 12))
      (aset (nth world-matrix-array 3) (+ (* index xyzw-size) 1) (aget (-> mesh .-matrixWorld .-elements) 13))
      (aset (nth world-matrix-array 3) (+ (* index xyzw-size) 2) (aget (-> mesh .-matrixWorld .-elements) 14))
      (aset (nth world-matrix-array 3) (+ (* index xyzw-size) 3) (aget (-> mesh .-matrixWorld .-elements) 15)))

    ; TODO: unused attribute position, but can't remove because getting:
    ; "[.CommandBufferContext]RENDER WARNING: Render count or primcount is 0."
    (-> geo (.addAttribute "position" (new js/THREE.BufferAttribute positions xyz-size)))
    (-> geo (.addAttribute "health" (new js/THREE.BufferAttribute healths 1)))
    (-> geo (.addAttribute "boundingSphereRadius" (new js/THREE.BufferAttribute bounding-sphere-radiuses 1)))
    (-> geo (.addAttribute "wmat1" (new js/THREE.BufferAttribute (nth world-matrix-array 0) xyzw-size)))
    (-> geo (.addAttribute "wmat2" (new js/THREE.BufferAttribute (nth world-matrix-array 1) xyzw-size)))
    (-> geo (.addAttribute "wmat3" (new js/THREE.BufferAttribute (nth world-matrix-array 2) xyzw-size)))
    (-> geo (.addAttribute "wmat4" (new js/THREE.BufferAttribute (nth world-matrix-array 3) xyzw-size)))
    (let
      [scene (data (:overlay-scene component))
       material (:material component)
       mesh (new js/THREE.Points geo material)
       width @(get-in component [:scene-properties :width])
       height @(get-in component [:scene-properties :height])
       ]
      (-> mesh .-frustumCulled (set! false))
      (-> material .-uniforms .-screen_width .-value (set! width))
      (-> material .-uniforms .-screen_height .-value (set! height))
      (if
        (> (count (-> scene .-children)) 0)
        (let
          [oldmesh (-> scene .-children (aget 0))]
          (-> scene (.remove oldmesh))
          (-> oldmesh .-geometry .dispose)
          ))
      (if
        (> c 0)
        (-> scene (.add mesh))))))

(defcom
  new-xp-overlay
  [$overlay2 overlay-scene init-scene scene-properties units pixi-overlay camera]
  [overlay-renderer material]
  (fn [component]
    (let
      [canvas (aget (data $overlay2) 0)
       overlay-renderer
       (or overlay-renderer
           (new js/THREE.WebGLRenderer #js { :antialias true :canvas canvas :alpha true }))

       pixi-renderer (:pixi-renderer pixi-overlay)
       three-texture
       (fn [x]
         (let
           [i (-> pixi-renderer .-extract (.image x))
            t (new js/THREE.Texture i)]
           (-> t .-needsUpdate (set! true))
           (-> t .-minFilter (set! js/THREE.LinearFilter))
           t))
       get-texture
       (fn
         [color transparent?]
         (->
           (get-texture pixi-renderer bar-block-width bar-height color transparent?)
           three-texture))
       green-texture (get-texture 0x00FF00 false)
       yellow-texture (get-texture 0xFFFF00 false)
       orange-texture (get-texture 0xFFA500 false)
       red-texture (get-texture 0xFF0000 false)
       transparent-texture (get-texture 0x000000 true)
       camera (data camera)
       uniforms
       #js
       {
        :screen_width #js { :value @(:width scene-properties) }
        :screen_height #js { :value @(:height scene-properties) }
        :green_texture #js { :type "t" :value green-texture }
        :yellow_texture #js { :value yellow-texture }
        :orange_texture #js { :value orange-texture }
        :red_texture #js { :value red-texture }
        :transparent_texture #js { :value transparent-texture }
        :fov #js { :value (-> camera .-fov) }
        }
       material
       (new js/THREE.ShaderMaterial
            #js
            {
             :uniforms uniforms
             :vertexShader vertex-shader
             :fragmentShader fragment-shader
             :depthTest false
             :transparent true
             }
            )
       component
       (-> component
         (assoc :material material)
         (assoc :overlay-renderer overlay-renderer))
       overlay-scene (data overlay-scene)
       ]
      component))
  (fn [component]
    component))
