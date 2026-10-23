# Development

## Layout

| Path | Contents |
| --- | --- |
| `runtime/` | The Scratch runtime bundled into compiled JARs: `Cast`, `Ops`, `ScratchList`, `Target`/`Sprite`/`Stage`, `Runtime` (scheduler), `StageRenderer`, `StageWindow`, pen and sound support. Depends on JSVG. |
| `sb3js/` | `src/index.js`, a thin wrapper over scratch-parser's `lib/parse` and `lib/validate`, bundled by esbuild into `build/dist/jscratch-sb3.js`. Node is downloaded by the Gradle node plugin, so nothing needs to be installed. |
| `compiler/` | Platform-neutral core: `json/JsonTree.kt` (tree and the `ProjectJson` interface), `sb3/` (model and parser), `codegen/` (ASM emitters), `JarWriter`, `JScratch` (entry point). Depends on ASM only. |
| `compiler-jvm/` | `RhinoProjectJson` (scratch-parser in Rhino, JSON through kotlinx-serialization) and the end-to-end tests. |
| `cli/` | `jscratch` command. Embeds the runtime fat JAR as a resource. |
| `web/` | TeaVM playground: `Playground.kt`, `BrowserProjectJson.kt` plus the static page in `src/main/webapp/`. |
| `samples/` | Sample projects as directories under `src/`, zipped into `.sb3` files by the build. |
| `docs/` | This documentation, built with Zensical. |

## Everyday commands

```sh
./gradlew build                 # compile, lint, test, assemble everything including the playground
./gradlew :compiler-jvm:test    # Rhino tests and end-to-end: compile the samples and run them headless
./gradlew :runtime:test         # unit tests for value semantics
./gradlew ktlintFormat          # format Kotlin sources
./gradlew :cli:fatJar           # single-file CLI
./gradlew :web:assembleSite     # playground in web/build/site
./gradlew :sb3js:bundle         # rebuild the scratch-parser bundle after touching sb3js/
```

The build uses the Gradle wrapper (Gradle 9), Kotlin 2.4 and a JDK of version 21 or newer on the path. Generated classes and the runtime target Java 21 because the scheduler uses virtual threads. To update JavaScript dependencies, edit `sb3js/package.json`, run `npm install` inside `sb3js/` to refresh `package-lock.json`, and commit both.

## Continuous integration

The `CI` workflow in `.github/workflows/ci.yml` runs on every pull request and every push to `main`. It runs `./gradlew build` on JDK 21 and JDK 25, so both the oldest supported JDK and the current one are covered, and it builds the docs with `zensical build --strict`. When a test fails, the HTML test reports are attached to the run as an artifact. CI never deploys anything; publishing the site is the `Pages` workflow's job (see [Deployment](web-playground.md#deployment)).

## Tests

- `runtime/src/test`: unit tests for `Cast` and `Ops`, the parts most likely to drift from Scratch.
- `compiler-jvm/src/test`: `RhinoProjectJsonTest` checks the scratch-parser bridge (valid project, schema rejection, broken JSON), and `CompileSamplesTest` compiles `samples/src/hello` with the real runtime, runs the JAR in a child JVM with `-Djscratch.headless=40`, and compares what the sprite said. Add a sample directory and a few expected `say` lines to cover a new block.

To test rendering without a window, run a compiled JAR with `-Djscratch.headless=<frames> -Djscratch.screenshot=out.png` and look at the PNG.

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
uv tool install zensical   # once
zensical serve             # live preview
zensical build             # static site in site/
```

Pushes to `main` publish the docs and the playground to [jscratch.hermits.cc](https://jscratch.hermits.cc/) through the `Pages` workflow. The workflow builds the docs with `zensical build --strict`, so fix any warnings before pushing. See [Deployment](web-playground.md#deployment).

Diagrams are written as Mermaid code blocks. Keep the docs in step with the code: when a block, option or module changes, update the matching page in the same change.
