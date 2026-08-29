package top.e404.emorepo.experiment.lsposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DexMethodDescriptorTest {
    @Test
    fun `解析对象数组和基础类型参数`() {
        val descriptor = DexMethodDescriptor.parse(
            "Ljava/lang/String;->regionMatches(ZILjava/lang/String;II)Z",
        )

        assertEquals("java.lang.String", descriptor.declaringClassName)
        assertEquals("regionMatches", descriptor.methodName)
        assertEquals(listOf("Z", "I", "Ljava/lang/String;", "I", "I"), descriptor.parameterTypeNames)
        assertEquals(
            String::class.java.getDeclaredMethod(
                "regionMatches",
                Boolean::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                String::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            ),
            descriptor.resolve(requireNotNull(javaClass.classLoader)),
        )
    }

    @Test
    fun `拒绝构造器和损坏描述符`() {
        assertThrows(IllegalArgumentException::class.java) {
            DexMethodDescriptor.parse("Ljava/lang/String;-><init>()V")
        }
        assertThrows(IllegalArgumentException::class.java) {
            DexMethodDescriptor.parse("not-a-method")
        }
    }
}
