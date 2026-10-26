# Development

## Layout

| Path | Contents |
| --- | --- |
| `runtime/` | The Scratch runtime bundled into compiled JARs: `Cast`, `Ops`, `ScratchList`, `Target`/`Sprite`/`Stage`, `Runtime` (scheduler), `StageRenderer`, `StageWindow`, pen and sound support. Depends on JSVG and the Chicory runtime. The sound decoders are C in `src/main/c/`, see [Sound decoders](#sound-decoders). |
| `sb3js/` | Two bundles, built by `build.mjs`: `src/index.js`, a thin wrapper over scratch-parser's `lib/parse` and `lib/validate`, becomes `build/dist/jscratch-sb3.js`, and `src/convert.js`, the Scratch 1.4 and 2 converter built from scratch-vm and scratch-sb1-converter, becomes `build/dist/jscratch-convert.js`. See [Converter bundle](#converter-bundle). Node is downloaded by the Gradle node plugin, so nothing needs to be installed. |
| `compiler/` | Platform-neutral core: `json/JsonTree.kt` (tree and the `ProjectJson` interface), `sb3/` (model and parser), `convert/` (recognizing Scratch 1.4 and 2 projects and the `ProjectConverter` interface), `codegen/` (ASM emitters), `Archives`, `JarWriter`, `JScratch` (entry point). Depends on ASM only. |
| `compiler-jvm/` | `RhinoProjectJson` (scratch-parser in Rhino, JSON through kotlinx-serialization), `RhinoProjectConverter` (the converter bundle in Rhino), `Rhino` (how both set up Rhino) and the end-to-end tests. |
| `cli/` | `jscratch` command, plus `ScratchDownloader`, which fetches shared projects from scratch.mit.edu with the JDK's `HttpClient`. Embeds the runtime fat JAR as a resource. |
| `web/` | TeaVM playground: `Playground.kt`, `BrowserProjectJson.kt` plus the static page in `src/main/webapp/`. |
| `samples/` | Sample projects as directories under `src/`, zipped into `.sb3` files by the build, and Scratch 2 projects under `sb2/`, zipped into `.sb2` files. A Scratch 1.4 project cannot be written by hand, so the build fetches one from scratch-sb1-converter's test fixtures, pinned to a commit in `gradle/libs.versions.toml`. |
| `benchmarks/` | CPU-heavy projects under `projects/` sharing the costumes in `assets/`, the jScratch runner and report in Kotlin, and `js/run.js`, which runs the same projects on scratch-vm and TurboWarp in Node. See [Benchmarks](benchmarks.md). |
| `docs/` | This documentation, built with Zensical. |
| `mockups/` | Block mockups for the docs, written as [mockblocks](https://github.com/AstroBlocksMod/mockblocks) text in `src/` and rendered to SVG when the docs are built. See [Docs](#docs). |

## Everyday commands

```sh
./gradlew build                 # compile, lint, test, assemble everything including the playground
./gradlew :compiler-jvm:test    # Rhino tests and end-to-end: compile the samples and run them headless
./gradlew :runtime:test         # unit tests for value semantics
./gradlew :cli:test             # project download tests (offline)
./gradlew ktlintFormat          # format Kotlin sources
./gradlew :cli:fatJar           # single-file CLI
./gradlew :web:assembleSite     # playground in web/build/site
./gradlew :sb3js:bundle         # rebuild the parser and converter bundles after touching sb3js/
./gradlew :benchmarks:benchmark # time the benchmarks on jScratch, TurboWarp and scratch-vm (a few minutes)
```

The build uses the Gradle wrapper (Gradle 9), Kotlin 2.4, a JDK of version 21 or newer on the path, and Emscripten from `mise.toml` (run `mise install` once). Generated classes and the runtime target Java 21 because the scheduler uses virtual threads. To update JavaScript dependencies, edit `sb3js/package.json` or `benchmarks/js/package.json`, run `npm install` in the same directory to refresh `package-lock.json`, and commit both.

## Continuous integration

Each workflow runs only when a file it depends on changes, so a docs-only change skips the Gradle build and a code change skips the docs check. Every workflow can also be started by hand from the Actions tab. When you add a module or a top-level file the build reads, add it to the `paths` list of the workflows that need it.

The `CI` workflow in `.github/workflows/ci.yml` runs on pull requests and pushes to `main` that touch a module, the Gradle setup, `.editorconfig` or `mise.toml`. It installs the tools in `mise.toml` with `jdx/mise-action`, then runs `./gradlew build` on JDK 21 and JDK 25, so both the oldest supported JDK and the current one are covered. When a test fails, the HTML test reports are attached to the run as an artifact. CI never deploys anything; publishing the site is the `Pages` workflow's job (see [Deployment](web-playground.md#deployment)).

The `Docs` workflow in `.github/workflows/docs.yml` runs on pull requests and pushes to `main` that touch `docs/`, `mockups/` or `zensical.toml`. It renders the block mockups and builds the docs with `zensical build --strict`. It runs on macOS, see [Block mockups](#block-mockups) for why.

The `Benchmarks` workflow in `.github/workflows/benchmarks.yml` runs `./gradlew :benchmarks:benchmark` on pushes to `main` that touch the benchmarks, a module they compile or run with (`runtime`, `sb3js`, `compiler`, `compiler-jvm`) or the Gradle setup, and on demand from the Actions tab, where the number of runs and the projects can be chosen. The report shows up on the run's summary page, and the raw results are attached as an artifact. Shared runners are noisy, so the job only fails when jScratch gives a wrong result, not when it gets slower.

## Tests

- `runtime/src/test`: unit tests for `Cast` and `Ops`, the parts most likely to drift from Scratch, `MonitorsTest` for how monitors format values, decode the compiler's table, place themselves and snap sliders, `SoundDecodersTest`, which decodes the MP3 and WAV files in `src/test/resources/assets` and checks that no generated decoder method is too big for the JIT, `VoiceTest` for the samples the sound mixer produces with volume, pitch, pan and fades, `SoundsTest` for which sounds stop when a sprite stops its other scripts or a clone is deleted, and for volumes and effects that are not numbers, `EdgeHatsTest` for when an edge-activated hat starts its script, `MicrophoneTest` for the loudness math, and `DragTest` for dragging sprites with the mouse and when a press counts as a click.
- `cli/src/test`: `ScratchDownloaderTest` checks project link parsing and how a download is packed into an `.sb3` (or an `.sb2`, for a project last saved by Scratch 2), with a fake server in place of the network so the build never goes online.
- `compiler-jvm/src/test`: `RhinoProjectJsonTest` checks the scratch-parser bridge (valid project, schema rejection, broken JSON), `MonitorTableTest` checks the monitor table and the warnings for monitors the compiler drops, and `CompileSamplesTest` compiles `samples/src/hello`, `samples/src/sounds`, `samples/src/collision`, `samples/src/colors`, `samples/src/effects`, `samples/src/monitors`, `samples/src/hats`, `samples/src/sensing` and `samples/src/legacy` with the real runtime, runs each JAR in a child JVM with `-Djscratch.headless=<frames>` (40 frames for `hello`, 200 for `sounds`, 30 for `monitors` and `legacy`, 100 for `hats`, 10 for the others), and compares what the sprites said. `sensing` runs twice, with and without `-Djscratch.username`. For `monitors` it also takes a screenshot and checks the colors at spots that do not depend on the font: the fill of each monitor's value, and the absence of the ones the project hides. `SilhouetteTest` in `runtime/src/test` covers the alpha mask and hull behind collision, `ColorSensingTest` the color sampling, blending and matching behind the color blocks, and `EffectsTest` the graphic effect math. Add a sample directory and a few expected `say` lines to cover a new block. `ConvertOldProjectsTest` converts `samples/sb2/hello` and the fetched Scratch 1.4 project in Rhino. It runs the converted Scratch 2 project and compares what the sprites said, checks layer order, monitors and a merged text layer pixel by pixel, and checks that the Scratch 1.4 images and sounds have the same MD5 names as when the bundle runs in Node and that ImageIO can read every image. The headless runner fails any test whose project could not load a costume.

To test rendering without a window, run a compiled JAR with `-Djscratch.headless=<frames> -Djscratch.screenshot=out.png` and look at the PNG.

## Converter bundle

`sb3js/build.mjs` builds `jscratch-convert.js` in two steps. esbuild bundles `src/convert.js` with scratch-vm's sources, scratch-storage's TypeScript sources (its published bundle carries a webpack runtime that looks for a page URL), scratch-sb1-converter and fast-png. Then SWC compiles the bundle to ES5 for Rhino, which lacks classes, spread in calls, async functions and `const` in loop heads. Compiling only those features away would mean compiling every `let` and `const` away too, so ES5 costs nothing extra.

An esbuild plugin swaps the modules that need a browser for the stand-ins in `src/headless/`, described in [Architecture](architecture.md#converting-scratch-14-and-2-projects). Another one fixes the byte order of the PNG checksum in scratch-sb1-converter and fails the build if that file changes, so an upstream fix is noticed. `text-encoding` is injected as `TextEncoder` and `TextDecoder` wherever the host has none, which is the case in Rhino.

To try a change without Gradle, run `npm run build` in `sb3js/` and load `build/dist/jscratch-convert.js` into Node with `vm.runInContext`, passing `crypto` into the context. Converting the same project there and through `RhinoProjectConverter` should give the same asset names. A difference means the bundle uses something Rhino does differently.

## Sound decoders

The JDK only plays PCM WAV, so the runtime decodes sounds itself with [dr_mp3 and dr_wav](https://github.com/mackron/dr_libs), single-header C libraries. `runtime/src/main/c/decoders.c` wraps them in an open, read and close function per format, so a sound can be decoded in chunks. The build turns that into JVM classes in three steps, all in `runtime/build.gradle.kts`:

1. `decoderHeaders` downloads the two headers by release tag and checks their SHA-256, because a tag can be moved. The tags and hashes are `dr-mp3`, `dr-wav` and their `-sha256` entries in `gradle/libs.versions.toml`. The download goes through an Ivy repository declared in `settings.gradle.kts`.
2. `decodersWasm` compiles the C file to a standalone WebAssembly module with Emscripten's `emcc`. It runs `emcc` from the path, or through `mise exec` when the shell has not activated mise, which is the usual case in an IDE. `mise.toml` also pins Python, because `emcc` needs 3.10 or newer and the system Python can be older. The task treats `mise.toml` as an input, so a new Emscripten version compiles the decoders again.
3. `decodersJvm` runs [Chicory](https://chicory.dev)'s build-time compiler, which turns every WebAssembly function into a JVM method. `decodersJar` packs the classes and a stripped copy of the module into a JAR on the runtime's class path, so it ends up in every compiled project.

HotSpot never compiles a method over 8000 bytes of bytecode to machine code. Inlining in clang and in Emscripten's `wasm-opt` produces functions past that size, and those run several times slower in the interpreter. That is why the C file is compiled with `-fno-inline-functions` and linked at `-O1`. `SoundDecodersTest` fails if a generated method grows past the limit again, for example after a dr_libs update.

To update dr_libs, change the tags in the version catalog and run `./gradlew :runtime:decoderHeaders`. It fails and prints the SHA-256 of each new header. Review the upstream changes, put the hashes in the catalog, then run `./gradlew :runtime:test`.

## Lint and formatting

[ktlint](https://pinterest.github.io/ktlint/) runs through the `org.jlleitschuh.gradle.ktlint` plugin as part of `./gradlew build`. The configuration lives in `.editorconfig`: `intellij_idea` code style, 120 columns, and a few wrapping rules disabled because they make the block dispatch tables in `TargetCompiler` hard to scan. Run `./gradlew ktlintFormat` before committing.

## Adding a block

1. If the block needs runtime behavior, add a method to `Target` (or `Runtime`) with a plain JVM signature: `Double`, `String`, `Boolean` or `Any?` parameters, no default arguments.
2. Add a case to `emitStatement` or `emitBlockExpr` in `TargetCompiler` that emits the inputs with `emitExpr(st, input, VType.X)` and calls the method with the matching descriptor.
3. If it is a reporter with a fixed result type, add it to `naturalType` so comparisons and arithmetic can skip the dynamic path.
4. Cover it in a sample project and in `blocks.md`.

## Docs

The documentation is a [Zensical](https://zensical.org) site configured in `zensical.toml`.

```sh
uv tool install zensical                # once
(cd mockups && npm ci && npm run build) # render the block mockups
zensical serve                          # live preview
zensical build                          # static site in site/
```

Pushes to `main` that change the docs or the playground publish them to [jscratch.hermits.cc](https://jscratch.hermits.cc/) through the `Pages` workflow. The workflow builds the docs with `zensical build --strict`, so fix any warnings before pushing. See [Deployment](web-playground.md#deployment).

Diagrams are written as Mermaid code blocks. Keep the docs in step with the code: when a block, option or module changes, update the matching page in the same change.

### Block mockups

Pictures of blocks, such as the ones on [Language features](language-features.md), are made with [mockblocks](https://github.com/AstroBlocksMod/mockblocks), a fork of scratchblocks that can draw blocks that do not exist yet. Each picture is a text file in `mockups/src/` written in the scratchblocks syntax, with the planned blocks coming from mockblocks' bundled `jscratch` pack. `npm run build` in `mockups/` turns every file into an SVG in `docs/assets/mockups/`, and a page shows it as an ordinary image: `![...](assets/mockups/name.svg)`.

The SVGs are build output, so git ignores them and the `Docs` and `Pages` workflows render them before building the site. Run the build again after editing a mockup, and before `zensical serve` in a fresh checkout, or the images will be missing.

mockblocks is not on npm, so `mockups/package.json` installs it from GitHub at a fixed commit. To update it, change the commit in `package.json`, run `npm install` in `mockups/` and commit both files. mockblocks sizes each block from the fonts installed on the machine, so the SVGs are rendered on macOS, where Helvetica Neue is available. That is the font Scratch uses and the one the SVGs ask for, and on Linux the blocks would come out sized for a different font.
