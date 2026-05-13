(ns build
  (:require [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd]))

(def lib 'dev.tomwaddington/still)
(def version (or (System/getenv "VERSION") "0.0.0-SNAPSHOT"))
(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn"}))

(def jar-file (str "target/still-" version ".jar"))
(def uber-file (str "target/still-" version "-uber.jar"))

(defn clean [_] (b/delete {:path "target"}))

(defn prep
  [_]
  (b/write-pom
   {:basis     basis
    :class-dir class-dir
    :lib       lib
    :pom-data  [[:description "Self-modifying snapshot testing for Clojure"]
                [:url "https://github.com/waddie/still"]
                [:licenses
                 [:license [:name "MIT License"]
                  [:url "https://opensource.org/licenses/MIT"]]]]
    :scm       {:connection "scm:git:git://github.com/waddie/still.git"
                :developerConnection
                "scm:git:ssh://git@github.com/waddie/still.git"
                :tag        (str "v" version)
                :url        "https://github.com/waddie/still"}
    :src-dirs  ["src"]
    :version   version})
  (b/copy-dir {:src-dirs   ["src" "resources"]
               :target-dir class-dir}))

(def opts
  {:basis     basis
   :class-dir class-dir})

(defn jar
  [_]
  (b/compile-clj {:basis     basis
                  :class-dir class-dir
                  :src-dirs  ["src"]})
  (b/jar (merge opts {:jar-file jar-file})))

(defn uber
  [_]
  (b/compile-clj {:basis     basis
                  :class-dir class-dir
                  :src-dirs  ["src"]})
  (b/uber (merge opts {:uber-file uber-file})))

(defn tag-release
  [{:keys [version]}]
  (let [tag (str "v" version)]
    (b/git-process {:git-args ["tag" "-a" tag "-m" (str "Release " version)]})
    (b/git-process {:git-args ["push" "origin" tag]})))

(defn jar-all [_] (clean nil) (prep nil) (jar nil))

(defn uber-all [_] (clean nil) (prep nil) (uber nil))

(defn jar-install
  [_]
  (b/install {:basis     basis
              :class-dir class-dir
              :jar-file  jar-file
              :lib       lib
              :version   version}))

(defn deploy
  [_]
  (dd/deploy {:artifact  jar-file
              :installer :remote
              :pom-file  (b/pom-path {:class-dir class-dir
                                      :lib       lib})}))
