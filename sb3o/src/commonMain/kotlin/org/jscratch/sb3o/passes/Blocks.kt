package org.jscratch.sb3o.passes

import org.jscratch.sb3o.model.Block
import org.jscratch.sb3o.model.Input
import org.jscratch.sb3o.model.Target

/** Walking and editing the block graph of a target, shared by the passes. */
internal object Blocks {
    /** Hats of the core blocks and the extensions that have them. Scripts under these can run. */
    val HATS =
        setOf(
            "event_whenflagclicked", "event_whenkeypressed", "event_whenthisspriteclicked", "event_whenstageclicked",
            "event_whentouchingobject", "event_whenbackdropswitchesto", "event_whengreaterthan",
            "event_whenbroadcastreceived", "control_start_as_clone", "procedures_definition",
        )

    /** Opcode prefixes of the categories whose blocks are known: a top-level block of one that is not a hat never runs. */
    private val KNOWN_CATEGORIES =
        listOf(
            "motion_", "looks_", "sound_", "event_", "control_", "sensing_", "operator_", "data_", "procedures_",
            "argument_", "pen_", "music_", "text2speech_", "translate_",
        )

    /**
     * Whether a block with this opcode starts a script. Unknown opcodes that look like hats (an
     * extension's `when` blocks) count as hats, so their scripts are left alone.
     */
    fun isHat(opcode: String): Boolean =
        opcode in HATS || opcode.contains("when", ignoreCase = true) || opcode.endsWith("_definition")

    fun isKnownCategory(opcode: String): Boolean = KNOWN_CATEGORIES.any { opcode.startsWith(it) }

    /** The blocks of the chain starting at [first], following `next`. */
    fun chain(target: Target, first: Block): List<Block> {
        val out = ArrayList<Block>()
        var b: Block? = first
        val seen = HashSet<String>()
        while (b != null && seen.add(b.id)) {
            out.add(b)
            b = b.next?.let { target.blocks[it] }
        }
        return out
    }

    /** Every block of the tree under [root]: the block, the blocks in its inputs and their shadows, recursively. */
    fun tree(target: Target, root: Block): List<Block> {
        val out = ArrayList<Block>()
        collectTree(target, root, out, HashSet())
        return out
    }

    private fun collectTree(target: Target, block: Block, out: MutableList<Block>, seen: MutableSet<String>) {
        if (!seen.add(block.id)) return
        out.add(block)
        for (input in block.inputs.values) {
            for (id in blockIds(input)) {
                val child = target.blocks[id] ?: continue
                collectTree(target, child, out, seen)
                // A substack is a chain of blocks, each the parent of the next.
                var next = child.next?.let { target.blocks[it] }
                while (next != null && next.id !in seen) {
                    collectTree(target, next, out, seen)
                    next = next.next?.let { target.blocks[it] }
                }
            }
        }
    }

    /** The whole stack under a top-level block: every block of every chain and input below it. */
    fun stack(target: Target, top: Block): List<Block> {
        val out = ArrayList<Block>()
        val seen = HashSet<String>()
        for (b in chain(target, top)) collectTree(target, b, out, seen)
        return out
    }

    /** The block ids an input refers to: the block in the slot and a menu shadow it covers. */
    fun blockIds(input: Input): List<String> {
        val ids = ArrayList<String>(2)
        if (input is Input.BlockRef) ids.add(input.id)
        val shadow = input.shadow
        if (shadow is Input.BlockRef) ids.add(shadow.id)
        return ids
    }

    /** Removes [blocks] from the target along with the comments attached to them. */
    fun remove(target: Target, blocks: List<Block>) {
        for (b in blocks) {
            target.blocks.remove(b.id)
            b.commentId?.let { target.comments.remove(it) }
        }
    }

    /** Removes the tree under [root] (a reporter and everything plugged into it). */
    fun removeTree(target: Target, root: Block) = remove(target, tree(target, root))

    /** Removes the whole stack under a top-level block. */
    fun removeStack(target: Target, top: Block) = remove(target, stack(target, top))

    /** The first block of a substack input, or null when the slot is empty. */
    fun substack(target: Target, block: Block, name: String): Block? =
        (block.inputs[name] as? Input.BlockRef)?.let { target.blocks[it.id] }

    /**
     * Takes [block] out of the chain it is in and puts the chain starting at [replacement] (which
     * may be null) in its place: the block before it, or the substack slot or top-level position
     * it occupied, now leads to the replacement chain, whose last block leads on to what followed
     * [block]. The blocks of [block]'s own tree are left for the caller to remove.
     */
    fun splice(target: Target, block: Block, replacement: Block?) {
        val chain = replacement?.let { chain(target, it) } ?: emptyList()
        val after = block.next?.let { target.blocks[it] }
        val head = chain.firstOrNull()
        val last = chain.lastOrNull()
        val newNextId = head?.id ?: after?.id

        val parentId = block.parent
        val parent = parentId?.let { target.blocks[it] }
        when {
            parent != null && parent.next == block.id -> parent.next = newNextId
            parent != null -> {
                val slot = parent.inputs.entries.firstOrNull { (it.value as? Input.BlockRef)?.id == block.id }
                if (slot != null) {
                    if (newNextId == null) {
                        parent.inputs.remove(
                            slot.key,
                        )
                    } else {
                        parent.inputs[slot.key] = Input.BlockRef(newNextId)
                    }
                }
            }
            else -> {
                // Top-level: the first block that remains takes the position on the workspace.
                val newTop = head ?: after
                if (newTop != null) {
                    newTop.topLevel = true
                    block.extra["x"]?.let { newTop.extra["x"] = it }
                    block.extra["y"]?.let { newTop.extra["y"] = it }
                }
            }
        }
        if (head != null) {
            head.parent = parentId
            last!!.next = after?.id
            after?.parent = last.id
        } else {
            after?.parent = parentId
        }
        block.next = null
        block.parent = null
        block.topLevel = false
    }
}
