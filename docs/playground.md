# Web playground

The playground runs the compiler in the browser. [TeaVM](https://teavm.org) translates the platform-neutral `compiler` module, which is plain Kotlin on top of `java.util.zip` and ASM, into JavaScript. The page also loads `jscratch-sb3.js`, the same scratch-parser bundle the JVM runs inside Rhino, and the compiled compiler calls it through TeaVM's JavaScript interop (JSO). The page fetches the runtime JAR once, hands it and the uploaded `.sb3` to the compiler, and offers the resulting JAR for download together with the bytecode listing. Nothing leaves the browser.

It is experimental: the output is correct, but the page is deliberately plain, and compiling a large project can take a few seconds because the whole runtime JAR is re-zipped in JavaScript.

## Build and serve

```sh
./gradlew :web:assembleSite
cd web/build/site
python3 -m http.server 8000
```

Then open `http://localhost:8000/`. The site is static: `index.html`, `playground.js`, `playground.css`, the scratch-parser bundle `jscratch-sb3.js`, the TeaVM output `jscratch.js` and `jscratch-runtime.jar`. Any static host will do.

## How the page talks to the compiler

`web/src/main/kotlin/org/jscratch/web/Playground.kt` installs one function:

```js
window.jscratch.compile(sb3Int8Array, runtimeJarInt8Array, includeListing)
```

It returns `{ ok: true, jar, listing, warnings, classes, targets }` or `{ ok: false, error }`. Primitive arrays cross the TeaVM boundary as typed arrays, which is why the page passes `Int8Array`s.

`BrowserProjectJson.kt` is the browser side of the `ProjectJson` interface: `JSON.parse` for plain JSON and `window.jscratchSb3.parseProject` for `project.json`, both through small `@JSBody` helpers that walk the resulting JavaScript objects into the compiler's tree.

## What cannot work in the browser

The compiled JAR cannot run in the page: there is no JVM there. Download it and run it with `java -jar`.
