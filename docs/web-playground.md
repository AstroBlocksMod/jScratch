# Web playground

The playground runs the compiler in the browser. [TeaVM](https://teavm.org) translates the platform-neutral `compiler` module, which is plain Kotlin on top of `java.util.zip` and ASM, into JavaScript. The page also loads `jscratch-sb3.js`, the same scratch-parser bundle the JVM runs inside Rhino, and the compiled compiler calls it through TeaVM's JavaScript interop (JSO). The page fetches the runtime JAR once, hands it and the uploaded `.sb3` to the compiler, and offers the resulting JAR for download together with the bytecode listing. Projects from Scratch 1.4 (`.sb`) and Scratch 2 (`.sb2`) are [converted](architecture.md#converting-scratch-14-and-2-projects) to Scratch 3 first, and the page offers the converted `.sb3` for download as well. Nothing leaves the browser.

It is experimental: the output is correct, but the page is deliberately plain, and compiling a large project can take a few seconds because the whole runtime JAR is re-zipped in JavaScript.

[Open the playground](https://jscratch.hermits.cc/playground/){ .md-button .md-button--primary }

## Deployment

The `Pages` workflow in `.github/workflows/pages.yml` publishes both this documentation and the playground to GitHub Pages on every push to `main` that changes either one: the docs, the block mockups, `zensical.toml`, the modules the playground is built from (`web`, `compiler`, `sb3js`, `runtime`) or the Gradle setup. It runs `./gradlew build` first, so a commit that fails lint or tests is never deployed. A separate job renders the [block mockups](development.md#block-mockups) on macOS. Then it builds the docs with Zensical and puts the two sites together: the docs at the root of [jscratch.hermits.cc](https://jscratch.hermits.cc/) and the playground under `/playground/`. The playground uses only relative URLs, so it works under any path. No docs page may build to `/playground/`, which is why this page is `web-playground.md`; the workflow fails if the two collide. The custom domain is configured in the repository's Pages settings, which is why the site has no `CNAME` file. You can also start the workflow by hand from the Actions tab.

## Build and serve locally

```sh
./gradlew :web:assembleSite
cd web/build/site
python3 -m http.server 8000
```

Then open `http://localhost:8000/`. The site is static: `index.html`, `playground.js`, `playground.css`, the scratch-parser bundle `jscratch-sb3.js`, the converter bundle `jscratch-convert.js`, the TeaVM output `jscratch.js` and `jscratch-runtime.jar`. Any static host will do.

## How the page talks to the compiler

`web/src/main/kotlin/org/jscratch/web/Playground.kt` installs three functions:

```js
window.jscratch.compile(sb3Int8Array, runtimeJarInt8Array, includeListing, turboWarp)
window.jscratch.readOld(projectInt8Array)
window.jscratch.writeSb3(projectJson, names, datas)
```

`turboWarp` comes from the "Tell the project it runs in TurboWarp" checkbox and does what the CLI's `--turbowarp` option does: the hacked [TurboWarp blocks](blocks.md#custom-blocks) report true. `compile` returns `{ ok: true, jar, listing, warnings, classes, targets }` or `{ ok: false, error }`. Primitive arrays cross the TeaVM boundary as typed arrays, which is why the page passes `Int8Array`s.

The other two exist because the converter returns a promise, which TeaVM code cannot wait for. `readOld` returns null for anything that is not a Scratch 1.4 or 2 project. Otherwise it returns `{ format, label, projectJson, names, datas }`: for Scratch 2 the `project.json` text and the other files of the archive, for Scratch 1.4 the whole file as the only entry of `datas`. The page then loads `jscratch-convert.js`, which it only does when the first old project comes in, and awaits `jscratchConvert.convertSb1` or `convertSb2`. `writeSb3` zips the result into the `.sb3` that goes to `compile`.

`BrowserProjectJson.kt` is the browser side of the `ProjectJson` interface: `JSON.parse` for plain JSON and `window.jscratchSb3.parseProject` for `project.json`, both through small `@JSBody` helpers that walk the resulting JavaScript objects into the compiler's tree.

## Running projects in the page

The playground cannot run a compiled project yet. Download the JAR and run it with `java -jar`.

There are two reasons it does not run in the page. TeaVM compiles ahead of time, so the page has no way to load the classes the compiler has just written. And the runtime depends on parts of the JDK that TeaVM does not provide: Swing and Java2D for the window and rendering, JSVG for vector costumes, Chicory for the sound decoders, and virtual threads for the scheduler.

Both can be worked around, and a preview in the page is on the [roadmap](roadmap.md#playground). The generated code only uses a small set of JVM instructions and calls nothing but the runtime methods listed in `RT`, so a small bytecode interpreter compiled with TeaVM could run the exact classes the compiler produced. The runtime would be split into a shared core and two backends: the current one for the JVM, and one for the browser that draws to a canvas, lets the browser render SVG costumes and decodes sounds with Web Audio. Scripts would run on TeaVM's own threads. Because the bytecode is interpreted inside JavaScript, the preview would be slower than scratch-vm, so it is meant to show that a project works, not to replace running the JAR.
