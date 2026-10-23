// Times the benchmark projects on scratch-vm or TurboWarp's VM, for comparison with jScratch.
//
//   node run.js <scratch-vm|turbowarp> <sb3 dir> <results.json> <runs> [name,name,...]
//
// Every run gets a fresh Node process, the same way jScratch gets a fresh JVM, so both
// pay their JIT warm-up. The VM runs headless with no renderer. Frames are stepped back
// to back with the 30 fps work budget, which matches jScratch's headless mode: scripts
// yield exactly as they would in the editor, but nobody waits for the next screen refresh.
"use strict";

const childProcess = require("child_process");
const fs = require("fs");
const path = require("path");

const MAX_FRAMES = 100000;
const TIMEOUT_MS = 300000;

const ENGINES = {
  "scratch-vm": {
    name: "scratch-vm",
    // The package's Node bundle reads a stylesheet it does not ship, so load the sources instead.
    entry: path.join(__dirname, "node_modules", "@scratch", "scratch-vm", "src", "index.js"),
    version: () => "@scratch/scratch-vm " + packageVersion("@scratch/scratch-vm"),
  },
  turbowarp: {
    name: "TurboWarp",
    entry: path.join(__dirname, "node_modules", "turbowarp-vm", "src", "index.js"),
    version: () => {
      const spec = require("./package.json").dependencies["turbowarp-vm"];
      return "TurboWarp/scratch-vm " + spec.slice(spec.lastIndexOf("/") + 1, spec.lastIndexOf("/") + 8);
    },
  },
};

function packageVersion(name) {
  const file = path.join(__dirname, "node_modules", name, "package.json");
  return JSON.parse(fs.readFileSync(file, "utf8")).version;
}

/** Child process: runs one project once and reports back over IPC. */
async function runOnce(engine, file) {
  const VirtualMachine = require(ENGINES[engine].entry);
  const vm = new VirtualMachine();
  let output = null;
  vm.runtime.on("SAY", (target, type, text) => {
    if (text !== "") output = String(text);
  });
  await vm.loadProject(fs.readFileSync(file));
  vm.runtime.currentStepTime = 1000 / 30;

  const start = performance.now();
  vm.greenFlag();
  let frames = 0;
  while (vm.runtime.threads.length > 0) {
    if (frames >= MAX_FRAMES || performance.now() - start > TIMEOUT_MS) {
      throw new Error("did not finish within " + frames + " frames");
    }
    vm.runtime._step();
    frames++;
  }
  return { ms: performance.now() - start, frames, output };
}

function runInChild(engine, file) {
  return new Promise((resolve) => {
    const child = childProcess.fork(__filename, ["--once", engine, file], {
      stdio: ["ignore", "ignore", "pipe", "ipc"],
    });
    let stderr = "";
    let result = null;
    child.stderr.on("data", (d) => (stderr += d));
    child.on("message", (m) => (result = m));
    child.on("exit", (code) => {
      if (result && !result.error) resolve(result);
      else resolve({ error: (result && result.error) || "exited with " + code + "\n" + stderr.trim() });
    });
  });
}

async function main(argv) {
  const [engine, sb3Dir, outFile, runsArg, onlyArg] = argv;
  if (!ENGINES[engine] || !sb3Dir || !outFile) {
    console.error("usage: node run.js <scratch-vm|turbowarp> <sb3 dir> <results.json> <runs> [names]");
    process.exit(2);
  }
  const runs = Math.max(1, parseInt(runsArg || "3", 10));
  const only = onlyArg ? onlyArg.split(",").filter((s) => s !== "") : [];
  const files = fs
    .readdirSync(sb3Dir)
    .filter((f) => f.endsWith(".sb3"))
    .map((f) => f.slice(0, -4))
    .filter((name) => only.length === 0 || only.includes(name))
    .sort();

  const benchmarks = [];
  for (const name of files) {
    const entry = { name, times: [], frames: 0, output: null, error: null };
    for (let i = 0; i < runs; i++) {
      const r = await runInChild(engine, path.join(sb3Dir, name + ".sb3"));
      if (r.error) {
        entry.error = r.error;
        break;
      }
      entry.times.push(r.ms);
      entry.frames = r.frames;
      entry.output = r.output;
    }
    const shown = entry.error ? "failed: " + entry.error : entry.times.map((t) => t.toFixed(1)).join(", ") + " ms";
    console.log(ENGINES[engine].name + " " + name + ": " + shown);
    benchmarks.push(entry);
  }

  const results = {
    engine,
    name: ENGINES[engine].name,
    version: ENGINES[engine].version(),
    platform: "Node.js " + process.version,
    date: new Date().toISOString(),
    benchmarks,
  };
  fs.mkdirSync(path.dirname(outFile), { recursive: true });
  fs.writeFileSync(outFile, JSON.stringify(results, null, 2) + "\n");
}

if (process.argv[2] === "--once") {
  runOnce(process.argv[3], process.argv[4]).then(
    (r) => process.send(r, () => process.exit(0)),
    (e) => process.send({ error: String((e && e.message) || e) }, () => process.exit(1)),
  );
} else {
  main(process.argv.slice(2)).catch((e) => {
    console.error(e);
    process.exit(1);
  });
}
