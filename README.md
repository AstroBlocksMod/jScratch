# jScratch

jScratch compiles [Scratch 3](https://scratch.mit.edu) projects (`.sb3`) into Java bytecode and executable JARs. Every sprite becomes a JVM class, every script a method, and a small bundled runtime provides the stage, the scheduler, input and rendering.

```sh
./gradlew :cli:fatJar
java -jar cli/build/libs/cli-0.1.0-SNAPSHOT-all.jar my-game.sb3
java -jar my-game.jar

# or compile a shared project straight from the Scratch website
java -jar cli/build/libs/cli-0.1.0-SNAPSHOT-all.jar https://scratch.mit.edu/projects/10128407/
```

## Highlights

- Real bytecode generated with [ASM](https://asm.ow2.io): primitive arithmetic, real loops, custom blocks as method calls.
- Self-contained output: the JAR carries the runtime, costumes and sounds, and runs on Java 21 or newer.
- Scratch semantics: value conversion, comparison rules, cooperative script scheduling, broadcasts, clones, "run without screen refresh" and the pen behave like the editor.
- Projects are parsed and validated by [scratch-parser](https://github.com/scratchfoundation/scratch-parser), the Scratch Foundation's own JavaScript parser, running in [Rhino](https://github.com/mozilla/rhino) on the JVM and natively in the browser.
- An experimental [browser playground](https://jscratch.hermits.cc/playground/), built by translating the compiler to JavaScript with [TeaVM](https://teavm.org).

## Status

This is an MVP. Typical projects from the Scratch website compile and run, with gaps listed in [docs/blocks.md](docs/blocks.md), such as no extensions besides pen.

## Repository

| Module | Purpose |
| --- | --- |
| `runtime/` | Scratch runtime bundled into compiled JARs |
| `sb3js/` | scratch-parser bundled with esbuild for Rhino and the browser |
| `compiler/` | Platform-neutral core: sb3 model, ASM code generator, JAR writer |
| `compiler-jvm/` | Rhino bridge to scratch-parser, JSON through kotlinx-serialization, end-to-end tests |
| `cli/` | `jscratch` command line tool, including project downloads from scratch.mit.edu |
| `web/` | TeaVM playground |
| `samples/` | Sample projects, zipped into `.sb3` by the build |
| `benchmarks/` | CPU-heavy projects timed on jScratch, TurboWarp and scratch-vm |
| `docs/` | Documentation site (Zensical) |

Useful commands:

```sh
./gradlew build               # lint, test and assemble everything
./gradlew :web:assembleSite   # playground in web/build/site
zensical serve                # preview the docs
```

See the [documentation](https://jscratch.hermits.cc/) for the CLI options, the supported blocks, the architecture and development notes.

## License

jScratch is free software released under the [GNU General Public License v3.0](LICENSE). It bundles scratch-parser, which is licensed under the AGPL-3.0.
