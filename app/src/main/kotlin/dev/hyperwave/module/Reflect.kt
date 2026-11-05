package dev.hyperwave.module

internal object Reflect {
    fun field(receiver: Any, name: String): Any? {
        var type: Class<*>? = receiver.javaClass
        while (type != null) {
            try {
                val field = type.getDeclaredField(name)
                field.isAccessible = true
                return field.get(receiver)
            } catch (ignored: NoSuchFieldException) {
            }
            type = type.superclass
        }
        throw NoSuchFieldException(name)
    }

    fun call(receiver: Any, name: String): Any? {
        val method = receiver.javaClass.getMethod(name)
        method.isAccessible = true
        return method.invoke(receiver)
    }
}
