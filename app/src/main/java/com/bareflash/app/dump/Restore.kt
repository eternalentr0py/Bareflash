package com.bareflash.app.dump

import com.bareflash.app.console.TargetInfo
import org.json.JSONObject

/**
 * Plans and checks a restore before anything is sent to the device: re-hashes every file
 * against its manifest and compares the chip the backup came from with the chip that is
 * connected. Pure file work; the write itself goes through the normal write path.
 */
object Restore {

    data class Item(val rel: String, val bytes: Long, val sha256: String?, val partition: String?)

    data class Plan(
        val label: String,
        /** Folder under dumps/ for a full backup, or null for a single image. */
        val folder: String?,
        val items: List<Item>,
        val chipHw: String?,
        val chipCpu: String?,
        val hasManifest: Boolean,
        /** Partition name for a single image; null when it is not known. */
        val partition: String?,
    ) {
        val isFolder: Boolean get() = folder != null
    }

    data class Verdict(val ok: Boolean, val problems: List<String>, val checked: Int)

    sealed class ChipCheck {
        object Same : ChipCheck()
        data class Different(val message: String) : ChipCheck()
        data class Unknown(val message: String) : ChipCheck()
    }

    fun planFolder(folder: String): Plan? = runCatching {
        val text = DumpBridge.readText("$folder/MANIFEST.json") ?: return null
        val j = JSONObject(text)
        val parts = j.optJSONArray("partitions")
        val names = HashSet<String>()
        if (parts != null) for (i in 0 until parts.length()) names += parts.getJSONObject(i).optString("name").lowercase()
        val files = j.optJSONArray("files") ?: return null
        val items = ArrayList<Item>()
        for (i in 0 until files.length()) {
            val f = files.getJSONObject(i)
            val file = f.getString("file")
            val stem = file.substringBeforeLast('.').lowercase()
            items += Item("$folder/$file", f.optLong("bytes", -1L), f.optString("sha256").ifBlank { null }, stem.takeIf { it in names })
        }
        val chip = j.optJSONObject("chip")
        Plan(folder, folder, items, chip?.optString("hwCode")?.ifBlank { null }, chip?.optString("cpu")?.ifBlank { null }, true, null)
    }.getOrNull()

    fun planImage(rel: String): Plan {
        val name = rel.substringAfterLast('/')
        val text = DumpBridge.readText(rel + DumpIntegrity.MANIFEST_SUFFIX)
        val j = runCatching { JSONObject(text ?: "") }.getOrNull()
        val guess = name.substringBeforeLast('.').takeIf { it.isNotBlank() }
        val part = j?.optJSONObject("partition")?.optString("name")?.ifBlank { null } ?: guess
        val chip = j?.optJSONObject("chip")
        val size = DumpBridge.size(rel)
        val item = Item(rel, j?.optLong("bytes", size) ?: size, j?.optString("sha256")?.ifBlank { null }, part)
        return Plan(name, null, listOf(item), chip?.optString("hwCode")?.ifBlank { null }, chip?.optString("cpu")?.ifBlank { null }, j != null, part)
    }

    /** Re-hashes every file against the manifest. Any mismatch names the file. */
    fun verify(plan: Plan, onProgress: (file: String, done: Long, total: Long) -> Unit, cancelled: () -> Boolean): Verdict {
        val problems = ArrayList<String>()
        var checked = 0
        for (it in plan.items) {
            if (cancelled()) return Verdict(false, problems + "Cancelled.", checked)
            val name = it.rel.substringAfterLast('/')
            val size = DumpBridge.size(it.rel)
            if (size < 0L) { problems += "$name is missing."; continue }
            if (it.bytes >= 0L && size != it.bytes) { problems += "$name is $size bytes, the manifest says ${it.bytes}."; continue }
            val want = it.sha256 ?: continue
            // With region hashes saved, one pass also names the regions that differ.
            val m = DumpIntegrity.readManifest(it.rel)
            val regional = m != null && m.chunks.isNotEmpty()
            val scan = if (regional) DumpIntegrity.scanRel(it.rel, size) { done -> onProgress(name, done, size) } else null
            val got = if (regional) scan?.sha256 else DumpIntegrity.sha256Rel(it.rel, size) { done -> onProgress(name, done, size) }
            checked++
            if (got == null) {
                problems += "$name could not be read."
            } else if (!got.equals(want, ignoreCase = true)) {
                val bad = if (m != null && scan != null) DumpIntegrity.badChunks(m, scan) else null
                val where = if (m != null && !bad.isNullOrEmpty()) DumpIntegrity.describeRegions(bad, m.chunkSize, size) else ""
                problems += if (where.isNotEmpty()) "$name does not match its saved SHA-256. It differs in $where." else "$name does not match its saved SHA-256."
            }
        }
        return Verdict(problems.isEmpty(), problems, checked)
    }

    private fun hexVal(s: String?): Long? = s?.trim()?.removePrefix("0x")?.removePrefix("0X")?.toLongOrNull(16)

    fun chipCheck(plan: Plan, target: TargetInfo): ChipCheck {
        val wantHw = hexVal(plan.chipHw)
        val haveHw = hexVal(target.hwCode)
        val wantCpu = plan.chipCpu?.trim()?.lowercase()
        val haveCpu = target.cpu?.trim()?.lowercase()
        if (wantHw == null && wantCpu == null) {
            return ChipCheck.Unknown("This backup does not say which chip it came from.")
        }
        if (haveHw == null && haveCpu == null) {
            return ChipCheck.Unknown("No device has been identified yet, so the chip could not be compared. Run Identify first.")
        }
        val hwDiff = wantHw != null && haveHw != null && wantHw != haveHw
        val cpuDiff = wantCpu != null && haveCpu != null && wantCpu != haveCpu
        if (hwDiff || cpuDiff) {
            return ChipCheck.Different(
                "This backup is from ${plan.chipCpu ?: plan.chipHw}, the connected device is ${target.cpu ?: target.hwCode}. " +
                    "Writing another phone's images can leave this one unable to boot."
            )
        }
        return ChipCheck.Same
    }

    private fun q(p: String) = if (p.any { it.isWhitespace() }) "\"$p\"" else p

    fun command(plan: Plan, partition: String? = plan.partition): String? =
        if (plan.isFolder) "wl dumps/${q(plan.folder!!)}"
        else partition?.takeIf { it.isNotBlank() }?.let { "w $it dumps/${q(plan.items.first().rel)}" }
}
