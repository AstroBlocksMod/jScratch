# Roadmap

This page lists what is planned for jScratch, roughly in the order it will happen. It is a plan, not a promise: priorities move when real projects turn up problems. [Supported blocks](blocks.md) is always the source of truth for what works today.

## Done: the MVP

- [x] `.sb3` parsing and validation with the official scratch-parser, on the JVM and in the browser
- [x] ASM code generation with typed expressions, custom blocks and "run without screen refresh"
- [x] Virtual-thread scheduler that follows scratch-vm's frame and yield rules
- [x] Java2D rendering with vector costumes, the pen layer and say bubbles
- [x] Broadcasts, clones, lists, `sensing_of` and ask and wait
- [x] WAV playback
- [x] Headless mode and screenshots for automated tests
- [x] TeaVM playground and the documentation site on GitHub Pages

## Next: close the compatibility gaps

These are the gaps most likely to break a project downloaded from the Scratch website, so they come first.

- [ ] **Pixel-accurate collision.** Replace bounding boxes in `touching` with costume alpha masks, honoring rotation and size.
- [ ] **Color sensing.** `touching color` and `color is touching color`, built on the same masks plus a read of the rendered stage.
- [ ] **Graphic effects.** Color, brightness, fisheye, whirl, pixelate and mosaic, applied per sprite in the renderer the way scratch-render does.
- [ ] **Monitors.** Draw variable and list monitors, and make the show and hide blocks control them.
- [ ] **MP3 sounds.** Most projects saved by the editor use MP3, so this needs a maintained pure Java decoder bundled into the runtime.
- [ ] **Sound effects.** Pitch and pan, plus clear sound effects.
- [ ] **Remaining hats.** `when timer greater than` first, since it needs no microphone. Loudness follows if microphone input proves workable through `javax.sound.sampled`.
- [ ] **Small sensing blocks.** Drag mode for draggable sprites, and a `username` that can be set with a system property.

## Then: performance and robustness

- [ ] **Split large scripts.** Break scripts that exceed the JVM's 64 KiB method limit into several methods, so no valid project fails to compile.
- [ ] **Turbo mode and frame rate.** Command line options and system properties for turbo mode and a custom frame rate, like TurboWarp offers.
- [ ] **Better type inference.** Track variables that only ever hold numbers so they can live as primitive doubles instead of boxed objects.
- [ ] **Benchmarks.** A small suite of CPU-heavy projects, timed against scratch-vm and TurboWarp, run in CI to catch regressions.
- [ ] **Compatibility corpus.** A set of real projects compiled and run headless on every build, with screenshots compared against reference frames.

## Extensions

- [ ] **Makey Makey.** Its hats map onto key presses, so it is cheap to add.
- [ ] **Music.** Instruments and drums through `javax.sound.midi`, which ships with the JDK.
- [ ] **Text to Speech and Translate.** These depend on online services. They will only be added if there is a way to do it without sending project data somewhere the user did not expect.
- Video Sensing and hardware extensions are out of scope for now.

## Playground

- [ ] **Faster compiles.** Cache the unpacked runtime JAR between compiles instead of re-zipping it every time.
- [ ] **Load by project ID.** Paste a Scratch project link instead of downloading the `.sb3` first.
- [ ] **Clearer output.** Show warnings next to the block that caused them and group the bytecode listing by sprite.

## Distribution

- [ ] **Release builds.** Publish the CLI JAR on GitHub Releases for every tagged version.
- [ ] **Native launchers.** An option to package a compiled project with `jpackage`, so players do not need a Java install.
- [ ] **Gradle plugin.** Compile `.sb3` files as part of another project's build.

## Later: major goals

These are bigger projects that build on everything above. They will take a while, and their shape will change as the earlier work settles.

### Two-way interop with the JVM

Compiled projects are already JVM classes, so the goal is to let them work with Java, Kotlin and other JVM languages in both directions.

- [ ] **Calling Java from Scratch.** Extension blocks that call into JVM libraries, with the compiler emitting direct method calls instead of going through a lookup at run time.
- [ ] **Calling Scratch from Java.** Use a compiled project as a library: start it, send broadcasts, read and set variables and lists, and listen for events from ordinary JVM code.
- [ ] **Library extensions.** Packages of blocks that wrap a specific library with an interface that feels like Scratch, instead of exposing its raw API. Each extension describes its blocks and how they compile.
- [ ] **Embeddable runtime.** Let the host decide how the project runs: its own tick in place of the 30 fps scheduler, its own drawing surface in place of the Swing window, or no stage at all.

With these in place, a project could become a Minecraft mod through blocks that bind to Fabric, or a desktop app with a JavaFX interface.

### Desktop IDE

- [ ] **A full desktop editor.** Edit blocks, costumes and sounds, then compile and run the project in one click without leaving the editor.
- [ ] **Debugging.** Breakpoints on blocks, stepping through scripts, and a live view of variables, lists and running scripts.
- [ ] **Extension support.** Browse and add library extensions, and see their blocks in the palette like any built-in category.

## Have a request?

Open an issue on [GitHub](https://github.com/AstroBlocksMod/jScratch/issues) with the project that fails, or the block you need. A project that reproduces the problem moves an item up the list faster than anything else.
