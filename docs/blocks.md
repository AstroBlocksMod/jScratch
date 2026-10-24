# Supported blocks

The compiler handles the blocks below. Anything else compiles to a no-op with a warning at compile time and a one-time message at run time, so a project with an unsupported block still runs.

## Hats

| Block | Notes |
| --- | --- |
| when green flag clicked | Also runs on launch. |
| when key pressed | Fires on key down, repeats with the OS key repeat. "any" works. |
| when this sprite clicked, when stage clicked | A click hits the topmost sprite with an opaque pixel under the mouse. |
| when I receive | Case-insensitive match like Scratch. |
| when backdrop switches to | Fires on every switch, even to the same backdrop. |
| when I start as a clone | |
| define (custom blocks) | Arguments, defaults and "run without screen refresh" are honored. |
| when loudness or timer greater than | Not supported. The script never runs. |

## Motion

All motion blocks: move, turn, go to (xy, random, mouse, sprite), glide (both forms), point in direction, point towards, change and set x and y, if on edge bounce, set rotation style, and the x position, y position and direction reporters. Sprites are fenced to the stage as in Scratch.

## Looks

say, say for secs, think, think for secs, show, hide, switch costume, next costume, switch backdrop, switch backdrop and wait, next backdrop, change and set size, go to front or back, go forward or backward layers, costume number or name, backdrop number or name, size.

Graphic effects: only **ghost** is implemented. Other effects (color, fisheye, whirl, pixelate, mosaic, brightness) are accepted but have no visible result.

## Sound

play sound, play sound until done, stop all sounds, set and change volume, volume. MP3 and WAV sounds play, including the ADPCM WAV files common in projects that started in Scratch 2. Sounds up to 10 seconds long are decoded when the project starts, and longer ones while they play. The timer restarts with every green flag, as in Scratch. Sound effects (pitch, pan) are ignored.

## Events

broadcast, broadcast and wait. Broadcasts restart scripts that are already running, as in Scratch.

## Control

wait, repeat, forever, if, if else, wait until, repeat until, while, stop (all, this script, other scripts in sprite), create clone of, delete this clone. Clones are capped at 300.

## Sensing

touching (mouse, edge, sprite), distance to, ask and wait with answer, key pressed, mouse down, mouse x and y, timer, reset timer, "of" (sprite and stage properties and variables), current date and time, days since 2000.

Touching a sprite or the mouse is pixel-accurate: it compares the opaque pixels of the costumes as they are drawn, with rotation and size applied, the way scratch-render does. Ghost does not change what a sprite touches. Touching the edge, if on edge bounce and fencing use tight bounds, the box around the costume's opaque pixels, so transparent padding in a costume does not count.

Not supported: touching color, color is touching color (always false), loudness (reports -1), username (reports an empty string), video sensing, drag mode (ignored).

## Operators

Every operator block: arithmetic, pick random, comparisons, and, or, not, join, letter of, length, contains, mod, round, and all the math functions. Comparisons follow Scratch: numeric when both sides look like numbers, otherwise a case-insensitive string comparison.

## Variables and lists

set, change, show and hide (monitors are not drawn), all list blocks: add, delete, delete all, insert, replace, item, item number, length, contains, and the list reporter. Cloud variables behave as ordinary variables.

## Pen

clear, stamp, pen down, pen up, set pen color to color, set and change color parameter (color, saturation, brightness, transparency), set and change pen size, plus the legacy hue and shade blocks.

## Extensions

Music, Text to Speech, Translate, Video Sensing, Makey Makey and hardware extensions are not supported.

## Known limitations

- Variable monitors and list monitors are not drawn.
- A single script longer than the JVM's 64 KiB method limit will fail to compile. Very large lists are stored as resources, so they are fine.
- Turbo mode and custom frame rates are not available.
