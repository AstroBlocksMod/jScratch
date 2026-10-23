package org.jscratch.compiler.sb3

/**
 * In-memory form of a Scratch 3 project. The shape follows the parser in
 * ScratchEverywhere (`source/runtime/parser/sb3.cpp`): a flat list of targets,
 * each with variables, lists, broadcasts, costumes, sounds and a block table
 * keyed by block id.
 */
class Project(val targets: List<Target>, val monitors: List<Monitor>, val extensions: List<String>) {
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
)

class Sound(val name: String, val assetId: String, val md5ext: String, val dataFormat: String)

class Monitor(val id: String, val opcode: String, val spriteName: String?, val visible: Boolean)

class Block(
    val id: String,
    val opcode: String,
    val next: String?,
    val parent: String?,
    val inputs: LinkedHashMap<String, Input>,
    val fields: LinkedHashMap<String, Field>,
    val shadow: Boolean,
    val topLevel: Boolean,
    val mutation: Mutation?,
)

class Field(val value: String, val id: String?)

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
 * where the value is a block id or a primitive array.
 */
sealed class Input {
    /** The slot holds another block. */
    class BlockRef(val id: String) : Input()

    /** A literal typed into the slot. [type] is Scratch's primitive type (4 number, 10 string, ...). */
    class Literal(val type: Int, val value: String) : Input()

    class BroadcastRef(val name: String, val id: String) : Input()

    class VariableRef(val name: String, val id: String) : Input()

    class ListRef(val name: String, val id: String) : Input()

    object Empty : Input()
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
