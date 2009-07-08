(ns penumbra.opengl)

(import '(javax.media.opengl GLCanvas GL)
        '(javax.media.opengl.glu GLU)
        '(java.awt Font)
        '(com.sun.opengl.util.j2d TextRenderer))

(set! *warn-on-reflection* true)

;;;;;;;;;;;;;;;;;;;;;;
;Basic matrix math

(defn identity-matrix []
  [1 0 0 0
   0 1 0 0
   0 0 1 0
   0 0 0 1])

(defn translation-matrix [x y z]
  [1 0 0 x
   0 1 0 y
   0 0 1 z
   0 0 0 1])

(defn scaling-matrix [x y z]
  [x 0 0 0
   0 y 0 0
   0 0 z 0
   0 0 0 1])

(defn rotation-matrix [theta x y z]
  (let [s (Math/sin (* Math/PI (/ theta 180)))
        c (Math/cos (* Math/PI (/ theta 180)))
        t (- 1 c)]
  [(+ c (* t x x))        (- (* t x y) (* s z))   (+ (* t x z) (* s y))   0
   (+ (* t x y) (* s z))  (+ (* t y y) c)         (- (* t y z) (* s x))   0
   (- (* t x z) (* s y))  (+ (* t y z) (* s x))   (+ (* t z z) c)         0
   0                      0                       0                       1]))

(defn index [m i j] (m (+ i (* j 4))))

(defn mult-matrix [a b]
  (let [indices (for [i (range 4) j (range 4)] [i j])
        traverse (fn [[i j]] (apply + (map #(* (index a % i) (index b j %)) (range 4))))]
    (vec (map traverse indices))))

(defn apply-matrix [m v]
  (let [traverse-fn (fn [i] #(* (v %) (index m % i)))]
    (map #(apply + (map (traverse-fn %) (range 4))) (range 4))))

;;;;;;;;;;;;;;;;;;;;;;

(def #^GL *gl* nil)
(def #^GLU *glu* (new GLU))
(def transform-matrix (atom (identity-matrix)))
(def inside-begin-end false)

(defmacro bind-gl [#^javax.media.opengl.GLAutoDrawable drawable & body]
  `(binding [*gl* (.getGL ~drawable)]
    ~@body))

;;;;;;;;;;;;;;;;;;;;;;
;Import macros

(defn prepend [text sym] (symbol (format "%s-%s" text (name sym))))

(defmacro gl-facade-import
  "Takes an OpenGL function and turns it into two macros:
    - a macro which behaves differently if we're inside a glBegin/End clause (glVertex3d -> vertex)
    - a macro which directly calls the OpenGL function (glVertex3d -> gl-vertex)"
  [import-from import-as]
  (let [facade-fn (prepend "facade" import-as)
        direct-fn (prepend "gl" import-as)]
    `(do
      (defmacro ~import-as [& a#]
        `(if inside-begin-end
          (~'~facade-fn ~@a#)
          (. *gl* ~'~import-from ~@a#)))
      (defmacro ~direct-fn [& b#]
        `(. *gl* ~'~import-from ~@b#)))))

(defmacro glu-import [import-from import-as]
  `(defmacro ~import-as [& args#]
      `(. *glu* ~'~import-from ~@args#)))

(defn translate-keyword [k]
 (if (keyword? k)
   (let [gl (str "GL_" (.. (name k) (replace \- \_) (toUpperCase)))]
   `(. GL ~(symbol gl)))
   k))

(defmacro gl-import
  "Imports an OpenGL function, transforming all :keywords into GL_KEYWORDS"
  [import-from import-as]
  `(defmacro ~import-as [& args#]
    `(. *gl* ~'~import-from ~@(map translate-keyword args#))))

;;;;;;;;;;;;;;;;;;;;;;
;Import declarations

(gl-facade-import glVertex3d vertex)
(gl-facade-import glNormal3d normal)
(gl-facade-import glRotated rotate)
(gl-facade-import glTranslated translate)
(gl-facade-import glScaled scale)
(gl-facade-import glLoadIdentity load-identity)

(gl-import glColor3d color)

(gl-import glBegin gl-begin)
(gl-import glEnd gl-end)

(gl-import glPushMatrix gl-push-matrix)
(gl-import glPopMatrix gl-pop-matrix)
(gl-import glMatrixMode gl-matrix-mode)

(gl-import glOrtho gl-ortho)
(glu-import gluPerspective glu-perspective)

(gl-import glEnable enable)
(gl-import glDisable disable)
(gl-import glCullFace gl-cull-face)
(gl-import glPolygonMode gl-polygon-mode)

(gl-import glClear gl-clear)
(gl-import glClearColor clear-color)

(gl-import glCallList gl-call-list)
(gl-import glGenLists gl-gen-lists)
(gl-import glNewList gl-new-list)
(gl-import glEndList gl-end-list)
(gl-import glDeleteLists gl-delete-lists)
(gl-import glIsList gl-is-list)

(gl-import glLightfv set-light)
(gl-import glMaterialfv set-material)
(gl-import glFogfv gl-fog)
(gl-import glShadeModel shade-model)

;;;;;;;;;;;;;;;;;;;;;;;;;
;Render functions

(defmacro facade-transform
  "Forwards the transformed vector from fn to the OpenGL function fn represents."
  [fn transform-fn]
  (let [facade-fn (prepend "facade" fn)
        direct-fn (prepend "gl" fn)]
    `(defn ~facade-fn [x# y# z#]
      (let [[xp# yp# zp# wp#] (apply-matrix (~transform-fn @transform-matrix) [x# y# z# 1])]
        (~direct-fn xp# yp# zp#)))))

(defn undo-translation [matrix] (vec (concat (subvec matrix 0 12) [0 0 0 0]))) ;we don't want to translate normals

(facade-transform vertex identity)
(facade-transform normal undo-translation)

(defn apply-transform
  "Pops off the head of the transform stack, multiplies it by the matrix, and pushes it back on"
  [matrix transform-fn]
  (swap! transform-matrix transform-fn matrix))

(defmacro facade-multiply
  "Applies a transform to transform-stack rather than the OpenGL modelview matrix."
  ([fn matrix-fn] `(facade-multiply ~fn ~matrix-fn mult-matrix))
  ([fn matrix-fn transform-fn]
  (let [facade-fn (prepend "facade" fn)]
    `(defmacro ~facade-fn [& args#]
      `(apply-transform (~'~matrix-fn ~@args#) ~'~transform-fn)))))

(facade-multiply rotate rotation-matrix)
(facade-multiply scale scaling-matrix)
(facade-multiply translate translation-matrix)
(facade-multiply load-identity identity-matrix #(%2)) ;Note: this only resets transformations local to the begin/end clause

(defmacro defn-draw
  "Creates a macro called draw-'type' which redirects vertex and transform calls through appropriate facades."
  [primitive-type]
  `(defmacro ~(symbol (str "draw-" (name primitive-type))) [& body#]
    `(binding [inside-begin-end true
               transform-matrix (atom (identity-matrix))]
      (gl-begin ~'~(translate-keyword primitive-type))
      ~@body#
      (gl-end))))

(defn-draw :quads)
(defn-draw :line-strip)
(defn-draw :lines)
(defn-draw :triangle-strip)
(defn-draw :triangle-fan)
(defn-draw :quad-strip)
(defn-draw :triangles)
(defn-draw :polygon)
(defn-draw :line-loop)
(defn-draw :points)

(defn clear []
  (gl-clear :depth-buffer-bit)
  (gl-clear :color-buffer-bit))

;;;;;;;;;;;;;;;;;;;;;;;;;;
;Various utility functions

(defmacro push-matrix [& body]
  `(binding [transform-matrix (if inside-begin-end (atom @transform-matrix) transform-matrix)]
    (if (not inside-begin-end) (gl-push-matrix))
    ~@body
    (if (not inside-begin-end) (gl-pop-matrix))))

(defmacro get-display-list [& body]
  `(let [list# (gl-gen-lists 1)]
    (gl-new-list list# :compile)
    ~@body
    (gl-end-list)
    list#))

(defmacro set-display-list
  "Points list-atom to a new list, and deletes the list it was previous pointing to."
  [list-atom & body]
  `(let [list# (get-display-list ~@body)]
    (if (is-display-list (deref ~list-atom)) (delete-display-list (deref ~list-atom)))
    (reset! ~list-atom list#)))

(defn is-display-list [display-list]
  (and
    (not (nil? display-list))
    (gl-is-list display-list)))

(defn delete-display-list [display-list]
  (gl-delete-lists display-list 1))

(defn call-display-list [display-list]
  (gl-call-list display-list))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;Render state

(defmacro set-light-position [num [x y z w]]
  (let [light# (keyword (str "light" num))]
    `(do
      (enable :lighting)
      (enable ~light#)
      (set-light ~light# :position (float-array 4 [~x ~y ~z ~w]) 0))))

(defmacro setup-fog [mode density near far [r g b a]]
  `(do
    (enable :fog)
    (gl-fog :fog-mode (float-array 1 [~(translate-keyword mode)]) 0)
    (gl-fog :fog-density (float-array 1 [~density]) 0)
    (gl-fog :fog-start (float-array 1 [~near]) 0)
    (gl-fog :fog-end (float-array 1 [~far]) 0)
    (gl-fog :fog-color (float-array 4 [~r ~g ~b ~a]) 0)))

(defn material [r g b a]
  (set-material :front-and-back :ambient-and-diffuse (float-array 4 [r g b a]) 0))

(defn draw-solid [] (gl-polygon-mode :front-and-back :fill))
(defn draw-wireframe [] (gl-polygon-mode :front-and-back :line))
(defn draw-point-cloud [] (gl-polygon-mode :front-and-back :point))

;;;;;;;;;;;;;;;;;;;;;;;;;;;
;View initialization

(defn ortho-view
  "Create orthographic view, where distant objects don't get smaller."
  [left top right bottom near far]
  (gl-matrix-mode :projection)
  (load-identity)
  (gl-ortho left right bottom top near far)
  (gl-matrix-mode :modelview))

(defn frustum-view [fovx aspect near far]
  "Create a standard perspective view."
  (gl-matrix-mode :projection)
  (load-identity)
  (glu-perspective fovx aspect near far)
  (gl-matrix-mode :modelview))

;;;;;;;;;;;;;;;;;;;;;;;;;;
;Text rendering

(def view-dimensions (ref [0 0]))
(def #^TextRenderer *text* (TextRenderer. (Font. "Tahoma" java.awt.Font/PLAIN 20) true true))

(defn set-dimensions [w h]
  (dosync (ref-set view-dimensions [w h])))

(defn write
  "writes string at normalized coordinates (x,y)"
  [string x y]
  (let [[w h] @view-dimensions
        text-height (.. *text* (getBounds string) getHeight)]
    (.beginRendering *text* w h)
    (.draw *text* string (int (* x w)) (int (* y (- h text-height))))
    (.endRendering *text*)))



