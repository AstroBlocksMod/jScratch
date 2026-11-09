# SB3O

SB3O is a Scratch 3 project optimizer. Like a minifier for a program, it rewrites a project at the block level so that it does the same thing with fewer blocks: `say ((2) + (2))` becomes `say [4]`, an `if` whose condition is always true runs its branch in place, and scripts that can never run are dropped. The result is an ordinary `.sb3` that opens in the Scratch editor and runs the same on scratch-vm, TurboWarp and jScratch.

It is a tool for Scratch and Scratch-based engines first. jScratch uses it in a few places: the `--optimize` option of the CLI, the "Optimize with SB3O first" checkbox of the [playground](web-playground.md), and the project model and `project.json` reader that the compiler is built on.

## Parts

| Part | Where | License |
| --- | --- | --- |
| The library | `sb3o/` | [BSD 3-Clause](https://github.com/AstroBlocksMod/jScratch/blob/main/sb3o/LICENSE) |
| The `sb3o` command | `sb3o-cli/` | GPL-3.0, like the rest of jScratch |

The library is Kotlin Multiplatform and builds for the JVM and for JavaScript, with TypeScript declarations, so it can go into a JVM program or an npm package alike. It has no dependencies beyond the Kotlin standard library. `sb3o/` carries its own license file; everything else in the repository stays under the GPL-3.0.

## Using the command

```sh
./gradlew :sb3o-cli:fatJar
java -jar sb3o-cli/build/libs/sb3o-cli-0.1.0-SNAPSHOT-all.jar my-game.sb3
```

This writes `my-game-optimized.sb3` next to the input and prints what changed to stderr:

```
Player: removed a loose stack of 3 blocks starting with looks_say
Player: folded 12 constant expressions
Player: decided 2 control blocks with a constant condition
Stage: removed the unused custom block "old level loader" (41 blocks)
1283 blocks before, 1221 after (4 changes)
wrote my-game-optimized.sb3 (412 KiB)
```

| Option | Effect |
| --- | --- |
| `-o`, `--output <file>` | Where to write the result. Defaults to `<name>-optimized.sb3`, or `<name>-optimized.json` for a bare `project.json`, next to the input. |
| `--no-fold` | Keep constant expressions and control blocks as they are. |
| `--no-remove-loose` | Keep stacks that cannot run. |
| `--no-remove-procedures` | Keep custom blocks that nothing calls. |
| `-q`, `--quiet` | Report only errors. |

For an `.sb3`, everything in the archive except `project.json` (costumes, sounds and any other files) goes into the output as it is, under the same path. The input can also be a bare `project.json`, which gives `<name>-optimized.json` back, holding only the optimized `project.json`.

With jScratch, `jscratch my-game.sb3 --optimize` runs the same passes on the project before compiling it and prints the same report, prefixed with `sb3o:`.

## What the passes do

Every pass has one rule: the project must behave exactly as it did, on every engine. Scratch makes that harder than it looks. Values carry a type that blocks can tell apart (`say` rounds a number to two decimals but shows text as it is), loops yield to other scripts between iterations, and a project can name a broadcast, a costume or a variable with a computed string. So each pass only touches what it can prove, and leaves the rest alone.

### Remove loose stacks

A top-level block that is not a hat only runs when someone clicks it in the editor, so a player never runs it. The pass removes such stacks, along with reporters dropped onto the workspace by themselves, and the comments attached to them. Blocks of unknown categories, such as another extension's, are left alone: one of them could be a hat the pass does not know.

### Fold constants

A reporter whose inputs are all literals is computed and replaced by its result. The operator reporters are the ones that get folded: arithmetic, `mod`, `round`, the `mathop` functions, `join`, `letter of`, `length of`, `contains`, the comparisons and `and`, `or` and `not`. The arithmetic is scratch-vm's own: the cast rules of its `Cast` class (what counts as a number, how text compares) and its operator code are mirrored in `ScratchValues`, down to `log` being `ln` divided by `ln 10` and `sin` rounded to ten decimals, and the number formatting is JavaScript's, worked out with exact arithmetic so it comes out the same on the JVM, in the browser and under TeaVM.

The `mathop` functions are the exception to folding whatever is constant, because the optimized project will run on engines other than the one that folded it, and their `Math` functions do not agree to the last bit: V8, SpiderMonkey, JavaScriptCore and the JVM can each give a different double for `e ^ 1`. So a function only folds where every engine gives the same answer:

- `abs`, `floor`, `ceiling` and `sqrt` always fold, since IEEE 754 defines them exactly.
- `sin`, `cos` and `tan` are rounded to ten decimals, which hides the last bits, so they fold unless the value sits close enough to a halfway point between two rounded results that some engine could round it the other way. That allows for a few ulps of error in the function, for engines that turn degrees into radians another way, and for the two roundings in use: scratch-vm's `toFixed(10)` and the `Math.round(x * 1e10) / 1e10` of TurboWarp and jScratch, which only differ at such halfway points. Angles beyond 100000 degrees never fold, since engines reduce those less consistently.
- `ln`, `log`, `e ^`, `10 ^`, `asin`, `acos` and `atan` never fold.

A result only replaces a reporter when the slot can hold it without any block seeing a difference:

- The slot has to have a literal shadow, the number or text box the reporter covers. A boolean slot or a menu has none, so nothing is written there.
- A number is written as a JSON number inside the shadow (`[10, 4]` rather than `[10, "4"]`), the encoding Scratch 2 imports use, so the block still reports a number. Writing text would change `say`, which rounds numbers, `pick random`, which treats whole numbers differently, and what a variable set from the slot holds. NaN, the infinities and negative zero have no JSON form that reads back the same, so they stay blocks. Number shadows with limits (positive, whole, integer, angle) only take values the editor's fields would show unchanged.
- Text goes into a text shadow as it is, never into a number shadow, which the editor would cast on load.
- A boolean never goes into a value slot. It only decides control blocks, and folds further through `not`, `and` and `or`.

The same pass decides control blocks whose condition is constant. `if` and `if else` run their chosen branch in place without yielding, so the branch is spliced into the script and the rest is removed. `repeat until` and `wait until` with a true condition, and `repeat` with a count that rounds to zero or less, end at once without yielding, so they are removed. A loop that never ends stays a loop, since removing it would change when the script yields.

### Remove unused procedures

A custom block is reachable when a script under a hat calls it, directly or through other reachable custom blocks; calls name their procedure by its `proccode` in the sprite they sit in. Definitions that are not reachable are removed, including one that only calls itself and one only called from a loose stack.

## Using the library

The library reads `project.json` into a model, runs passes over it and writes it back. Everything it does not interpret (comments, block positions, the `meta` block, an extension's own members, the mutation of a `stop` block) is kept as read and written back unchanged, so a project goes through unharmed even when no pass changes it.

=== "Kotlin or Java"

    ```kotlin
    import org.jscratch.sb3o.OptimizeOptions
    import org.jscratch.sb3o.SB3O
    import org.jscratch.sb3o.model.ProjectReader
    import org.jscratch.sb3o.model.ProjectWriter

    val result = SB3O.optimizeJson(projectJsonText)
    val optimizedText = result.projectJson
    for (message in result.report.messages) println(message)

    // Or on the model, to look at it or change it further:
    val project = ProjectReader.read(projectJsonText)
    val report = SB3O.optimize(project, OptimizeOptions(removeLooseStacks = false))
    val text = ProjectWriter.writeText(project)
    ```

    `./gradlew :sb3o:jvmJar` builds the JVM JAR in `sb3o/build/libs/`. On the JVM, `Archives` reads and writes the zip archives, which is what the `sb3o` command uses.

=== "JavaScript"

    ```js
    import { optimizeProjectJson, JsOptimizeOptions } from "sb3o";

    const result = optimizeProjectJson(projectJsonText);
    console.log(result.summary);
    for (const message of result.messages) console.log(message);
    const options = new JsOptimizeOptions(false, true, false); // removeLooseStacks, foldConstants, removeUnusedProcedures
    const onlyFolded = optimizeProjectJson(projectJsonText, options);
    ```

    `./gradlew :sb3o:jsNodeProductionLibraryDistribution` builds the package in `sb3o/build/dist/js/productionLibrary/`: `sb3o.mjs`, its declarations in `sb3o.d.mts` and a `package.json`. It is not on npm yet; see the [roadmap](roadmap.md#sb3o).

The model (`org.jscratch.sb3o.model`) is a flat list of targets, each with its variables, lists, broadcasts, costumes, sounds and a block table keyed by block id. Inputs keep the shadow a block covers, so a pass that removes the block can put the shadow back, and literals know whether the project stored them as text or as a JSON number. `ScratchValues` (`org.jscratch.sb3o.values`) is the mirror of scratch-vm's casting and comparison rules, and `JsNumbers` formats numbers the way JavaScript does on either platform.

## Adding a pass

A pass is an object implementing `Pass` in `org.jscratch.sb3o.passes`: a `name` and `run(project, report)`, which edits the model in place and adds a line to the report for everything it changes. `Blocks` has the graph operations the existing passes share: the chain under a block, the tree of reporters plugged into it, the whole stack under a hat, removing blocks with their comments, and splicing a chain into the place of a block. Add the pass to `SB3O.passes` with an option to turn it off, cover it in `sb3o/src/commonTest`, and describe what it does and why it is safe on this page.

Before writing a pass, work out what scratch-vm does for every block it touches, not what seems reasonable. The sources to read are `src/util/cast.js`, the block package in `src/blocks/` and `src/engine/execute.js`. A pass that is only right on jScratch is wrong.

## Tests

- `sb3o/src/commonTest` runs on the JVM and on Node, so the folder is checked against the JavaScript engine's own arithmetic and number formatting: `JsNumbersTest`, `ScratchValuesTest`, `JsonTest`, `ProjectRoundTripTest`, `FoldConstantsTest` and `RemovePassesTest`.
- `sb3o/src/jvmTest` reads, writes and reads every sample project again and checks that nothing was lost.
- `OptimizeSamplesTest` in `compiler-jvm/src/test` compiles every sample with and without `--optimize`, runs both headless and compares what the sprites said. `samples/src/optimize` is a project written for it, with an expression for every folder rule.
