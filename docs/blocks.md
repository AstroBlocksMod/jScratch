# Supported blocks

The compiler handles the blocks below. Anything else compiles to a no-op with a warning at compile time and a one-time message at run time, so a project with an unsupported block still runs.

## Hats

| Block | Notes |
| --- | --- |
| when green flag clicked | Also runs on launch. |
| when key pressed | Fires on key down, repeats with the OS key repeat. "any" works. |
| when this sprite clicked, when stage clicked | A click hits the topmost sprite with an opaque pixel under the mouse. Sprites with a ghost effect of 100 do not catch clicks. |
| when I receive | Case-insensitive match like Scratch. |
| when backdrop switches to | Fires on every switch, even to the same backdrop. |
| when I start as a clone | |
| define (custom blocks) | Arguments, defaults and "run without screen refresh" are honored. |
| when loudness or timer greater than | Not supported. The script never runs. |

## Motion

All motion blocks: move, turn, go to (xy, random, mouse, sprite), glide (both forms), point in direction, point towards, change and set x and y, if on edge bounce, set rotation style, and the x position, y position and direction reporters. Sprites are fenced to the stage as in Scratch.

## Looks

say, say for secs, think, think for secs, show, hide, switch costume, next costume, switch backdrop, switch backdrop and wait, next backdrop, change and set size, go to front or back, go forward or backward layers, costume number or name, backdrop number or name, size.

Graphic effects: set effect to, change effect by and clear graphic effects, for all seven effects (color, fisheye, whirl, pixelate, mosaic, brightness, ghost), on sprites and on the stage. They use scratch-render's shader math, so a value looks the same as in Scratch: color 200 goes once around the color wheel, mosaic 10 makes two copies across, pixelate blocks are a tenth of the value in stage units. Ghost is clamped to 0 through 100 and brightness to -100 through 100, as in Scratch. Stamps carry the sprite's effects. Clones start with their parent's effects, and the green flag and stop clear every effect.

## Sound

play sound, play sound until done, stop all sounds, set and change volume, volume. MP3 and WAV sounds play, including the ADPCM WAV files common in projects that started in Scratch 2. Sounds up to 10 seconds long are decoded when the project starts, and longer ones while they play. The timer restarts with every green flag, as in Scratch.

As in Scratch 3, a sound never plays twice at once within a sprite: playing a sound that is still playing starts it over, also when a clone of the sprite plays it, and "play sound until done" waiting on it stops waiting. Playing a sound again in the same frame from the same sprite or clone leaves it playing and only ends those waits. Stopped and restarted sounds fade out over 25 ms. Stop other scripts in sprite and deleting a clone stop the sounds that sprite or clone started with "play sound until done" and that no other clone has started since, the way scratch-vm does.

Sound effects: set effect to, change effect by and clear sound effects, for pitch and pan, with the math of scratch-audio. Pitch goes from -360 to 360, and 10 is one semitone, so 120 plays an octave higher and twice as fast. Pan goes from -100 (left) to 100 (right). A changed volume or effect also applies to the sounds the sprite is already playing, and "play sound until done" waits longer or shorter with the pitch, even when it changes in the middle of the sound. Stop all sounds ends "play sound until done". Clones start with their parent's volume and effects, and the green flag and stop clear the effects. As in Scratch, set and change volume and set and change effect wait for the next frame, even inside a custom block that runs without screen refresh.

## Events

broadcast, broadcast and wait. Broadcasts restart scripts that are already running, as in Scratch.

## Control

wait, repeat, forever, if, if else, wait until, repeat until, while, stop (all, this script, other scripts in sprite), create clone of, delete this clone. Clones are capped at 300.

## Sensing

touching (mouse, edge, sprite), touching color, color is touching color, distance to, ask and wait with answer, key pressed, mouse down, mouse x and y, timer, reset timer, "of" (sprite and stage properties and variables), current date and time, days since 2000.

Touching a sprite or the mouse is pixel-accurate: it compares the opaque pixels of the costumes as they are drawn, with rotation, size and the effects that move pixels (fisheye, whirl, pixelate, mosaic) applied, the way scratch-render does. The mouse counts as the whole stage pixel under it. Ghost and the other color effects do not change what a sprite touches. Touching the edge, if on edge bounce and fencing use tight bounds, the box around the costume's opaque pixels as the effects draw them, so transparent padding in a costume does not count. A costume with no opaque pixels at all touches nothing, not even the edge, and is not fenced.

Touching color looks at what the stage shows under every opaque pixel of the sprite: the other sprites in front to back order (with all their effects), the pen layer, the backdrop (with the stage's effects) and the white stage behind it. Color is touching color only looks under the sprite's pixels that have the first color after its own color and brightness effects, ignoring its ghost effect. Colors match loosely, like in Scratch: the top 5 bits of red and green and the top 4 bits of blue have to agree, and the first color of color is touching color needs the top 6 bits of each channel. Both blocks work while the sprite is hidden, as in Scratch 3.

Not supported: loudness (reports -1), username (reports an empty string), video sensing, drag mode (ignored).

## Operators

Every operator block: arithmetic, pick random, comparisons, and, or, not, join, letter of, length, contains, mod, round, and all the math functions. Comparisons follow Scratch: numeric when both sides look like numbers, otherwise a case-insensitive string comparison.

## Variables and lists

set, change, show and hide variable, all list blocks: add, delete, delete all, insert, replace, item, item number, length, contains, show and hide list, and the list reporter. Cloud variables behave as ordinary variables.

## Monitors

Monitors are drawn the way the Scratch player draws them, with scratch-gui's sizes, colors and labels, on top of the sprites and say bubbles.

- **Variables** show as a normal readout, a large readout or a slider, whichever the project saved. Dragging a slider's bar sets the variable, snapping to whole numbers or to hundredths like in Scratch.
- **Lists** show their items with index numbers and the length, at the size saved in the project. The mouse wheel scrolls a long list.
- **Built-in reporters** with a checkbox in the editor can be monitored too: x position, y position, direction, costume and backdrop number or name, size, volume, answer, timer, current date and time, username and loudness.
- Numbers are rounded to six decimal places for display, as in Scratch.
- show and hide variable and show and hide list work on every variable and list, including ones that never had a monitor and ones a block uses without declaring them. A monitor shown for the first time is placed where scratch-gui would place it, in the first free spot from the top left.
- Clicks on a monitor do not reach the project, so they do not trigger `when stage clicked` or `mouse down?`.

Not supported: dragging monitors, their right-click menu, editing or resizing lists in a list monitor, and monitors of extension blocks such as the Music tempo. A value too wide or too tall for the stage is cut off at the edge instead of wrapping.

## Pen

clear, stamp, pen down, pen up, set pen color to color, set and change color parameter (color, saturation, brightness, transparency), set and change pen size, plus the legacy hue and shade blocks.

## Extensions

Music, Text to Speech, Translate, Video Sensing, Makey Makey and hardware extensions are not supported.

## Known limitations

- A single script longer than the JVM's 64 KiB method limit will fail to compile. Very large lists are stored as resources, so they are fine.
- Turbo mode and custom frame rates are not available.
