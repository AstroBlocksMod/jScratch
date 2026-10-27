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

Measured on October 27, 2026 on a Mac with an Apple M4 and 16 GB of memory, at commit `4961d39`. jScratch ran on JDK 25.0.4, TurboWarp (`TurboWarp/scratch-vm` at `371839d`) and scratch-vm (`@scratch/scratch-vm` 15.3.0) on Node.js 26.12.0. Median of 5 runs, in milliseconds.

| Project | jScratch | TurboWarp | scratch-vm | jScratch vs TurboWarp | jScratch vs scratch-vm |
| --- | ---: | ---: | ---: | --- | --- |
| fib | 54.9 | 20.8 | 3122.3 | 2.6x slower | 56.9x faster |
| loops | 19.6 | 23.7 | 81.8 | 1.2x faster | 4.2x faster |
| motion | 160.1 | 166.4 | 2633.1 | 1.0x faster | 16.4x faster |
| primes | 674.2 | 82.9 | 17998.3 | 8.1x slower | 26.7x faster |
| sieve | 185.1 | 51.7 | 7868.2 | 3.6x slower | 42.5x faster |
| sort | 124.5 | 29.7 | 9836.5 | 4.2x slower | 79.0x faster |
| strings | 234.0 | 85.9 | 4262.9 | 2.7x slower | 18.2x faster |

Every engine gave the expected result on every project. The numbers move with the machine, so compare engines within one run, not across machines. They also move between runs on one machine: this run was about 5% slower than one earlier the same day on all three engines, and an earlier run of 3 had TurboWarp at 335.3 ms on `motion`, more than twice its usual time, so a single run can mislead.

Until this run, every loop iteration inside "run without screen refresh" read the clock and the script's volatile stop flag to enforce the 500 ms limit. Now only every 256th iteration does. Timed on jScratch alone the same day, before and after the change, `sort` went from 286.8 ms to 115.6 ms, `sieve` from 392.8 ms to 174.5 ms, `primes` from 1049.4 ms to 638.5 ms, `motion` from 249.3 ms to 142.6 ms and `strings` from 288.6 ms to 210.9 ms. Skipping the check entirely measured within noise of checking every 256th iteration, so the limit stays.

Three days before that, before yields became cheap, `loops` took 2705.6 ms on the same machine: 119.5 times slower than TurboWarp and 35.6 times slower than scratch-vm. Every yield then switched from the scheduler thread to the script's virtual thread and back through two semaphores, about 5 microseconds. Now a script that yields while no other script is active keeps running without a thread switch, see [Runtime scheduling](architecture.md#runtime-scheduling). `motion` went from 425.6 ms to 262.1 ms in the same change, because a warp loop no longer looks up the running script in a `ThreadLocal` on every iteration.

## Reading the results

jScratch is far ahead of scratch-vm everywhere. It is ahead of TurboWarp on `loops`, level with it on `motion`, and behind it on every project that works mostly with variables and lists. The generated bytecode shows why: variables are stored as objects, so every `set` and `change` allocates a `Double`, and comparisons go through the general `Cast.compare`.

Fixing that is an item on the [roadmap](roadmap.md#then-performance-and-robustness). The benchmarks are there to show how much each fix is worth.

## Adding a benchmark

1. Add a directory under `benchmarks/projects/` with a `project.json`, the way `samples/` works. The build zips it into `benchmarks/build/sb3/` together with the costumes in `benchmarks/assets/`, which every project shares. Reuse those costumes, or add new assets to that directory.
2. The project has to finish on its own: no `forever`, no waiting for input. Its last `say` is its result.
3. Add a `benchmark.properties` with a one-line `description` and the `expected` result.
4. Size the work so TurboWarp needs at least about 20 ms. Shorter runs mostly measure JIT warm-up.
5. List it in the table above.
