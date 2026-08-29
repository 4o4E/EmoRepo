package top.e404.emorepo.experiment.lsposed

import java.lang.reflect.Array
import java.lang.reflect.Method

/**
 * 解析 DexKit 返回的 DEX 方法描述符，只负责把已经定位的签名恢复为宿主反射方法。
 */
internal data class DexMethodDescriptor(
    val declaringClassName: String,
    val methodName: String,
    val parameterTypeNames: List<String>,
) {
    fun resolve(classLoader: ClassLoader): Method {
        val declaringClass = Class.forName(declaringClassName, false, classLoader)
        val parameterTypes = parameterTypeNames.map { resolveType(it, classLoader) }.toTypedArray()
        return declaringClass.getDeclaredMethod(methodName, *parameterTypes).apply { isAccessible = true }
    }

    companion object {
        fun parse(value: String): DexMethodDescriptor {
            val separator = value.indexOf(";->")
            require(value.startsWith('L') && separator > 1) { "DEX 方法描述符缺少声明类" }
            val declaringClass = value.substring(1, separator).replace('/', '.')
            val methodStart = separator + 3
            val parametersStart = value.indexOf('(', methodStart)
            val parametersEnd = value.indexOf(')', parametersStart + 1)
            require(parametersStart > methodStart && parametersEnd > parametersStart) {
                "DEX 方法描述符缺少参数列表"
            }
            val methodName = value.substring(methodStart, parametersStart)
            require(methodName.isNotBlank() && methodName != "<init>" && methodName != "<clinit>") {
                "DEX 描述符不是普通方法"
            }
            val parameters = parseTypes(value.substring(parametersStart + 1, parametersEnd))
            return DexMethodDescriptor(declaringClass, methodName, parameters)
        }

        private fun parseTypes(value: String): List<String> {
            val result = mutableListOf<String>()
            var index = 0
            while (index < value.length) {
                val start = index
                while (index < value.length && value[index] == '[') index++
                require(index < value.length) { "DEX 数组类型不完整" }
                if (value[index] == 'L') {
                    val end = value.indexOf(';', index)
                    require(end >= index) { "DEX 对象类型不完整" }
                    index = end + 1
                } else {
                    require(value[index] in PRIMITIVE_TYPES) { "不支持的 DEX 类型：${value[index]}" }
                    index++
                }
                result += value.substring(start, index)
            }
            return result
        }

        private fun resolveType(descriptor: String, classLoader: ClassLoader): Class<*> {
            var dimensions = 0
            while (dimensions < descriptor.length && descriptor[dimensions] == '[') dimensions++
            val componentDescriptor = descriptor.substring(dimensions)
            val component = when (componentDescriptor) {
                "Z" -> Boolean::class.javaPrimitiveType!!
                "B" -> Byte::class.javaPrimitiveType!!
                "C" -> Char::class.javaPrimitiveType!!
                "S" -> Short::class.javaPrimitiveType!!
                "I" -> Int::class.javaPrimitiveType!!
                "J" -> Long::class.javaPrimitiveType!!
                "F" -> Float::class.javaPrimitiveType!!
                "D" -> Double::class.javaPrimitiveType!!
                else -> {
                    require(componentDescriptor.startsWith('L') && componentDescriptor.endsWith(';')) {
                        "DEX 对象类型无效：$componentDescriptor"
                    }
                    Class.forName(
                        componentDescriptor.substring(1, componentDescriptor.length - 1).replace('/', '.'),
                        false,
                        classLoader,
                    )
                }
            }
            return if (dimensions == 0) {
                component
            } else {
                Array.newInstance(component, *IntArray(dimensions)).javaClass
            }
        }

        private const val PRIMITIVE_TYPES = "ZBCSIJFD"
    }
}
