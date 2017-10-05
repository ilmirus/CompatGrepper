package us.ilmir

import org.objectweb.asm.ClassReader
import org.objectweb.asm.signature.SignatureReader
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.util.TraceSignatureVisitor
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

class ClassFile(val path: String, val content: ByteArray) {
    fun classNode(): ClassNode {
        val cn = ClassNode()
        val reader = ClassReader(content.inputStream())
        reader.accept(cn, 0)
        return cn
    }
    fun type(): String = "L${classNode().name};"
}

fun InputStream.classFiles(): List<ClassFile> {
    val zis = ZipInputStream(this)
    var entry: ZipEntry? = zis.nextEntry
    val res = arrayListOf<ClassFile>()
    while (entry != null) {
        if (entry.name.endsWith(".class")) res += ClassFile(entry.name, zis.readBytes(entry.size.toInt()))
        entry = zis.nextEntry
    }
    return res
}

fun InputStream.classesJar(): InputStream {
    val zis = ZipInputStream(this)
    var entry: ZipEntry? = zis.nextEntry
    while (entry != null) {
        if (entry.name == "classes.jar") return zis.readBytes(entry.size.toInt()).inputStream()
        entry = zis.nextEntry
    }
    return ByteArray(0).inputStream()
}

fun MethodNode.readSignature(): String {
    val tsv = TraceSignatureVisitor(0)
    val reader = SignatureReader(if (this.signature == null) this.desc else this.signature)
    reader.accept(tsv)
    return "${tsv.returnType} ${this.name}${tsv.declaration}"
}

