import com.bareflash.fastboot.*
import com.bareflash.usb.CancelToken
import java.io.File

fun main(args: Array<String>) {
    val src = FileByteSource(File(args[0]))
    val pieces = FlashPlanner.plan(src, args[1].toLong())
    for ((i, p) in pieces.withIndex()) {
        File("${args[2]}_%03d.simg".format(i)).outputStream().use { o ->
            p.writeTo({ b, off, len -> o.write(b, off, len) }, CancelToken.NEVER)
        }
    }
    println("pieces=${pieces.size} max=${pieces.maxOf { it.size }}")
}
