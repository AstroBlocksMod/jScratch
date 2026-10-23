# jScratch

jScratch is an ahead-of-time compiler for [Scratch 3](https://scratch.mit.edu) projects. It reads an `.sb3` file and produces a plain executable JAR: every sprite becomes a JVM class, every script becomes a method, and a small bundled runtime takes care of the stage, the scheduler, input and rendering.

```
jscratch my-game.sb3
java -jar my-game.jar
```

## What you get

- **Real bytecode.** Scripts are compiled to JVM methods with [ASM](https://asm.ow2.io). Arithmetic runs on primitive doubles, loops are loops, custom blocks are method calls. There is no interpreter in the loop.
- **One self-contained JAR.** The output embeds the runtime, the costumes and the sounds. It runs anywhere with Java 21 or newer.
- **Scratch semantics.** Values convert the way Scratch converts them, scripts yield where Scratch yields, broadcasts, clones, custom blocks with "run without screen refresh" and the pen all behave as in the editor.
- **The official parser.** Projects are parsed and validated against the sb3 schema by [scratch-parser](https://github.com/scratchfoundation/scratch-parser), the Scratch Foundation's JavaScript parser, running in Rhino on the JVM and natively in the browser.
- **Straight from the Scratch website.** Give the CLI a project link instead of a file and it downloads the project, costumes and sounds for you.
- **A browser playground.** The compiler itself is translated to JavaScript with [TeaVM](https://teavm.org), so you can compile a project without installing anything. [Try it](https://jscratch.hermits.cc/playground/) or read how it works in [Web playground](web-playground.md).

## Status

jScratch is an MVP. It compiles and runs typical projects from the Scratch website, but some blocks are still missing and rendering uses bounding boxes for collision. [Supported blocks](blocks.md) lists exactly what works today, and the [Roadmap](roadmap.md) lists what comes next.

## Where to go next

- [Getting started](getting-started.md): build the CLI and compile your first project.
- [Architecture](architecture.md): how the pipeline and the runtime fit together.
- [Development](development.md): module layout, tests, lint and docs.
- [Roadmap](roadmap.md): what is planned next.

jScratch is free software, released under the [GPL-3.0](https://www.gnu.org/licenses/gpl-3.0.html).
