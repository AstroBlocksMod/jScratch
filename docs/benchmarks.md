# Benchmarks

jScratch compiles projects to JVM bytecode, and HotSpot turns the hot parts into machine code. That alone does not make it faster than everything else: TurboWarp also compiles projects, to JavaScript, and V8 compiles that to machine code too. The benchmarks measure where jScratch actually stands against the Scratch Foundation's scratch-vm and TurboWarp, on the same `.sb3` files.

## Running them

```sh
./gradlew :benchmarks:benchmark                             # all engines, then the report
./gradlew :benchmarks:benchmarkJScratch                     # jScratch only
./gradlew :benchmarks:benchmark -Pbenchmark.runs=5          # more runs per project (default 3)
./gradlew :benchmarks:benchmark -Pbenchmark.only=fib,loops  # some projects only
```

The report goes to `benchmarks/build/reports/benchmarks/report.md` and is printed at the end. It uses the latest results of each engine, so after `benchmarkJScratch` it still shows the earlier TurboWarp and scratch-vm numbers next to the new jScratch ones. Node and both VMs are downloaded by the build, so nothing needs to be installed. A full run takes a few minutes, almost all of it spent in scratch-vm.

The `Benchmarks` workflow runs the same command on every push to `main` that could change the results. See [Continuous integration](development.md#continuous-integration).

## What is measured

Every engine runs the same `.sb3` file headless, with nothing drawn. The time runs from the green flag until every script has finished. Each run starts a fresh process (a JVM for jScratch, Node for the others), so JIT warm-up counts, like it would for a player. Starting the process and loading the project do not. The report shows the median of all runs.

Frames are stepped back to back without waiting for the next screen refresh, but with the usual rules: a frame ends when a script asks for a redraw or after 75% of a 30 fps frame, and "run without screen refresh" gives up after 500 ms. jScratch does this in its headless mode (`-Djscratch.headless` with `-Djscratch.timing`). For scratch-vm and TurboWarp, `benchmarks/js/run.js` calls the VM's own step function in a loop.

Each project ends by saying its result, and the report checks it against the expected value in the project's `benchmark.properties`. A wrong answer shows up as "(wrong)" in the table and as an entry under "Problems". If jScratch gets one wrong, the report task fails.

The versions of both VMs are pinned in `benchmarks/js/package.json`. scratch-vm is loaded from its sources because the Node bundle in the npm package reads a stylesheet that the package does not ship.

## The projects

| Project | What it stresses |
| --- | --- |
| `primes` | Number variables, comparisons and `mod` in a tight loop. Counts the primes below 60000 by trial division, 20 times. |
| `sieve` | List reads and writes. A sieve of Eratosthenes over a 20000-item list, 300 times. |
| `strings` | `join`, `letter of` and `length` on growing strings, 15000 strings of 300 letters. |
| `fib` | Custom block calls. Naive recursive Fibonacci of 32, about 7 million calls. |
| `sort` | List items compared and swapped. Bubble sort of 600 numbers, 40 times. |
| `motion` | Sprite state. 9 million move and turn steps. |
| `loops` | The scheduler. A plain `repeat` of 500000 that yields to the other scripts on every iteration, as every loop outside "run without screen refresh" does. |

All but `loops` do their work inside a custom block that runs without screen refresh, which is how compute-heavy Scratch projects are usually written.

## Latest results

Measured on October 26, 2026 on a Mac with an Apple M4 and 16 GB of memory, at commit `4c76d56`. jScratch ran on JDK 25.0.4, TurboWarp (`TurboWarp/scratch-vm` at `371839d`) and scratch-vm (`@scratch/scratch-vm` 15.3.0) on Node.js 26.12.0. Median of 3 runs, in milliseconds.

| Project | jScratch | TurboWarp | scratch-vm | jScratch vs TurboWarp | jScratch vs scratch-vm |
| --- | ---: | ---: | ---: | --- | --- |
| fib | 64.8 | 22.2 | 3001.8 | 2.9x slower | 46.3x faster |
| loops | 20.6 | 23.1 | 88.9 | 1.1x faster | 4.3x faster |
| motion | 290.7 | 335.3 | 2231.9 | 1.2x faster | 7.7x faster |
| primes | 1119.0 | 98.4 | 18342.4 | 11.4x slower | 16.4x faster |
| sieve | 395.8 | 63.5 | 7957.1 | 6.2x slower | 20.1x faster |
| sort | 289.4 | 48.2 | 9884.5 | 6.0x slower | 34.2x faster |
| strings | 308.5 | 83.7 | 4810.7 | 3.7x slower | 15.6x faster |

Every engine gave the expected result on every project. The numbers move with the machine, so compare engines within one run, not across machines.

Three days earlier, before yields became cheap, `loops` took 2705.6 ms on the same machine: 119.5 times slower than TurboWarp and 35.6 times slower than scratch-vm. Every yield then switched from the scheduler thread to the script's virtual thread and back through two semaphores, about 5 microseconds. Now a script that yields while no other script is active keeps running without a thread switch, see [Runtime scheduling](architecture.md#runtime-scheduling). `motion` went from 425.6 ms to 290.7 ms in the same change, because a warp loop no longer looks up the running script in a `ThreadLocal` on every iteration.

## Reading the results

jScratch is far ahead of scratch-vm everywhere, and ahead of TurboWarp on `loops` and `motion`, but behind it on everything that computes inside a custom block. The generated bytecode shows why:

- **Variables are boxed.** Variables are stored as objects, so every `set` and `change` allocates a `Double`, and comparisons go through the general `Cast.compare`.
- **Warp loops still check for yields.** Inside "run without screen refresh", every loop iteration reads the running script and the clock.

Each of these is an item on the [roadmap](roadmap.md#then-performance-and-robustness). The benchmarks are there to show how much each fix is worth.

## Adding a benchmark

1. Add a directory under `benchmarks/projects/` with a `project.json`, the way `samples/` works. The build zips it into `benchmarks/build/sb3/` together with the costumes in `benchmarks/assets/`, which every project shares. Reuse those costumes, or add new assets to that directory.
2. The project has to finish on its own: no `forever`, no waiting for input. Its last `say` is its result.
3. Add a `benchmark.properties` with a one-line `description` and the `expected` result.
4. Size the work so TurboWarp needs at least about 20 ms. Shorter runs mostly measure JIT warm-up.
5. List it in the table above.
