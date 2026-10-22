package consumer
import fixture.decorated
fun greeting(): String = decorated().uppercase()
