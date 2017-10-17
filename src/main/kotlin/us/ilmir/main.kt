package us.ilmir

import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.MethodNode
import java.io.FileOutputStream
import kotlin.collections.ArrayList

const private val SUPPORT_COMPAT = "support-compat-26.1.0.aar"
const private val ANDROID_JAR = "android.jar"

private class MethodInfo(
        val name: String,
        val returnType: Type,
        val argTypes: List<Type>,
        val desc: String,
        val isStatic: Boolean
)

private fun ClassFile.methods(): List<MethodInfo> = classNode().methods
        .filter { it.access and Opcodes.ACC_PUBLIC != 0 }
        .map {
            MethodInfo(
                    it.name,
                    Type.getReturnType(it.desc),
                    Type.getArgumentTypes(it.desc).toList(),
                    it.readSignature(),
                    it.isStatic
            )
        }


private val MethodNode.isStatic
    get() = when {
        this.localVariables == null -> true
        this.localVariables.isEmpty() -> true
        else -> this.localVariables[0].name != "this"
    }

private class Inconsistency(
        val methodName: String,
        val originDesc: String,
        val compatDesc: String,
        val reason: Inconsistency.Reason
) {
    enum class Reason(val s: String) {
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

private fun <K, T> List<T>.toMap(op: (T) -> K): Map<K, List<T>> {
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
        for ((name, tmp) in compatInfos) {
            if (name.contains("<")) {
                continue
            }
            val compatOverloads = tmp.filter { it.isStatic }
            if (compatOverloads.isEmpty()) {
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

private fun findCompats() = ClassFile::class.java.classLoader.getResourceAsStream(SUPPORT_COMPAT)
        .classesJar().classFiles().filter { it.path.endsWith("Compat.class") }

private fun findCompatOrigins(compatNames: List<String>): List<ClassFile> {
    var strippedNames = compatNames.map {
        it.substring(it.lastIndexOf('/'))
                .replace("Compat.class", ".class")
                .replace("Compat$", "$")
    }
    strippedNames += strippedNames.map { it.replace('/', '$') }
    val classFiles = ClassFile::class.java.classLoader.getResourceAsStream(ANDROID_JAR).classFiles()
    return classFiles.filter {
        var startIndex = it.path.lastIndexOf('/')
        if (startIndex < 0) return@filter false
        val name = it.path.substring(startIndex)
        if (strippedNames.contains(name)) return@filter true
        startIndex = it.path.lastIndexOf('$')
        if (startIndex < 0) return@filter false
        if (strippedNames.contains(name)) return@filter true
        return@filter false
    }
}

private typealias AloneCompats = List<ClassFile>

private fun combine(origins: List<ClassFile>, compats: List<ClassFile>): Pair<List<OriginAndCompat>, AloneCompats> {
    val res = arrayListOf<OriginAndCompat>()
    val alones = arrayListOf<ClassFile>()
    for (compat in compats) {
        var stripped = compat.path
                .substring(compat.path.lastIndexOf('/'))
                .replace("Compat.class", ".class")
                .replace("Compat$", "$")
        var origin = origins.find { it.path.endsWith(stripped) }
        if (origin == null) {
            stripped = stripped.replace('/', '$')
            origin = origins.find { it.path.endsWith(stripped) }
        }
        if (origin == null) alones += compat
        else res += OriginAndCompat(origin, compat)
    }
    return Pair(res, alones)
}

fun main(args: Array<String>) {
    val compats = findCompats()
    val compatNames = compats.map { it.path }
    val origins = findCompatOrigins(compatNames)
    for (compat in compats) {
        compat.methods()
                .filter { it.desc.contains(">(") && !it.name.contains("<") }
                .forEach { println("Generic method: ${compat.path} ${it.desc}") }
    }
    val (oacs, alones) = combine(origins, compats)
    alones.forEach { println("Alone compat: ${it.path}") }
    oacs.forEach { it.origin.addAnnotation("Lkotlin/android/Compat;", hashMapOf("value" to Type.getType(it.compat.type()))) }
    FileOutputStream(ANDROID_JAR).use {
        it.write(ClassFile::class.java.classLoader.getResourceAsStream(ANDROID_JAR).replaceClassesInJar(oacs.map { it.origin }))
    }
    val res = oacs.map { Triple(it.origin.path, it.compat.path, it.findInconsistencies()) }
    for ((origin, compat, inconsistencies) in res) {
        if (inconsistencies.isNotEmpty()) println("$origin $compat:")
        for (inconsistency in inconsistencies) {
            println("\t$inconsistency")
        }
    }
}