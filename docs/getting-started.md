# Getting started

## Requirements

- A JDK, version 21 or newer, both to build jScratch and to run compiled projects.
- [mise](https://mise.jdx.dev), to build jScratch. Run `mise install` once in the repository root. It installs Emscripten, which compiles the runtime's sound decoders, and the Python version Emscripten runs on.

The Gradle wrapper downloads the build tool and everything else. Compiled JARs carry their own runtime, so running them needs only Java.

## Build the CLI

```sh
./gradlew :cli:fatJar
```

This produces `cli/build/libs/cli-<version>-all.jar`, a single runnable JAR that contains the compiler, ASM and the embedded runtime. The `:cli:installDist` task produces a conventional `bin/jscratch` launcher under `cli/build/install/jscratch/` instead.

## Compile a project

Export a project from the Scratch editor with **File, Save to your computer** to get an `.sb3` file (older `.sb` and `.sb2` files work too, see [below](#scratch-14-and-scratch-2-projects)), then:

```sh
java -jar cli/build/libs/cli-0.1.0-SNAPSHOT-all.jar my-game.sb3
java -jar my-game.jar
```

A window opens with the stage, a green flag and a stop button. The green flag runs automatically on launch.

### Compile straight from the Scratch website

Instead of a file, you can give the CLI the link to a shared project, or just its number:

```sh
java -jar cli/build/libs/cli-0.1.0-SNAPSHOT-all.jar https://scratch.mit.edu/projects/10128407/
java -jar 10128407.jar
```

The CLI fetches the project and all of its costumes and sounds from scratch.mit.edu, the same way the Scratch player does, and compiles the result. Links ending in `/editor` or `/fullscreen` work too. The JAR is named after the project ID and written to the current directory unless you pass `-o`. Add `--save-sb3 <file>` to keep a copy of the downloaded project.

A few things to know:

- Only shared projects can be downloaded. An unshared project reports "not found", just as it does on the website.
- Projects last saved in Scratch 2 or 1.4 are converted to Scratch 3 first, see below.
- If a local file with the same name as the argument exists, the file is used, so a file called `123` still compiles as a file.

### Scratch 1.4 and Scratch 2 projects

The CLI also takes projects saved by older versions of Scratch: `.sb` files from Scratch 1.4 and `.sb2` files from Scratch 2, and shared projects on the Scratch website that were last saved by either one. It converts them to Scratch 3 before compiling, with the same code the Scratch 3 editor uses when it opens an old project: [scratch-sb1-converter](https://github.com/scratchfoundation/scratch-sb1-converter) for Scratch 1.4, and the Scratch 2 importer and the Scratch 3 exporter from [scratch-vm](https://github.com/scratchfoundation/scratch-editor). Blocks are translated the way the editor translates them, so a block the editor leaves out (a few Scratch 1.4 blocks have no Scratch 3 counterpart) is left out here too, with a warning.

```sh
java -jar cli/build/libs/cli-0.1.0-SNAPSHOT-all.jar old-game.sb2
java -jar cli/build/libs/cli-0.1.0-SNAPSHOT-all.jar old-game.sb --convert
```

The first command compiles `old-game.jar`. The second only converts, and writes `old-game.sb3`, which opens in the Scratch 3 editor like any other project. `--save-sb3 <file>` keeps the converted project next to the JAR. The CLI refuses to write the converted project over the file it came from, which could otherwise happen to an old project saved under an `.sb3` name.

The editor touches up a project in a few more places while it loads it, using the browser's canvas and SVG support. The converter runs without a browser, so it leaves those out:

- Bitmap costumes keep the resolution they were saved with. The editor doubles them to Scratch 3's resolution; jScratch draws them at the same size either way.
- Vector costumes keep their Scratch 2 SVG. The editor rewrites some of them to make up for differences in how Scratch 2 drew text and strokes, so text in a Scratch 2 vector costume can sit slightly differently.
- Costumes that went from Scratch 1.4 through Scratch 2 can keep their text as a separate image. The converter draws it onto the costume as the editor does, but only over a PNG image. Over a JPEG image the text is left out, with a warning.

### Options

| Option | Effect |
| --- | --- |
| `-o`, `--output <file>` | Where to write the JAR. Defaults to the input name with a `.jar` extension, or `<id>.jar` in the current directory for a downloaded project. |
| `--convert` | Write the project as an `.sb3` file instead of compiling it. For an `.sb` or `.sb2` file this converts it; for a link to the Scratch website it downloads it (and converts it if needed). `-o` names the file, which defaults to the input name with an `.sb3` extension, or `<id>.sb3` in the current directory for a download. |
| `--save-sb3 <file>` | When the project was downloaded or converted, also save it as an `.sb3` file. With `--convert`, use `-o` instead. |
| `--run` | Run the JAR right after compiling. |
| `--dump` | Print the generated bytecode as an ASM listing. Useful to see what a script compiled to. |
| `--no-runtime` | Emit only the generated classes and assets. The result needs the runtime on the class path to run. |

Warnings about unsupported blocks are printed to stderr. An unsupported block compiles to a no-op (statements) or an empty value (reporters), so the rest of the project still runs.

## Running headless

Compiled JARs understand a few system properties that are handy for tests and automation:

| Property | Effect |
| --- | --- |
| `-Djscratch.headless=<frames>` | Run up to that many frames with a virtual 30 fps clock and no window, stopping early once no script is left running and no hat can start one. Without scripts and a mouse nothing moves, and loudness is always -1 in headless mode, so only a `when timer greater than` hat whose condition is still false keeps the run going, along with any edge-activated hat that has not been checked yet. Every `say` and `think` is printed to stdout as `<sprite> says: <text>`. |
| `-Djscratch.screenshot=<file.png>` | In headless mode, save the final frame as a PNG at twice the stage resolution. |
| `-Djscratch.timing=true` | In headless mode, print the number of frames and the wall time from the green flag to the end of the run to stderr, as `jscratch: <frames> frames in <ms> ms`. The [benchmarks](benchmarks.md) use it. |
| `-Djscratch.username=<name>` | What the `username` block reports, in a window or headless. Without it the block reports an empty string, as for a player who is not signed in. |

```sh
java -Djscratch.headless=90 -Djscratch.screenshot=frame.png -jar my-game.jar
```

## Try the sample

The repository ships a small sample project built from reviewable sources in `samples/src/hello/`:

```sh
./gradlew :samples:buildSamples :cli:fatJar
java -jar cli/build/libs/cli-0.1.0-SNAPSHOT-all.jar samples/build/sb3/hello.sb3 --run
```
