package consumer
fun forbidden(): Int = hidden.privateValue() + runtimeonly.runtimeOnlyApi()
