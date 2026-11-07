package org.jscratch.sb3o.model

import org.jscratch.sb3o.json.JsonValue

/**
 * In-memory form of a Scratch 3 project. The shape follows the parser in
 * ScratchEverywhere (`source/runtime/parser/sb3.cpp`): a flat list of targets,
 * each with variables, lists, broadcasts, costumes, sounds and a block table
 * keyed by block id.
 *
 * The model is also what the optimizer rewrites and what [ProjectWriter] turns back into
 * `project.json`, so it keeps what it does not interpret: every object has an [extra] map of
 * the JSON members it does not model (`meta` on the project, `comments` and `tempo` on a target,
 * `x`, `y` and `comment` on a block, `rate` on a sound), and inputs and fields remember the
 * exact JSON they were read from until a pass replaces them.
 */
class Project(
    val targets: MutableList<Target>,
    val monitors: MutableList<Monitor>,
    val extensions: MutableList<String>,
    /** Members of `project.json` the model does not interpret, such as `meta`. */
    val extra: LinkedHashMap<String, JsonValue>,
) {
    val stage: Target get() = targets.first { it.isStage }
    val sprites: List<Target> get() = targets.filter { !it.isStage }
}

class Target(
    val name: String,
    val isStage: Boolean,
    val variables: LinkedHashMap<String, Variable>,
    val lists: LinkedHashMap<String, ScratchListDef>,
    val broadcasts: LinkedHashMap<String, String>,
    val blocks: LinkedHashMap<String, Block>,
    /**
     * Entries of `blocks` that are not blocks: a variable or list reporter dragged onto the
     * workspace by itself is stored as a bare array (`[12, name, id, x, y]`). Kept as read, since
     * nothing runs them.
     */
    val looseReporters: LinkedHashMap<String, JsonValue>,
    /** Workspace comments by id, as read. A comment attached to a block names it in `blockId`. */
    val comments: LinkedHashMap<String, JsonValue>,
    val costumes: List<Costume>,
    val sounds: List<Sound>,
    val currentCostume: Int,
    val volume: Double,
    val layerOrder: Int,
    val visible: Boolean,
    val x: Double,
    val y: Double,
    val size: Double,
    val direction: Double,
    val draggable: Boolean,
    val rotationStyle: String,
    /** Members of the target the model does not interpret, such as the stage's `tempo` and `videoState`. */
    val extra: LinkedHashMap<String, JsonValue>,
) {
    /** Blocks with `topLevel == true`: hats, procedure definitions and loose stacks. */
    val topLevelBlocks: List<Block> get() = blocks.values.filter { it.topLevel }
}

class Variable(val id: String, val name: String, val value: Any, val isCloud: Boolean)

class ScratchListDef(val id: String, val name: String, val items: List<Any>)

class Costume(
    val name: String,
    val assetId: String,
    val md5ext: String,
    val dataFormat: String,
    val rotationCenterX: Double,
    val rotationCenterY: Double,
    val bitmapResolution: Double,
    val extra: LinkedHashMap<String, JsonValue>,
)

class Sound(
    val name: String,
    val assetId: String,
    val md5ext: String,
    val dataFormat: String,
    /** `format`, `rate` and `sampleCount`, which only the editor reads. */
    val extra: LinkedHashMap<String, JsonValue>,
)

/**
 * A monitor record from `project.json`. [x] and [y] are the top-left corner in stage pixels, or NaN when
 * the editor never placed the monitor. [width] and [height] only matter for list monitors, where 0 means
 * the default size.
 */
class Monitor(
    val id: String,
    val opcode: String,
    val mode: String,
    val params: Map<String, String>,
    val spriteName: String?,
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
    val visible: Boolean,
    val sliderMin: Double,
    val sliderMax: Double,
    val isDiscrete: Boolean,
    /** The saved `value` and anything else the model does not interpret. */
    val extra: LinkedHashMap<String, JsonValue>,
)

class Block(
    val id: String,
    val opcode: String,
    var next: String?,
    var parent: String?,
    val inputs: LinkedHashMap<String, Input>,
    val fields: LinkedHashMap<String, Field>,
    val shadow: Boolean,
    var topLevel: Boolean,
    /** The procedure metadata of `procedures_prototype` and `procedures_call`, or null for any other mutation. */
    val mutation: Mutation?,
    /** The `mutation` member as read, for any block that has one; written back unchanged. */
    val mutationJson: JsonValue?,
    /** `x`, `y`, `comment` and anything else the model does not interpret. */
    val extra: LinkedHashMap<String, JsonValue>,
) {
    /** The id of the workspace comment attached to this block, if any. */
    val commentId: String? get() = extra["comment"]?.asString
}

/**
 * A field. [raw] is the JSON the field was read from (`[value, id]`), kept so the writer can put
 * back exactly what it read; null for a field made by a pass.
 */
class Field(val value: String, val id: String?, val raw: JsonValue?)

/** Procedure metadata carried by `procedures_prototype` and `procedures_call`. */
class Mutation(
    val proccode: String,
    val argumentIds: List<String>,
    val argumentNames: List<String>,
    val argumentDefaults: List<String>,
    val warp: Boolean,
)

/**
 * An input slot. Scratch encodes inputs as `[shadowType, value, shadowValue?]`
 * where the value is a block id or a primitive array. When a block covers a slot that has its
 * own shadow (a literal or a menu), the shadow stays in the project as the third element and is
 * kept here as [shadow], so a pass that removes the block can put the shadow back in its place.
 *
 * [raw] is the JSON the input was read from, written back unchanged until a pass replaces the
 * input; it is null for an input made by a pass, which the writer encodes from the model.
 */
sealed class Input {
    abstract val raw: JsonValue?

    /** The obscured shadow, for a slot covered by a block: a [Literal], a reference, or a [BlockRef] to a menu. */
    open val shadow: Input? get() = null

    /** The slot holds another block. */
    class BlockRef(val id: String, override val shadow: Input? = null, override val raw: JsonValue? = null) : Input()

    /**
     * A literal typed into the slot. [type] is Scratch's primitive type (4 number, 10 string, ...). [value]
     * is its text. [numberValue] is set when the project stores the literal as a JSON number instead of
     * text, which Scratch 2 imports and the optimizer do: the block then reports a number, not a string.
     */
    class Literal(
        val type: Int,
        val value: String,
        val numberValue: Double? = null,
        override val raw: JsonValue? = null,
    ) : Input()

    class BroadcastRef(
        val name: String,
        val id: String,
        override val shadow: Input? = null,
        override val raw: JsonValue? = null,
    ) : Input()

    class VariableRef(
        val name: String,
        val id: String,
        override val shadow: Input? = null,
        override val raw: JsonValue? = null,
    ) : Input()

    class ListRef(
        val name: String,
        val id: String,
        override val shadow: Input? = null,
        override val raw: JsonValue? = null,
    ) : Input()

    /** A slot that holds nothing, such as an unused boolean slot or substack. */
    class Empty(override val raw: JsonValue? = null) : Input()
}

object PrimitiveType {
    const val NUMBER = 4
    const val POSITIVE_NUMBER = 5
    const val WHOLE_NUMBER = 6
    const val INTEGER = 7
    const val ANGLE = 8
    const val COLOR = 9
    const val TEXT = 10
    const val BROADCAST = 11
    const val VARIABLE = 12
    const val LIST = 13
}

/** Scratch's input encoding: the first element of an input array. */
object InputShadow {
    /** The slot holds only its shadow (a literal, a menu or nothing). */
    const val SAME_BLOCK = 1

    /** The slot holds a block and has no shadow: a boolean slot or a substack. */
    const val NO_SHADOW = 2

    /** The slot holds a block that covers its shadow. */
    const val OBSCURED = 3
}
