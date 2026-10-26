// Builds the two bundles in build/dist:
//
// - jscratch-sb3.js: scratch-parser for every compile (src/index.js).
// - jscratch-convert.js: the Scratch 1.4 and 2 converter (src/convert.js), only
//   loaded when an old project comes in.
//
// Both run in the browser and in Rhino. Rhino supports most of ES6 but not classes,
// spread in calls, async functions, or `const` in the head of a for-in or for-of
// loop. scratch-parser happens to use none of them, so esbuild's ES2015 output is
// enough for it. scratch-vm uses all of them, and esbuild cannot lower classes, so
// SWC turns the converter bundle into ES5. Lowering only those features means
// lowering every let and const as well, which leaves little ES6 and saves nothing.
import { readFile, writeFile } from "node:fs/promises";
import path from "node:path";
import * as esbuild from "esbuild";
import { transform } from "@swc/core";

const dist = "build/dist";

await esbuild.build({
  entryPoints: ["src/index.js"],
  bundle: true,
  format: "iife",
  globalName: "jscratchSb3",
  platform: "browser",
  target: "es2015",
  outfile: `${dist}/jscratch-sb3.js`,
});

const vmSrc = path.resolve("node_modules/@scratch/scratch-vm/src");
const headless = path.resolve("src/headless");

const storageSrc = path.resolve("node_modules/@scratch/scratch-storage/src/index.ts");

// Swaps the modules that need a DOM, a canvas or a console for the ones in
// src/headless, and lets the entry point reach into scratch-vm's sources, which
// its package exports do not list. scratch-storage is built from its TypeScript
// sources, because its published bundle carries a webpack runtime that looks for
// a page URL.
const headlessPlugin = {
  name: "headless",
  setup(build) {
    build.onResolve({ filter: /^@scratch\/scratch-storage$/ }, () => ({ path: storageSrc }));
    build.onResolve({ filter: /^\.\/scratchFetch$/ }, (args) =>
      args.importer.startsWith(path.dirname(storageSrc)) ? { path: path.join(headless, "scratch-fetch.js") } : undefined,
    );
    // The default costumes and sound, which webpack's arraybuffer-loader would inline.
    build.onResolve({ filter: /\?arrayBuffer$/ }, (args) => ({
      path: path.join(args.resolveDir, args.path.replace(/\?arrayBuffer$/, "")),
      namespace: "array-buffer",
    }));
    build.onLoad({ filter: /.*/, namespace: "array-buffer" }, async (args) => ({
      contents: `export default new Uint8Array([${[...(await readFile(args.path))].join(",")}]).buffer;`,
      loader: "js",
    }));
    const fromVm = (args) => args.importer.startsWith(vmSrc);
    // A relative import inside scratch-vm that lands on `file` (without extension).
    const vmModule = (args, file) =>
      fromVm(args) && path.join(args.resolveDir, args.path).replace(/\.js$/, "") === path.join(vmSrc, file);
    build.onResolve({ filter: /^@scratch\/scratch-vm\/src\// }, (args) => ({
      path: path.join(vmSrc, args.path.slice("@scratch/scratch-vm/src/".length)) + ".js",
    }));
    build.onResolve({ filter: /^\.\.?\/.*log$/ }, (args) =>
      vmModule(args, "util/log") ? { path: path.join(headless, "log.js") } : undefined,
    );
    build.onResolve({ filter: /^\.\.?\/.*load-costume(\.js)?$/ }, (args) =>
      vmModule(args, "import/load-costume") ? { path: path.join(headless, "load-costume.js") } : undefined,
    );
    build.onResolve({ filter: /^@scratch\/scratch-svg-renderer$/ }, () => ({
      path: path.join(headless, "svg-renderer.js"),
    }));
    build.onResolve({ filter: /^jszip$/ }, (args) =>
      fromVm(args) ? { path: path.join(headless, "jszip.js") } : undefined,
    );
  },
};

// scratch-sb1-converter writes the zlib checksum at the end of every PNG it makes
// as little-endian, where zlib wants big-endian. Browsers do not check it, so
// Scratch 3 never noticed, but Java's ImageIO refuses those images. The build
// fails if the line changes, so a fix upstream does not go unnoticed.
const sb1ChecksumPlugin = {
  name: "sb1-png-checksum",
  setup(build) {
    build.onLoad({ filter: /scratch-sb1-converter[\\/]src[\\/]coders[\\/]deflate-packets\.js$/ }, async (args) => {
      const source = await readFile(args.path, "utf8");
      const fixed = source
        .replace("import {Uint8, Uint16LE, Uint32LE} from", "import {Uint8, Uint16LE, Uint32BE} from")
        .replace("checksum: Uint32LE", "checksum: Uint32BE");
      if (!fixed.includes("checksum: Uint32BE") || fixed.includes("Uint32LE")) {
        throw new Error(`${args.path} changed; check whether the PNG checksum fix is still needed`);
      }
      return { contents: fixed, loader: "js" };
    });
  },
};

const converter = await esbuild.build({
  entryPoints: ["src/convert.js"],
  bundle: true,
  format: "iife",
  globalName: "jscratchConvert",
  platform: "browser",
  target: "es2020",
  define: { "process.env.NODE_ENV": '"production"', global: "globalThis", self: "globalThis" },
  inject: ["src/headless/text-encoding.js"],
  plugins: [headlessPlugin, sb1ChecksumPlugin],
  write: false,
});

// Printed without whitespace (names stay as they are), or the big lookup tables
// in text-encoding take one line per number and the bundle doubles in size.
const es5 = await transform(converter.outputFiles[0].text, {
  jsc: { target: "es5", parser: { syntax: "ecmascript" }, minify: { compress: false, mangle: false } },
  minify: true,
  module: { type: "es6" },
  isModule: false,
});

// SWC puts its helpers at the top level. Keep them out of the page's globals.
const wrapped = `var jscratchConvert = (function () {\n${es5.code}\nreturn jscratchConvert;\n})();\n`;
await writeFile(`${dist}/jscratch-convert.js`, wrapped);
