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
const sources = readdirSync(here("src/"))
  .filter((name) => name.endsWith(".txt"))
  .map((name) => here(`src/${name}`))

const result = spawnSync(
  process.execPath,
  [here("node_modules/mockblocks/bin/mockblocks.js"), "--pack", "jscratch", "--scale", "0.75", "--out-dir", outDir, ...sources],
  {
    stdio: "inherit",
    // Pango uses CoreText on macOS unless told to use fontconfig.
    env: { ...process.env, PANGOCAIRO_BACKEND: "fc", FONTCONFIG_FILE: here("fonts.conf") },
  },
)
if (result.status !== 0) process.exit(result.status ?? 1)

const measured = "Helvetica Neue, Helvetica, sans-serif"
const drawn = "Helvetica, Arial, sans-serif"
for (const name of readdirSync(outDir).filter((name) => name.endsWith(".svg"))) {
  const svg = readFileSync(outDir + name, "utf8")
  if (!svg.includes(measured)) {
    console.error(`${name} does not use the font mockblocks used to, check build.mjs against the new mockblocks.`)
    process.exit(1)
  }
  writeFileSync(outDir + name, svg.replaceAll(measured, drawn))
}
