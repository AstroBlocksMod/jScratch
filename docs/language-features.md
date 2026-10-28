# Language features

This page sketches the [advanced language features](roadmap.md#advanced-language-features) on the roadmap: functions, macros, objects, lambdas, maps, variables declared in code and broadcasts that carry data. None of it exists yet. The pictures are mockups drawn with [mockblocks](https://github.com/AstroBlocksMod/mockblocks), and the block names, shapes and colors are a draft that will change once the work starts.

The one rule that will not change: jScratch stays a strict superset of Scratch. Every block Scratch has keeps its meaning, every Scratch project compiles and behaves the same way it does today, and the new blocks only matter in a project that uses them.

## Functions

Custom blocks can only be stack blocks today, and every variable lives on the sprite or the stage. The usual way to get a value out of a custom block is to write it into a variable and read it afterwards:

![A distance custom block that stores its result in a global variable called result](assets/mockups/functions-today.svg)

That works until two scripts call the block at the same time, or the block calls itself, and then they overwrite each other's `dx`, `dy` and `result`.

### Reporters and booleans

A custom block whose prototype is round reports a value, and one whose prototype is pointed reports true or false. `return` ends the block and hands the value back, the same way `stop this script` ends it without one. The call is an ordinary pink reporter that fits anywhere a value goes.

=== "With functions"

    ![The distance custom block written as a reporter that returns the result](assets/mockups/functions-reporter.svg)

=== "Scratch today"

    ![The same block storing its result in a global variable](assets/mockups/functions-today.svg)

Boolean blocks fit into `if`, `wait until`, `repeat until` and the other pointed slots:

![A boolean custom block that checks whether a number lies between two others, used to keep a sprite on the stage](assets/mockups/functions-predicate.svg)

### Local variables

`local` makes a variable that only exists until the script or custom block that created it ends. Each call gets its own copy, so two calls running at once never see each other's values. The usual `set`, `change` and variable reporter work on it, and inside its script a local hides a sprite or stage variable with the same name.

![A custom block that counts the vowels in a text with two local variables](assets/mockups/functions-locals.svg)

A local declared in a script, outside any custom block, belongs to that run of the script. A clone that runs the script gets its own.

### Recursion

With return values and locals, a custom block can call itself without a list acting as a manual stack:

![A recursive Fibonacci custom block](assets/mockups/functions-recursion.svg)

Locals also make recursive drawing straightforward. Each call remembers where it started and goes back there once its branches are drawn:

![A recursive custom block that draws a tree with the pen, saving its position and direction in local variables](assets/mockups/functions-tree.svg)

The plan is to compile calls that run without screen refresh to plain JVM method calls, so deep recursion would be as fast as it is in Java. There will still be a depth limit, and a project that goes past it should stop with an error instead of crashing.

## Macros

A macro looks like a custom block, but the compiler replaces every use of it with the blocks in its definition before generating any code. Nothing is called at run time, so a macro costs exactly as much as writing its blocks out by hand.

Macro blocks and their parameters are purple. A parameter stands for whatever was plugged into that slot, which can be a single value, a whole expression, a variable, a list or a stack of blocks.

### Constants

The simplest macro has no parameters and expands to a value. It gives a name to a number that would otherwise be repeated across the project, without the cost of reading a variable:

![Two constant macros, tile size and columns, used in a custom block that places a tile](assets/mockups/macros-constant.svg)

### Expressions

A reporter macro expands to the expression after `expand to`, with its parameters filled in:

=== "You write"

    ![A lerp macro and a script that uses it to follow the mouse](assets/mockups/macros-expression.svg)

=== "The compiler sees"

    ![The same script with the lerp expression written out in place](assets/mockups/macros-expression-expanded.svg)

A parameter is pasted in as a whole expression, so `lerp from (x position) ...` reads `x position` twice, once for each place `a` appears. That matters for reporters like `pick random`, which give a different value each time.

### New control blocks

A macro can take a stack of blocks as a parameter and becomes a C block. This is how a project can add control structures Scratch does not have. Here is a `for each` loop over a list:

![A for each macro whose definition loops over a list with a local index](assets/mockups/macros-for-each.svg)

=== "You write"

    ![A script that uses for each to check every enemy](assets/mockups/macros-for-each-use.svg)

=== "The compiler sees"

    ![The same script with the loop written out](assets/mockups/macros-for-each-expanded.svg)

Locals that a macro declares are renamed when it expands, so the `index` in the definition can never clash with a variable called `index` at the place the macro is used.

### Compile-time choices

The `at compile time` if is only available in macros. The compiler decides which way it goes and leaves the other branch out of the JAR completely. Turning a debug macro off removes every trace of the logging from the compiled project:

![A debug log macro that only adds its block when the debug mode macro is on](assets/mockups/macros-debug.svg)

## Objects

Scratch has two ways to have many of something. Clones are sprites, so each one costs a sprite's worth of memory and drawing, even for something as small as a particle. Parallel lists are cheap, but keeping them in step by hand gets out of hand quickly:

![A particle effect written with four parallel lists, one per property](assets/mockups/objects-today.svg)

### Classes

A class describes a kind of object: the fields every instance has, with their starting values, and the methods that work on them. A method is a custom block that belongs to the class. Inside it, `this` is the object the method was called on.

![A Particle class with five fields and update, draw and is old methods](assets/mockups/objects-class.svg)

Fields are initialized every time an instance is made, so `mouse x` is read when the particle is created, not once when the project starts.

### Instances

`new` makes an instance. Instances are values like numbers and text: they can be stored in variables and lists, passed into custom blocks and returned from them. The same effect as above, written with the class and the `for each` macro:

![The particle effect written with the Particle class and a list of instances](assets/mockups/objects-use.svg)

`call` runs a method on an instance. A method with arguments takes them after `with`, and a method that returns a value can be used as a reporter or a boolean:

![An Enemy class with a take damage method that takes an amount and a describe method that returns text](assets/mockups/objects-arguments.svg)

### Objects as values

Two variables can hold the same instance, so changing a field through one is visible through the other. `is a` checks what class a value belongs to, which is useful when a list holds different kinds of objects:

![A custom block that returns the stronger of two enemies, and a script that renames it](assets/mockups/objects-values.svg)

Objects mix freely with sprites and clones. A clone can create an object, keep it in a "for this sprite only" variable and share it through a global list, so other sprites can find it:

![A clone that creates an Enemy object, adds it to a list and deletes itself when its health runs out](assets/mockups/objects-sprites.svg)

## Lambdas

A lambda is a function without a name, made right where it is needed. `function` wraps a reporter, a boolean or a stack block and reports the function itself instead of running it. Its inputs go after the word `function` and work like the arguments of a custom block. Functions are values, so they can be kept in variables, lists and fields, passed into custom blocks and returned from them.

`call` runs a function and reports what it returns. A function that takes inputs gets them after `with`:

![Two lambdas kept in variables, double and shout, called one inside the other](assets/mockups/lambdas-basics.svg)

### Working on lists

Lambdas make it possible to have list blocks that ask a question about every item. `keep items of` removes every item the function answers false for, and `sort by` orders the list by what the function reports for each item:

![A script that removes defeated enemies from a list, sorts the rest by health and targets the weakest](assets/mockups/lambdas-lists.svg)

### Capturing values

A lambda can use the arguments and locals of the custom block or script it was made in, and keeps them after that block has returned. Here each function remembers its own `factor`:

![A custom block that returns a function multiplying its input by a factor, used to make double and triple](assets/mockups/lambdas-closure.svg)

### Running code later

A function made from stack blocks runs with `run`, or can be handed to a block that runs it later. `after seconds run` starts the function as a separate script once the time is up, while the script that set it up carries on straight away:

![A script that sets a ghost effect, schedules clearing it after two seconds and keeps going with a broadcast](assets/mockups/lambdas-run.svg)

The function runs in the sprite or clone that made it. If that clone is deleted first, the function never runs.

## Maps

A list finds things by position. A map finds them by name: it holds pairs of a key and a value, and looking up a key is just as fast with a thousand pairs as with three. Doing that in Scratch today takes two lists kept in step, one for the names and one for the values:

=== "With maps"

    ![A script that counts a picked up item in an inventory map with one block](assets/mockups/maps-inventory.svg)

=== "Scratch today"

    ![The same inventory written with two lists, one for item names and one for counts](assets/mockups/maps-today.svg)

`change key` treats a missing key as 0, the same way `change by` treats an empty variable, so the first pickup needs no special case.

Like objects, maps are values. `empty map` makes a new one, and it can be kept in a variable, a list, a field or another map. Keys are text, and values can be anything a variable can hold:

![A shop that sets up prices in a map and sells an item if the player has enough coins](assets/mockups/maps-blocks.svg)

`for each key` runs its body once for every key, in the order the keys were added:

![A script that lists every item in the inventory and how many of it there are](assets/mockups/maps-for-each.svg)

An object has a fixed set of fields that the class decides. A map is the better fit when the keys are only known while the project runs, like item names, player names or the cells of a grid.

## Variables in code

New variables and lists are made through the "Make a Variable" dialog today, and every one of them starts out empty or 0. A `declare` block lists them in the code area instead, each with its scope and starting value. Declared variables show up in the palette, in the variable dropdowns and as monitors like any other, so the rest of the project uses them the usual way:

![A declare block with score, lives, a per-sprite speed and a high scores list, and a script that moves at that speed](assets/mockups/variables-declare.svg)

On the stage, `for` only offers "all sprites". The starting values are set when the project starts, before any green flag script runs, so no script ever sees a variable that has not been set up yet. A "this sprite only" variable gets its own starting value in each sprite, and a clone copies its parent's value as it does today.

The starting value can be any reporter, which is the natural place to set up the values from the sections above:

![Declared variables that start out as an empty map, a new Enemy object and a lambda](assets/mockups/variables-values.svg)

Because the declarations are blocks, they can be copied between sprites and projects with the code that uses them, and a macro can declare the variables it needs.

## Broadcasts with data

A broadcast can only say that something happened. Anything else the receivers need has to go through a global variable, which breaks as soon as two broadcasts are sent in the same frame:

=== "With data"

    ![A sprite that broadcasts score points with 10, and a receiver that adds the points it was sent to the score](assets/mockups/broadcasts-data.svg)

=== "Scratch today"

    ![The same thing with a global variable set just before the broadcast](assets/mockups/broadcasts-today.svg)

`broadcast with` and `broadcast with and wait` send one value along. `when I receive with` gets an events-colored reporter, which holds the value inside that script the way an argument does in a custom block. Every run of the script has its own copy, so two broadcasts in one frame each deliver their own value. To send more than one value, send a map or an object.

The old blocks keep working with the new ones. A plain `when I receive` also runs for a broadcast that carries data and ignores the value, and a plain `broadcast` delivers an empty value to receivers that expect one.

### Clones

Clones have the same problem: a new clone starts with a copy of its parent's variables, so the parent has to set them just before `create clone` and hope nothing changes them in between. `create clone with` hands a value to the new clone, and `when I start as a clone with` receives it:

![A wave script that creates clones at random x positions, and a clone script that moves to the x position it was given](assets/mockups/broadcasts-clones.svg)

## Open questions

These are the parts the design does not answer yet:

- **Objects in Scratch blocks.** What `say`, `join` or `=` should do when given an object. Showing the class name is the likely answer for text, and comparing identity for `=`.
- **Constructors.** Whether a class gets a method that runs on `new` with arguments, or whether field defaults are enough.
- **Inheritance.** Whether a class can extend another one, and whether sprites themselves should become classes that clones are instances of.
- **Lists as values.** Lambdas, maps and objects are values, but lists are still named containers. `keep items of` and `sort by` change a list in place for that reason. Making lists values too would allow blocks that report a new list instead, and keys of a map as a list.
- **Map keys.** Scratch compares text without caring about case, so `apple` and `Apple` are equal with `=`. Whether map keys follow that rule or tell the two apart.
- **Resetting declared variables.** Whether declared variables go back to their starting values each time the green flag is clicked, or keep their values like variables do today.
- **Replies to broadcasts.** Whether `broadcast and wait` should be able to report values that the receivers send back.
- **Saving projects.** How the new blocks are stored in a project file.
