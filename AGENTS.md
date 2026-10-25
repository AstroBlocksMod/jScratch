# jScratch

## Overview

jScratch compiles `.sb3` files into Java bytecode and executable JAR files. Kotlin everywhere, ASM for bytecode, Gradle (Kotlin DSL) for the build, TeaVM for the browser playground. License: GPL-3.0.

Modules:

- `runtime/`: the Scratch runtime bundled into every compiled JAR (value semantics in `Cast` and `Ops`, `Target`/`Sprite`/`Stage`, the `Runtime` scheduler on virtual threads, Java2D rendering, Swing window, pen, sound). Depends on JSVG for vector costumes. MP3 and WAV decoding is C (`src/main/c/decoders.c` around dr_mp3 and dr_wav) compiled by Emscripten to WebAssembly and by Chicory to JVM classes at build time. No JSON library: large list contents use the tiny line format in `ListData`, and the monitor table the one in `MonitorData`.
- `sb3js/`: scratch-parser (the Scratch Foundation's JavaScript sb3 parser: `lib/parse` and `lib/validate`) wrapped by `src/index.js` and bundled with esbuild into `jscratch-sb3.js`. The Gradle node plugin downloads Node, so the build needs nothing installed. Target is ES2015 because Rhino has no classes and no spread in calls.
- `compiler/`: platform-neutral core: the `JsonValue` tree and the `ProjectJson` interface (`json/JsonTree.kt`), sb3 model and parser (model shape follows ScratchEverywhere's `sb3.cpp`), ASM code generation (`codegen/`), `JarWriter`, and the `JScratch` entry point. Depends only on ASM, so TeaVM can translate it. It never parses JSON itself.
- `compiler-jvm/`: `RhinoProjectJson` runs the scratch-parser bundle inside Rhino (interpreted mode, one shared scope) and parses JSON with kotlinx-serialization. Holds the end-to-end tests.
- `cli/`: the `jscratch` command. The runtime fat JAR is embedded as a resource.
- `web/`: TeaVM playground. `Playground.kt` exposes `window.jscratch.compile`; `BrowserProjectJson.kt` uses `JSON.parse` and the page-loaded `jscratch-sb3.js` through `@JSBody`. The page lives in `src/main/webapp/`.
- `samples/`: sample projects as directories under `src/`, zipped into `.sb3` files by the build and used by the end-to-end tests.
- `benchmarks/`: CPU-heavy projects under `projects/` (each with a `benchmark.properties` holding its description and expected `say` output, all sharing the costumes in `assets/`), a Kotlin runner that times them on jScratch, `js/run.js` that times them on scratch-vm and TurboWarp in Node, and a report that merges the results.
- `docs/`: Zensical documentation, configured by `zensical.toml` at the root.
- `mockups/`: block mockups for the docs as mockblocks text in `src/`. `npm run build` there renders them to SVG in `docs/assets/mockups/`, which git ignores, so build them before `zensical build` or `zensical serve`.

The compiler never depends on the runtime module. It only knows runtime class and method names, listed in `RT` in `compiler/.../codegen/Names.kt`. When you change a runtime method that generated code calls, update the descriptor in `TargetCompiler` too, and the other way around.

## Building and testing

- `./gradlew build` compiles, lints, tests and assembles everything, including the TeaVM site. Requires a JDK of version 21 or newer (JDK 25 is installed locally through mise) and Emscripten, which `mise install` sets up from `mise.toml`. CI installs it with `jdx/mise-action`.
- The sound decoders must keep every generated method under 8000 bytes of bytecode, or HotSpot never JIT-compiles it. `SoundDecodersTest` checks this. Keep `-fno-inline-functions` and the `-O1` link in `runtime/build.gradle.kts` unless you have measured the alternative.
- `./gradlew :compiler-jvm:test` runs the Rhino bridge tests and the end-to-end test: it compiles the samples with the real runtime and runs the JAR headless in a child JVM, checking the `say` output.
- After editing `sb3js/src/index.js` or `sb3js/package.json`, run `npm install` in `sb3js/` to refresh `package-lock.json` (the Gradle task uses `npm ci`) and commit both.
- Compiled JARs accept `-Djscratch.headless=<frames>` (virtual clock, prints `say` to stdout, stops early once no script is running), `-Djscratch.screenshot=<png>` and `-Djscratch.timing=true` (frames and wall time on stderr). Use these to verify behavior and rendering without a window.
- `./gradlew :benchmarks:benchmark` times the benchmarks on all three engines and writes `benchmarks/build/reports/benchmarks/report.md`. `-Pbenchmark.runs=<n>` and `-Pbenchmark.only=<a,b>` narrow it down; `:benchmarks:benchmarkJScratch` runs only jScratch. It is not part of `build`. After a change that could affect speed, run at least `benchmarkJScratch` and compare. After editing `benchmarks/js/package.json`, run `npm install` in `benchmarks/js/` and commit the lock file.
- Add a block by adding a runtime method with a plain JVM signature and a case in `TargetCompiler`, then cover it with a sample and list it in `docs/blocks.md`.

## Verifying in a Browser (web playground)

If you have it installed, use the Playwright MCP for this. If not, use the Playwright CLI instead. You can use the CLI over the MCP if you need to use a browser other than the one that is configured.

Build the site with `./gradlew :web:assembleSite`, serve `web/build/site` with any static server, open it, upload an `.sb3` from `samples/build/sb3/`, and click Compile. The status line shows the compile time and the page lists the generated classes.

## Searching the Codebase

Use fast, modern tools instead of the slow defaults:

- [`rg`](https://github.com/BurntSushi/ripgrep) (ripgrep) for text search, not `grep`. It respects `.gitignore` for you. **NOTE**: Prefer to use [`ast-grep`](https://ast-grep.github.io) when applicable as it uses the structure of the code instead.
- [`fd`](https://github.com/sharkdp/fd) for finding files by name, not `find`.
- [`ast-grep`](https://ast-grep.github.io) for structural searches and rewrites, like finding every call to a function or every component that uses a certain prop. Call it as `ast-grep`. The `sg` alias is deprecated, so don't use it.
- `jq` for reading and querying JSON.

If your harness has dedicated search or file tools that already wrap these, those are fine too.

## Code Quality

Always lint and format:

- ktlint runs as part of `./gradlew build` through the `org.jlleitschuh.gradle.ktlint` plugin. `./gradlew ktlintFormat` fixes what it can; `./gradlew ktlintCheck` only reports.
- Style is configured in `.editorconfig`: `intellij_idea` code style, 120 columns. A few wrapping rules are disabled on purpose because they turn the block dispatch tables in `TargetCompiler` into something hard to scan. Do not switch to `ktlint_official` without a reason.

Use modern code practices:

- Kotlin 2.4 with JVM target 21. Use virtual threads, `java.util.zip`, and Java2D from the JDK before adding a dependency.
- Gradle 9 Kotlin DSL: `tasks.register<T>("name")`, `configurations.consumable`/`resolvable`/`dependencyScope`, the version catalog in `gradle/libs.versions.toml`. The `by registering` and `by creating` delegates are deprecated.
- Runtime methods called from generated code must have plain JVM signatures (`Double`, `String`, `Boolean`, `Any?`), no default parameters, no inline classes, and `@JvmStatic` on `object` members.
- Keep the `compiler` module free of reflection, JSON libraries, JavaScript engines and JDK APIs TeaVM lacks (no `String.format`, no class loading). Platform-specific code goes into `compiler-jvm` or `web` behind the `ProjectJson` interface. The TeaVM build is part of `./gradlew build` and will tell you.

## Docs (IMPORTANT!!!)

Always update the docs if necessary when changing or adding code. If the docs already contain outdated information, expand your scope to cover that info too.

The docs are a Zensical site: `zensical.toml` at the root, pages in `docs/`. `zensical build` validates the config and writes `site/` (ignored by git). Pages: `index.md`, `getting-started.md`, `blocks.md` (supported blocks, keep in step with `TargetCompiler`), `web-playground.md`, `architecture.md`, `development.md`, `benchmarks.md` (method and latest results), `roadmap.md`, `language-features.md` (mockups of the planned language features).

When a roadmap item is finished, check it off (`- [x]`) where it is. DO NOT move it into an earlier section like "Done: the MVP". The roadmap shows how each goal is progressing, so finished items stay with the section they belong to.

## Diagrams as Mermaid, not ASCII art

Never draw a flow, pipeline or architecture picture out of characters in a code block. Make a Mermaid diagram instead, which Zensical integrates with.

## Outdated packages

PLEASE, PLEASE do not use a package that is old or deprecated. EVEN IF IT IS "INDUSTRY STANDARD" THAT DOES NOT MEAN IT IS GOOD!!! USE THE BEST PACKAGE AND LATEST VERSION LIKE A NORMAL PERSON WOULD!

Check Maven Central metadata (`https://repo1.maven.org/maven2/<group path>/<artifact>/maven-metadata.xml`) for the current release before bumping `gradle/libs.versions.toml`.

## Keep things human

Do no use em dashes or other special symbols not normally found in writing. Do not word things in a weird way. Keep it looking human. If you need to use, for example, an arrow icon, use an SVG instead.

Replacing an em dash with a hyphen is NOT a fix. A spaced hyphen doing an em dash's job ("the limit - 4 bytes", "fast - but wrong") is the same tic with a different character. Reword the sentence instead: use a comma, a colon, parentheses, or the actual word ("minus", "to", "through"). Plain hyphens in compound words and numeric ranges like 0-1 are fine.

## Use American spelling

It's fine to be British in the chat if the person is actually in a region where its spelled like that. But if not just use American spelling. The thing itself MUST use American spelling.
