package com.bareflash.cli

import com.bareflash.fastboot.ByteSource
import com.bareflash.fastboot.FileByteSource
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files

/** A file or folder on this phone, as far as the command line needs to know it. */
public interface LocalFile {
    public val name: String
    public val path: String
    public val exists: Boolean
    public val isDirectory: Boolean
    public val isSymlink: Boolean
    public val size: Long
    public val mtimeSeconds: Long
    public val isExecutable: Boolean

    public fun openInput(): InputStream
    public fun openOutput(): OutputStream
    public fun openByteSource(): ByteSource

    /** Entries of a folder, sorted by name. */
    public fun children(): List<LocalFile>

    /** A direct child by plain name (no slashes, not `.` or `..`). */
    public fun child(name: String): LocalFile

    public fun mkdirs(): Boolean

    /** Creates the folder this file lives in, if it is missing. */
    public fun mkParentDirs(): Boolean
    public fun delete(): Boolean
}

/** Resolves the paths a user types. */
public interface LocalFiles {
    public fun resolve(path: String): LocalFile

    /** Where `adb pull` puts things when no destination is given. */
    public val downloadDir: LocalFile
}

/**
 * [LocalFiles] over `java.io.File`. A leading `~` means [home]; other relative
 * paths are taken relative to [home].
 */
public class JavaLocalFiles(private val home: File, private val downloads: File = home) : LocalFiles {
    override fun resolve(path: String): LocalFile {
        val f = when {
            path == "~" -> home
            path.startsWith("~/") -> File(home, path.substring(2))
            path.startsWith("/") -> File(path)
            else -> File(home, path)
        }
        return JavaLocalFile(f)
    }

    override val downloadDir: LocalFile get() = JavaLocalFile(downloads)
}

internal class JavaLocalFile(private val f: File) : LocalFile {
    override val name: String get() = f.name
    override val path: String get() = f.path
    override val exists: Boolean get() = f.exists()
    override val isDirectory: Boolean get() = f.isDirectory
    override val isSymlink: Boolean get() = Files.isSymbolicLink(f.toPath())
    override val size: Long get() = f.length()
    override val mtimeSeconds: Long get() = f.lastModified() / 1000
    override val isExecutable: Boolean get() = f.canExecute()

    override fun openInput(): InputStream = f.inputStream().buffered(64 * 1024)
    override fun openOutput(): OutputStream = f.outputStream().buffered(64 * 1024)
    override fun openByteSource(): ByteSource = FileByteSource(f)

    override fun children(): List<LocalFile> =
        (f.listFiles() ?: emptyArray()).sortedBy { it.name }.map { JavaLocalFile(it) }

    override fun child(name: String): LocalFile {
        require(name.isNotEmpty() && '/' !in name && name != "." && name != ".." && '\u0000' !in name) { "Unsafe file name: $name" }
        return JavaLocalFile(File(f, name))
    }

    override fun mkdirs(): Boolean = f.isDirectory || f.mkdirs()
    override fun mkParentDirs(): Boolean = f.absoluteFile.parentFile?.let { it.isDirectory || it.mkdirs() } ?: true
    override fun delete(): Boolean = f.delete()
}
