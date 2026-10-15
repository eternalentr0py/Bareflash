package com.bareflash.app.dump

/** Sizes and clean-up for what is in the dump folder, grouped the way a person thinks of it. */
object DumpStorage {

    data class Group(
        val name: String,
        val isFolder: Boolean,
        val bytes: Long,
        val files: Int,
        val newest: Long,
        /** Every relative path in the group, sidecars included. */
        val rels: List<String>,
    )

    private fun baseOf(name: String): String =
        name.removeSuffix(DumpIntegrity.MANIFEST_SUFFIX).removeSuffix(DumpIntegrity.SHA_SUFFIX)

    /** One group per top-level folder, one per loose image (its sidecars included). */
    fun groups(entries: List<DumpBridge.Entry>): List<Group> {
        val folders = LinkedHashMap<String, MutableList<DumpBridge.Entry>>()
        val loose = LinkedHashMap<String, MutableList<DumpBridge.Entry>>()
        for (e in entries) {
            if (e.name.contains('/')) folders.getOrPut(e.name.substringBefore('/')) { ArrayList() } += e
            else loose.getOrPut(baseOf(e.name)) { ArrayList() } += e
        }
        val out = ArrayList<Group>()
        folders.forEach { (n, l) -> out += Group(n, true, l.sumOf { it.size }, l.size, l.maxOf { it.modified }, l.map { it.name }) }
        loose.forEach { (n, l) -> out += Group(n, false, l.sumOf { it.size }, l.size, l.maxOf { it.modified }, l.map { it.name }) }
        return out.sortedByDescending { it.bytes }
    }

    fun olderThan(groups: List<Group>, days: Int, nowMs: Long = System.currentTimeMillis()): List<Group> {
        val cutoff = nowMs - days * 24L * 3600L * 1000L
        return groups.filter { it.newest in 1 until cutoff }
    }

    /** Deletes a group together with its sidecars and its partial-dump records. Returns files removed. */
    fun delete(g: Group): Int {
        var n = 0
        if (g.isFolder) {
            if (DumpBridge.deleteRel(g.name)) n = g.files
        } else {
            for (rel in g.rels) if (DumpBridge.deleteRel(rel)) n++
        }
        PartialDumps.all().keys.filter { it == g.name || it.startsWith(g.name + "/") }.forEach { PartialDumps.remove(it) }
        return n
    }

    /** `.sha256` and `.manifest.json` files whose image is gone. */
    fun orphans(entries: List<DumpBridge.Entry>): List<String> {
        val names = entries.map { it.name }.toSet()
        return entries.map { it.name }.filter { DumpIntegrity.isSidecar(it) && baseOf(it) !in names }
    }

    fun deleteOrphans(entries: List<DumpBridge.Entry>): Int = orphans(entries).count { DumpBridge.deleteRel(it) }
}
