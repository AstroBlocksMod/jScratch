// Renders the mockups in src/ to SVG in docs/assets/mockups/.
//
// mockblocks sizes each label for Helvetica Neue, which only macOS has. To get
// the same SVGs on every machine, labels are measured through fontconfig with
// fonts.conf, which swaps Helvetica Neue for a font with Helvetica's widths, and
// the SVGs are then pointed at Helvetica and Arial, which have those widths too.

import { spawnSync } from "node:child_process"
import { readdirSync, readFileSync, writeFileSync } from "node:fs"
import { fileURLToPath } from "node:url"

const here = (path) => fileURLToPath(new URL(path, import.meta.url))
const outDir = here("../docs/assets/mockups/")
const names = readdirSync(here("src/"))
  .filter((name) => name.endsWith(".txt"))
  .map((name) => name.slice(0, -".txt".length))

const result = spawnSync(
  process.execPath,
  [
    here("node_modules/mockblocks/bin/mockblocks.js"),
    "--pack", "jscratch", "--scale", "0.75", "--out-dir", outDir,
    ...names.map((name) => here(`src/${name}.txt`)),
  ],
  {
    stdio: "inherit",
    // Pango uses CoreText on macOS unless told to use fontconfig.
    env: { ...process.env, PANGOCAIRO_BACKEND: "fc", FONTCONFIG_FILE: here("fonts.conf") },
  },
)
if (result.status !== 0) process.exit(result.status ?? 1)

const measured = "Helvetica Neue, Helvetica, sans-serif"
const drawn = "Helvetica, Arial, sans-serif"
// Only the SVGs written just now: one left over from a renamed mockup was already rewritten.
for (const name of names) {
  const file = `${outDir}${name}.svg`
  const svg = readFileSync(file, "utf8")
  if (!svg.includes(measured)) {
    console.error(`${name}.svg does not use the font mockblocks used to, check build.mjs against the new mockblocks.`)
    process.exit(1)
  }
  writeFileSync(file, svg.replaceAll(measured, drawn))
}
