package us.ilmir

import org.objectweb.asm.ClassReader
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlin.collections.ArrayList

const private val SUPPORT_COMPAT = "support-compat-26.1.0.aar"
const private val ANDROID_JAR = "android.jar"

private class MethodInfo(
        val access: Int,
        val name: String,
        val returnType: Type,
        val argTypes: List<Type>,
        val desc: String
)

private class ClassFile(val name: String, val content: ByteArray) {
    fun methods(): List<MethodInfo> = classNode().methods.map {
        MethodInfo(
                it.access,
                it.name,
                Type.getReturnType(it.desc),
                Type.getArgumentTypes(it.desc).toList(),
                it.desc
        )
    }

    fun type(): String = classNode().name
    private fun classNode(): ClassNode {
        val cn = ClassNode()
        val reader = ClassReader(content.inputStream())
        reader.accept(cn, 0)
        return cn
    }
}

private class Inconsistency(
        val methodName: String,
        val originDesc: String,
        val compatDesc: String,
        val reason: Inconsistency.Reason
) {
    enum class Reason(val s: String) {
        DIFFERENT_ACCESS("Different access"),
        NOT_ORIGIN_ON_FIRST_PARAM("Not origin on first param"),
        NO_ORIGIN_COMPANION("No origin companion"),
        TOO_FEW_ORIGIN_COMPANIONS("Too few origin companions"),
        TOO_MANY_ORIGIN_COMPANIONS("Too many origin companions"),
        DIFFERENT_PARAMS("Different params"),
        DIFFERENT_RETURN_TYPE("Different return type"),
        EMPTY_PARAMS("Empty params on compat")
    }

    override fun toString() = "$methodName: ${reason.s}. Compat: $compatDesc${printOrigin()}"

    private fun printOrigin() = if (originDesc.isEmpty()) "" else ", Origin: $originDesc"
}

fun <K, T> List<T>.toMap(op: (T) -> K): Map<K, List<T>> {
    val res = hashMapOf<K, ArrayList<T>>()
    for (item in this) {
        val key = op(item)
        if (res.containsKey(key)) res[key]!!.add(item)
        else res[key] = arrayListOf(item)
    }
    return res
}

private class OriginAndCompat(val origin: ClassFile, val compat: ClassFile) {
    fun findInconsistencies(): List<Inconsistency> {
        val compatInfos = compat.methods().toMap { it.name }
        val originInfos = origin.methods().toMap { it.name }
        val res = arrayListOf<Inconsistency>()
        for ((name, compatOverloads) in compatInfos) {
            if (name.contains("<")) {
                continue
            }
            val originOverloads = originInfos[name]
            when {
                originOverloads == null ->
                    res += Inconsistency(
                            name,
                            "",
                            compatOverloads[0].desc,
                            Inconsistency.Reason.NO_ORIGIN_COMPANION
                    )
                originOverloads.size < compatOverloads.size ->
                    res += Inconsistency(
                            name,
                            originOverloads[0].desc,
                            compatOverloads[0].desc,
                            Inconsistency.Reason.TOO_FEW_ORIGIN_COMPANIONS
                    )
                else -> res += checkOverloads(name, originOverloads, compatOverloads)
            }
        }
        return res
    }

    private fun checkOverloads(name: String, originOverloads: List<MethodInfo>, compatOverloads: List<MethodInfo>): List<Inconsistency> {
        val res = arrayListOf<Inconsistency>()
        for (compatInfo in compatOverloads) {
            when {
                compatInfo.argTypes.isEmpty() ->
                    res += Inconsistency(
                            name,
                            originOverloads[0].desc,
                            compatInfo.desc,
                            Inconsistency.Reason.EMPTY_PARAMS
                    )
                compatInfo.argTypes[0].toString() != origin.type() ->
                    res += Inconsistency(
                            name,
                            "",
                            compatInfo.desc,
                            Inconsistency.Reason.NOT_ORIGIN_ON_FIRST_PARAM
                    )
                else -> {
                    val originInfo = originOverloads.find { it.argTypes == compatInfo.argTypes.rest() }
                    if (originInfo == null) res += Inconsistency(
                            name,
                            originOverloads[0].desc,
                            compatInfo.desc,
                            Inconsistency.Reason.DIFFERENT_PARAMS
                    )
                    else {
                        val inc = compareMethods(name, originInfo, compatInfo)
                        if (inc != null) res += inc
                    }
                }
            }
        }
        return res
    }

    private fun compareMethods(name: String, originInfo: MethodInfo, compatInfo: MethodInfo) = when {
        compatInfo.access != originInfo.access -> Inconsistency(
                name,
                originInfo.desc,
                compatInfo.desc,
                Inconsistency.Reason.DIFFERENT_ACCESS
        )
        compatInfo.returnType.toString().replace("Compat", "") != compatInfo.returnType.toString() ->
            Inconsistency(
                    name,
                    originInfo.desc,
                    compatInfo.desc,
                    Inconsistency.Reason.DIFFERENT_RETURN_TYPE
            )
        else -> null
    }
}

private fun <E> List<E>.rest(): List<E> = this.subList(1, this.size)

private fun findCompats(): List<ClassFile> {
    fun findClassesJar(zis: ZipInputStream): ByteArray {
        var entry: ZipEntry? = zis.nextEntry
        while (entry != null) {
            if (entry.name == "classes.jar") return zis.readBytes(entry.size.toInt())
            entry = zis.nextEntry
        }
        return ByteArray(0)
    }

    val res = arrayListOf<ClassFile>()
    val stream = ClassFile::class.java.classLoader.getResourceAsStream(SUPPORT_COMPAT)
    val classesJar = ZipInputStream(findClassesJar(ZipInputStream(stream)).inputStream())
    var cjEntry: ZipEntry? = classesJar.nextEntry
    while (cjEntry != null) {
        if (cjEntry.name.endsWith("Compat.class")) {
            res.add(ClassFile(cjEntry.name, classesJar.readBytes(cjEntry.size.toInt())))
        }
        cjEntry = classesJar.nextEntry
    }
    return res
}

private fun findCompatOrigins(compatNames: List<String>): List<ClassFile> {
    val strippedNames = compatNames.map {
        it.substring(it.lastIndexOf('/'))
                .replace("Compat.class", ".class")
                .replace("Compat$", "$")
    }
    val res = arrayListOf<ClassFile>()
    val stream = ClassFile::class.java.classLoader.getResourceAsStream(ANDROID_JAR)
    val zis = ZipInputStream(stream)
    var entry: ZipEntry? = zis.nextEntry
    while (entry != null) {
        val startIndex = entry.name.lastIndexOf('/')
        if (startIndex < 0) {
            entry = zis.nextEntry
            continue
        }
        val name = entry.name.substring(startIndex)
        if (strippedNames.contains(name)) {
            res.add(ClassFile(entry.name, zis.readBytes(entry.size.toInt())))
        }
        entry = zis.nextEntry
    }
    return res
}

private typealias AloneCompats = List<ClassFile>

private fun combine(origins: List<ClassFile>, compats: List<ClassFile>): Pair<List<OriginAndCompat>, AloneCompats> {
    val res = arrayListOf<OriginAndCompat>()
    val alones = arrayListOf<ClassFile>()
    for (compat in compats) {
        val stripped = compat.name
                .substring(compat.name.lastIndexOf('/'))
                .replace("Compat.class", ".class")
                .replace("Compat$", "$")
        val origin = origins.find { it.name.endsWith(stripped) }
        if (origin == null) alones += compat
        else res += OriginAndCompat(origin, compat)
    }
    return Pair(res, alones)
}

fun main(args: Array<String>) {
    val compats = findCompats()
    val compatNames = compats.map { it.name }
    val origins = findCompatOrigins(compatNames)
    val (oacs, alones) = combine(origins, compats)
    for (alone in alones) {
        println("Alone compat: ${alone.name}")
    }
    val res = oacs.map { Triple(it.origin.name, it.compat.name, it.findInconsistencies()) }
    for ((origin, compat, inconsistencies) in res) {
        if (inconsistencies.isNotEmpty()) println("$origin $compat:")
        for (inconsistency in inconsistencies) {
            println("\t$inconsistency")
        }
    }
}