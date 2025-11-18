# still

Snapshot testing for Clojure/ClojureScript/Babashka inspired by [juxt/snap](https://github.com/juxt/snap) and [Ian Henry’s “My Kind of REPL”](https://ianthehenry.com/posts/my-kind-of-repl/).

- Configure via deps.edn, bb.edn, project.clj, or runtime
- Smart behaviour inside `deftest` vs REPL
- Inline snapshots with automatic source editing
- Full colour diffing
- Auto-update mode to snapshots automatically (like Jest’s `-u` flag)
- Custom serialisers to handle timestamps, UUIDs, and custom types
- Snapshot metadata to track creation date, platform, etc.

## Demo

![An asciinema recording of using still in Helix via nrepl.hx](https://github.com/waddie/still/blob/main/images/still.gif?raw=true)

## Installation

### Clojure (deps.edn)

```clojure
{:deps {io.github.waddie/still {:git/sha "…"}}
 :aliases {:repl {:extra-deps {nrepl/nrepl {:mvn/version "1.5.1"}}}}}
```

**Note:** For best REPL experience with `snap!`, use nREPL 1.5.0 or later with a client supporting filenames in regular eval. This enables automatic file detection during REPL eval operations.

### Babashka (bb.edn)

```clojure
{:deps {io.github.waddie/still {:git/sha "…"}
        rewrite-clj/rewrite-clj {:mvn/version "1.2.50"}
        lambdaisland/deep-diff2 {:mvn/version "2.12.219"}}}
```

## Quick start

```clojure
(ns my-app.test
  (:require [clojure.test :refer [deftest testing is]]
            [still.core :refer [snap snap!]]))

(deftest user-creation-test
  (testing "creates user with correct shape"
    (let [user (create-user {:name "Alice" :email "alice@example.com"})]
      ;; First run: creates snapshot in test/still/user_creation.edn
      ;; Subsequent runs: compares against stored snapshot
      (snap :user-creation user))))

(deftest inline-snapshot-test
  (testing "inline snapshots"
    ;; First run: edits this file to add expected value
    ;; Becomes: (snap! (compute-result) {:result 42})
    (snap! (compute-result))))
```

## API

### `snap` - Filesystem-based snapshots

Compares a value against a stored snapshot file. Contextual behaviour:

**Inside deftest:**

- Uses `clojure.test/is` for assertions
- Failures appear in test runner output
- Integrates with CI/CD pipelines

**Outside deftest (REPL):**

- Returns boolean (true if match, false if mismatch)
- Prints friendly messages to `stdout`
- No test framework overhead

```clojure
;; In a test
(deftest api-test
  (snap :api-response (fetch-data)))

;; In the REPL
(snap :api-response (fetch-data))
;; => ✓ Snapshot matches: :api-response
;; => true
```

### `snap!` - inline snapshots (JVM/Babashka only)

Like `snap`, but stores expected values directly in source code. When called without an expected value, automatically edits the source file.

```clojure
;; First run - edits source file
(snap! (+ 1 2))

;; After first run, the line becomes:
(snap! (+ 1 2) 3)

;; Subsequent runs compare against inline value
```

**REPL Usage:** For `snap!` to work when evaluating forms in the REPL (not loading files):

- Use nREPL 1.5.0+ with a supporting client
- OR load the file instead of evaluating individual forms
- OR use `snap` instead for REPL-based testing
- OR provide the expected value manually: `(snap! expr expected)`

See the error message for detailed troubleshooting if `snap!` can’t detect your file location.

## Configuration

Configure `still` via multiple sources (later sources override earlier):

1. Default configuration
2. `deps.edn`/`bb.edn`/`project.clj` (`:still/config` key)
3. Environment variables
4. Runtime overrides (highest priority)

### Configuration Options

```clojure
{:snapshot-dir "test/still"        ; Where snapshots are stored
 :enabled? true                    ; Enable/disable snapshot tests
 :auto-update? false               ; Auto-update mismatched snapshots
 :metadata? true                   ; Track snapshot metadata
 :serializers {}                   ; Custom type serialisers
 :diff-context-lines 3             ; Context lines in diffs
 :color? true}                     ; ANSI colours in output
 ; Note: :colour? and :serialisers are also accepted
```

### In deps.edn

```clojure
{:still/config {:snapshot-dir "test/snapshots"
                :auto-update? false}}
```

### Runtime Override

```clojure
(require '[still.config :as config])

;; Replace entire config
(config/override! {:snapshot-dir "test/custom"})

;; Merge into config
(config/merge-override! {:auto-update? true})
```

### Environment variables

```sh
export STILL_SNAPSHOT_DIR="test/snapshots"
export STILL_ENABLED="true"
export STILL_AUTO_UPDATE="false"
```

## Auto-update mode

Update all mismatched snapshots automatically (like Jest’s `-u`):

```sh
# Via environment variable
STILL_AUTO_UPDATE=true clj -M:test
```

```clojure
;; Or programmatically
(require '[still.update :as update])
(update/enable-auto-update!)
```

## Custom serialisers

Handle unstable values like timestamps and UUIDs:

```clojure
(require '[still.serialize :as serialize])

;; Timestamps are automatically serialised as ISO-8601
(snap :with-timestamp {:id 123 :created-at (java.util.Date.)})
;; => {:id 123 :created-at {:type :still.serialize/date :iso8601 "2025-..."}}

;; Register custom serialiser for your types
(defrecord Person [name age])

(serialize/register-serializer! Person
  (fn [p] {:type ::person :name (:name p) :age (:age p)}))

(snap :person (->Person "Alice" 30))
```

### Diff visualisation

Full-colour diffs powered by `deep-diff2`:

```clojure
(require '[still.diff :as diff])

;; Generate and print a diff
(diff/print-diff {:a 1 :b 2} {:a 1 :b 3})

;; Get diff as string
(diff/diff-str expected actual)

;; Side-by-side comparison
(println (diff/side-by-side expected actual))
```

### Snapshot management

```clojure
(require '[still.update :as update])

;; List all snapshots
(update/print-summary)

;; Enable auto-update for session
(update/enable-auto-update!)

;; Delete all snapshots (careful!)
(update/delete-all-snapshots!)
```

## REPL workflow

`still` is designed for interactive development:

```clojure
;; Load your namespace
(require '[my-app.core :as core])
(require '[still.core :refer [snap snap!]])

;; Test a function interactively
(snap :user-response (core/create-user {:name "Alice"}))
;; => ✓ Snapshot created: :user-response
;; => true

;; Modify the function, run again
(snap :user-response (core/create-user {:name "Alice"}))
;; => ✗ Snapshot mismatch: :user-response
;; => (shows colourful diff)
;; => false

;; Looks good? Update the snapshot
(config/merge-override! {:auto-update? true})
(snap :user-response (core/create-user {:name "Alice"}))
;; => ✓ Snapshot updated: :user-response
;; => true
```

## Running tests

### Clojure

```sh
# Run tests
clj -M:test -m cognitect.test-runner

# Update all snapshots
STILL_AUTO_UPDATE=true clj -M:test -m cognitect.test-runner

# Disable snapshots (e.g., in production)
STILL_ENABLED=false clj -M:test -m cognitect.test-runner
```

### Babashka

```sh
# Run tests
bb test

# Verify namespaces load
bb verify

# Start REPL with Still loaded
bb repl

# Update snapshots
STILL_AUTO_UPDATE=true bb test
```

## License

Copyright © 2025 Tom Waddington

Distributed under the MIT License. See LICENSE file for details.
