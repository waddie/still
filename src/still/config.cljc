;; Copyright (c) 2025 Tom Waddington. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; MIT License (https://opensource.org/licenses/MIT) which can be found
;; in the LICENSE file at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.

(ns still.config
  "Configuration management for Still snapshot testing.

  Supports configuration from:
  - deps.edn / bb.edn / project.clj (:still/config key)
  - deps.edn aliases (:still/config in :aliases map)
  - Runtime overrides via still.config/override!
  - Environment variables (for CI/CD)

  Alias-specific configuration in deps.edn is detected automatically from:
  - clojure.basis system property (when using tools.deps)
  - STILL_ALIASES environment variable (comma-separated)

  All configuration keys accept both British and American spellings
  (e.g., :colour? or :color?, :serialisers or :serializers)."
  (:require #?(:clj [clojure.edn :as edn]
               ;; cljs.reader aliased as edn for API consistency but only
               ;; used in JVM
               :cljs
                 #_{:clj-kondo/ignore [:unused-namespace]}
                 [cljs.reader as edn])
            #?(:clj [clojure.java.io :as io])
            #?(:clj [clojure.string])))

;; Default configuration
(def ^:private default-config
  {:snapshot-dir       "test/still"
   :auto-update?       false
   :metadata?          true
   :serializers        {}
   :diff-context-lines 3
   :color?             false})

;; Runtime configuration override (atom for thread-safe updates)
(defonce ^:private runtime-config (atom {}))

;; Configuration cache (atom for thread-safe updates)
(defonce ^:private config-cache (atom nil))

;; British to American spelling mappings
(def ^:private spelling-aliases
  "Map of British English config keys to their American English equivalents.
  Both spellings are accepted, but internally normalised to American spelling."
  {:colour? :color? :serialisers :serializers})

(defn- normalize-config-keys
  "Normalise config keys to accept both British and American spellings.
  British spellings are mapped to American equivalents for internal use."
  [config-map]
  (when config-map
    (reduce-kv (fn [acc k v]
                 (let [normalized-key (get spelling-aliases k k)]
                   (assoc acc normalized-key v)))
               {}
               config-map)))

#?(:clj
     (defn- read-edn-file
       "Safely read EDN from a file, returning nil if file doesn't exist or can't be read."
       [path]
       (try (when-let [file (io/file path)]
              (when (.exists file) (edn/read-string (slurp file))))
            (catch Exception _e nil))))

#?(:clj
     (defn- get-active-aliases
       "Detect which aliases are currently active.

       Tries multiple methods in order:
       1. STILL_ALIASES environment variable (comma-separated, e.g. 'repl,dev')
       2. clojure.basis system property (contains basis file path)
       3. Returns empty list if detection fails"
       []
       (or
        ;; Method 1: Check environment variable
        (when-let [env-aliases (System/getenv "STILL_ALIASES")]
          (map keyword (clojure.string/split env-aliases #",")))
        ;; Method 2: Try reading from basis file
        (try (when-let [basis-path (System/getProperty "clojure.basis")]
               (when-let [basis (read-edn-file basis-path)]
                 ;; The basis has :basis-config {:aliases [:test :dev ...]}
                 (get-in basis [:basis-config :aliases])))
             (catch Exception _e nil))
        ;; Fallback: empty list
        []))
   :cljs (defn- get-active-aliases "No-op in ClojureScript." [] []))

#?(:clj
     (defn- read-deps-config
       "Read configuration from deps.edn, including active aliases.

          Merges :still/config from:
          1. Top-level :still/config
          2. Each active alias's :still/config (in order)

          Later aliases override earlier ones and top-level config."
       []
       (when-let [deps (read-edn-file "deps.edn")]
         (let [top-level-config (:still/config deps)
               active-aliases   (get-active-aliases)
               alias-configs    (keep #(get-in deps [:aliases % :still/config])
                                      active-aliases)]
           (apply merge top-level-config alias-configs))))
   :cljs (defn- read-deps-config
           "No-op in ClojureScript (config must be provided at runtime)."
           []
           nil))

#?(:clj (defn- read-bb-config
          "Read configuration from bb.edn."
          []
          (when-let [bb (read-edn-file "bb.edn")]
            (:still/config bb)))
   :cljs (defn- read-bb-config "No-op in ClojureScript." [] nil))

#?(:clj (defn- read-project-config
          "Read configuration from project.clj (Leiningen)."
          []
          (try (when-let [file (io/file "project.clj")]
                 (when (.exists file)
                   (let [project-form (edn/read-string (slurp file))]
                     (when (and (list? project-form)
                                (= 'defproject (first project-form)))
                       ;; project.clj is (defproject name version &
                       ;; keyvals)
                       ;; Look for :still/config in the keyvals
                       (let [keyvals (drop 3 project-form)
                             kvmap   (apply hash-map keyvals)]
                         (:still/config kvmap))))))
               (catch Exception _e nil)))
   :cljs (defn- read-project-config "No-op in ClojureScript." [] nil))

(defn- read-env-config
  "Read configuration from environment variables.

  Environment variables:
  - STILL_SNAPSHOT_DIR: snapshot directory path
  - STILL_AUTO_UPDATE: 'true' or 'false'"
  []
  (let [env-snapshot-dir #?(:clj (System/getenv "STILL_SNAPSHOT_DIR")
                            :cljs (when (exists? js/process)
                                    (.-STILL_SNAPSHOT_DIR js/process.env)))
        env-auto-update  #?(:clj (System/getenv "STILL_AUTO_UPDATE")
                            :cljs (when (exists? js/process)
                                    (.-STILL_AUTO_UPDATE js/process.env)))]
    (cond-> {}
      env-snapshot-dir (assoc :snapshot-dir env-snapshot-dir)
      env-auto-update (assoc :auto-update? (= "true" env-auto-update)))))

(defn- load-config
  "Load configuration from all sources, with precedence:
  1. Runtime overrides (highest)
  2. Environment variables
  3. deps.edn
  4. bb.edn
  5. project.clj
  6. Default config (lowest)

  All config keys are normalised to accept both British and American spellings."
  []
  (merge default-config
         (normalize-config-keys (read-project-config))
         (normalize-config-keys (read-bb-config))
         (normalize-config-keys (read-deps-config))
         (normalize-config-keys (read-env-config))
         (normalize-config-keys @runtime-config)))

(defn invalidate-config-cache!
  "Invalidate the configuration cache.

  Forces the next call to get-config to reload configuration from all sources.
  Useful when configuration files change during development."
  []
  (reset! config-cache nil))

(defn get-config
  "Get the current configuration.

  Configuration is cached for performance. Use invalidate-config-cache! to force a reload."
  []
  (or @config-cache (reset! config-cache (load-config))))

(defn get-value
  "Get a specific configuration value by key.

  Example:
    (config/get-value :snapshot-dir) => \"test/still\"
    (config/get-value :enabled?) => true"
  [k]
  (clojure.core/get (get-config) k))

(defn override!
  "Override configuration at runtime.

  This has the highest precedence and will override all file-based config.
  Invalidates the config cache.

  Example:
    (config/override! {:snapshot-dir \"test/snapshots\"
                       :auto-update? true})

  To clear overrides:
    (config/override! {})"
  [config-map]
  (reset! runtime-config config-map)
  (invalidate-config-cache!))

(defn merge-override!
  "Merge additional configuration into runtime overrides.
  Invalidates the config cache.

  Example:
    (config/merge-override! {:auto-update? true})"
  [config-map]
  (swap! runtime-config merge config-map)
  (invalidate-config-cache!))

(defn snapshot-dir
  "Get the configured snapshot directory path."
  []
  (get-value :snapshot-dir))

(defn auto-update?
  "Check if automatic snapshot updates are enabled.

  When true, mismatched snapshots are automatically updated instead of failing."
  []
  (get-value :auto-update?))

(defn metadata?
  "Check if snapshot metadata tracking is enabled."
  []
  (get-value :metadata?))

(defn serializers
  "Get the map of custom serialisers."
  []
  (get-value :serializers))

(defn diff-context-lines
  "Get the number of context lines to show in diffs."
  []
  (get-value :diff-context-lines))

(defn color? "Check if coloured output is enabled." [] (get-value :color?))
