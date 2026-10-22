package support

import kotlinx.serialization.SerializationException

fun runtimeValue(): String = SerializationException("runtime-external").message!!
