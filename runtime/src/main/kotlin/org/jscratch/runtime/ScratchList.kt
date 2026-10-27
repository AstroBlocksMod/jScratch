package org.jscratch.runtime

/**
 * A Scratch list. Indices in the public API are Scratch values (1-based, or "last", "random", "all").
 *
 * There is no length limit. The Scratch editor stops `add` and `insert` at 200,000 items, but
 * TurboWarp's compiler does not, and projects built for a compiler rely on that: a voxel world that
 * stores one list item per block overflows the editor's limit at default settings, and every write
 * above it would be dropped, which turns bookkeeping like a lighting flood fill into an infinite
 * loop. jScratch compiles projects the way TurboWarp does, so its lists follow TurboWarp.
 */
class ScratchList {
    val items: ArrayList<Any> = ArrayList()

    fun add(item: Any?) {
        items.add(item ?: "")
    }

    fun delete(index: Any?) {
        val i = Cast.toListIndex(index, items.size, true)
        if (i == Cast.LIST_ALL) {
            items.clear()
        } else if (i != Cast.LIST_INVALID) {
            items.removeAt(i - 1)
        }
    }

    fun deleteAll() = items.clear()

    fun insert(index: Any?, item: Any?) {
        val i = Cast.toListIndex(index, items.size + 1, false)
        if (i == Cast.LIST_INVALID) return
        items.add(i - 1, item ?: "")
    }

    fun replace(index: Any?, item: Any?) {
        val i = Cast.toListIndex(index, items.size, false)
        if (i == Cast.LIST_INVALID) return
        items[i - 1] = item ?: ""
    }

    fun item(index: Any?): Any {
        val i = Cast.toListIndex(index, items.size, false)
        if (i == Cast.LIST_INVALID) return ""
        return items[i - 1]
    }

    fun indexOf(item: Any?): Double {
        for (i in items.indices) {
            if (Cast.compare(items[i], item) == 0.0) return (i + 1).toDouble()
        }
        return 0.0
    }

    fun length(): Double = items.size.toDouble()

    fun contains(item: Any?): Boolean = items.any { Cast.compare(it, item) == 0.0 }

    /** The list as text: single characters join without spaces, anything else with spaces. */
    fun contents(): String {
        val allSingle = items.all { Cast.toString(it).length == 1 }
        return items.joinToString(if (allSingle) "" else " ") { Cast.toString(it) }
    }

    fun copy(): ScratchList {
        val c = ScratchList()
        c.items.addAll(items)
        return c
    }

    /** Fills the list from the JSON array text the compiler stored as a resource. */
    fun loadResource(path: String): ScratchList {
        val stream = ScratchList::class.java.classLoader.getResourceAsStream(path) ?: return this
        val text = stream.use { it.readBytes().toString(Charsets.UTF_8) }
        items.clear()
        items.addAll(ListData.decode(text))
        return this
    }
}
