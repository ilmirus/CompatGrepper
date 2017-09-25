package us.ilmir

import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

const private val SUPPORT_COMPAT = "support-compat-26.1.0.aar"

private class ClassFile(val name:String, val content: ByteArray)

private fun unzipFiles(stream: InputStream): ArrayList<ClassFile> {
    fun findClassesJar(zis: ZipInputStream): ByteArray {
        var entry: ZipEntry? = zis.nextEntry
        while (entry != null) {
            if (entry.name == "classes.jar") return zis.readBytes(entry.size.toInt())
            entry = zis.nextEntry
        }
        return ByteArray(0)
    }
    val res = arrayListOf<ClassFile>()
    val classesJar = ZipInputStream(findClassesJar(ZipInputStream(stream)).inputStream())
    var cjEntry: ZipEntry? = classesJar.nextEntry
    while (cjEntry != null) {
        res.add(ClassFile(cjEntry.name, classesJar.readBytes(cjEntry.size.toInt())))
        cjEntry = classesJar.nextEntry
    }
    return res
}

fun main(args: Array<String>) {
    unzipFiles(ClassFile::class.java.classLoader.getResourceAsStream(SUPPORT_COMPAT))
            .filter { it.name.endsWith("Compat.class") }
            .forEach { println(it.name) }
}