package us.ilmir

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.signature.SignatureReader
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.util.TraceSignatureVisitor
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class ClassFile(val path: String, var content: ByteArray) {
    fun classNode(): ClassNode {
        val cn = ClassNode()
        val reader = ClassReader(content)
        reader.accept(cn, 0)
        return cn
    }

    fun type(): String = "L${classNode().name};"

    fun name(): String = classNode().name.replace('/', '.')

    fun addAnnotation(name: String, params: HashMap<String, Any> = hashMapOf(), visible: Boolean = false) {
        val cn = ClassNode(Opcodes.ASM4)
        val reader = ClassReader(content)
        val writer = ClassWriter(reader,0)
        reader.accept(cn, 0)
        val annotation = AnnotationNode(name)
        if (annotation.values == null && params.isNotEmpty()) annotation.values = arrayListOf()
        for ((paramName, paramValue) in params) {
            annotation.values.add(paramName)
            annotation.values.add(paramValue)
        }
        if (visible) {
            if (cn.visibleAnnotations == null) cn.visibleAnnotations = arrayListOf()
            cn.visibleAnnotations.add(annotation)
        } else {
            if (cn.invisibleAnnotations == null) cn.invisibleAnnotations = arrayListOf()
            cn.invisibleAnnotations.add(annotation)
        }
        cn.accept(writer)
        content = writer.toByteArray()
    }
}

fun InputStream.classFiles(): List<ClassFile> {
    val zis = ZipInputStream(this)
    var entry: ZipEntry? = zis.nextEntry
    val res = arrayListOf<ClassFile>()
    while (entry != null) {
        assert(entry.size.toInt().toLong() == entry.size && entry.size > 0) { "Integer overflow "}
        if (entry.name.endsWith(".class")) res += ClassFile(entry.name, zis.readBytes(entry.size.toInt()))
        entry = zis.nextEntry
    }
    return res
}

fun InputStream.classesJar(): InputStream {
    val zis = ZipInputStream(this)
    var entry: ZipEntry? = zis.nextEntry
    while (entry != null) {
        assert(entry.size.toInt().toLong() == entry.size && entry.size > 0) { "Integer overflow "}
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

fun InputStream.replaceClassesInJar(newClasses: List<ClassFile>): ByteArray {
    val replacements = newClasses.map { Pair(it.path, it.content) }.toMap()
    val res = ByteArrayOutputStream()
    val zos = ZipOutputStream(res)
    val zis = ZipInputStream(this)
    var entry: ZipEntry? = zis.nextEntry
    while (entry != null) {
        zos.putNextEntry(entry)
        assert(entry.size.toInt().toLong() == entry.size && entry.size > 0) { "Integer overflow "}
        zos.write(replacements[entry.name] ?: zis.readBytes(entry.size.toInt()))
        zos.closeEntry()
        entry = zis.nextEntry
    }
    zos.close()
    return res.toByteArray().inputStream().addClassesToJar(emptyList())
}

fun InputStream.addClassesToJar(newClasses: List<ClassFile>): ByteArray {
    val res = ByteArrayOutputStream()
    val zos = ZipOutputStream(res)
    val zis = ZipInputStream(this)
    var entry: ZipEntry? = zis.nextEntry
    while (entry != null) {
        zos.putNextEntry(entry)
        assert(entry.size.toInt().toLong() == entry.size && entry.size > 0) { "Integer overflow "}
        zos.write(zis.readBytes(entry.size.toInt()))
        zos.closeEntry()
        entry = zis.nextEntry
    }
    for (cf in newClasses) {
        val e = ZipEntry(cf.path)
        zos.putNextEntry(e)
        zos.write(cf.content)
        zos.closeEntry()
    }
    zos.close()
    return res.toByteArray()
}
