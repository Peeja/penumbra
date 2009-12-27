;   Copyright (c) Zachary Tellman. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns penumbra.opengl.core
  (:use [clojure.contrib.def :only (defn-memo defmacro- defvar defvar-)])
  (:import (org.lwjgl.opengl GL11 GL12 GL13 GL14 GL15 GL20 GL21 GL30 GL31 GL32
                             ARBDrawBuffers
                             EXTFramebufferObject
                             ARBHalfFloatPixel
                             ATITextureFloat
                             NVFloatBuffer))
  (:import (org.lwjgl.util.glu GLU))
  (:import (java.lang.reflect Field Method)))

;;;

(defvar *inside-begin-end* false
  "Are we within a glBegin/glEnd scope")

(defvar *intra-primitive-transform* (atom false)
  "Have we encountered an intra-primitive (i.e. *inside-begin-end* is true) transformation")

(defvar *transform-matrix* (atom nil)
  "The transform matrix for intra-primtive transforms")

(defvar *program* nil
  "The current program bound by with-program")

(defvar *uniforms* nil
  "Cached integer locations for uniforms (bound on a per-program basis)")

(defvar *texture-pool* nil
  "A list of all allocated textures.  Unused textures can be overwritten, thus avoiding allocation.")

(defvar *tex-mem-threshold* 100e6
  "The memory threshold, in bytes, which will trigger collection of unused textures")

(defvar *tex-count-threshold* 100
  "The threshold for number of allocated textures which will trigger collection of any which are unused")

(defvar *check-errors* true
  "Causes errors in glGetError to throw an exception.  This creates minimal CPU overhead (~3%), and is almost always worth having enabled.")

(defvar *view* (atom [0 0 0 0])
  "Pixel boundaries of render window.  Parameters represent [x y width height].")

;;;

(defvar- containers [EXTFramebufferObject
                     ARBDrawBuffers
                     ARBHalfFloatPixel
                     ATITextureFloat
                     NVFloatBuffer
                     GL11 GL12 GL13 GL14 GL15 GL20 GL21 GL30 GL31 GL32 GLU])

(defn- get-fields [#^Class static-class]
  (. static-class getFields))

(defn- get-methods [#^Class static-class]
  (. static-class getMethods))

(defn- contains-field? [#^Class static-class field]
  (first
   (filter
    #{ (name field) }
    (map #(.getName #^Field %) (get-fields static-class)))))

(defn- contains-method? [static-class method]
  (first
   (filter
    #{ (name method) }
    (map #(.getName #^Method %) (get-methods static-class)))))

(defn- field-container [field]
  (first (filter #(contains-field? % field) containers)))

(defn- method-container [method]
  (first (filter #(contains-method? % method) containers)))

(defn- get-gl-method [method]
  (let [method-name (name method)]
    (first (filter #(= method-name (.getName #^Method %)) (mapcat get-methods containers)))))

(defn-memo enum-name
  "Takes the numeric value of a gl constant (i.e. GL_LINEAR), and gives the name"
  [enum-value]
  (if (= 0 enum-value)
    "NONE"
    (.getName
     #^Field (some
              #(if (= enum-value (.get #^Field % nil)) % nil)
              (mapcat get-fields containers)))))     

(defn check-error []
  (let [error (GL11/glGetError)]
    (if (not (zero? error))
      (throw (Exception. (str "OpenGL error: " (enum-name error)))))))

(defn-memo enum [k]
  (when (keyword? k)
    (let [gl (str "GL_" (.. (name k) (replace \- \_) (toUpperCase)))
          sym (symbol gl)
          container (field-container sym)]
      (when (nil? container)
        (throw (Exception. (str "Cannot locate enumeration " k))))
      (eval `(. ~(field-container sym) ~sym)))))

(defn- get-doc-string [method]
  (str "Wrapper for " method ".  "
       "Type signature: ["
       (apply
        str
        (interpose
         " "
         (map
          #(.getCanonicalName #^Class %)
          (.getParameterTypes #^Method (get-gl-method method))))) "]."))

(defmacro gl-import
  [import-from import-as]
  (let [container (method-container import-from)]
    (when (nil? container)
      (throw (Exception. (str "Cannot locate method " import-from))))
    (let [doc-string (get-doc-string import-from)]
      `(defmacro ~import-as
         ~doc-string
         [& args#]
         `(do
            (let [~'value# (. ~'~container ~'~import-from ~@(map (fn [x#] (or (enum x#) x#)) args#))]
              (when (and *check-errors* (not *inside-begin-end*))
                (check-error))
              ~'value#))))))

(defmacro gl-import-
  "Private version of gl-import"
  [name & decls]
  (list* `gl-import (with-meta name (assoc (meta name) :private true)) decls))
