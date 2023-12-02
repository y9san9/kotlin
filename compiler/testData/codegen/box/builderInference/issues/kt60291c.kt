// ISSUE: KT-60291

// IGNORE_LIGHT_ANALYSIS
// IGNORE_BACKEND: ANY
// REASON: red code in K1 (see corresponding diagnostic test)
// REASON: compile-time failure in K2/JVM (java.lang.IllegalStateException: Cannot serialize error type: ERROR CLASS: Cannot infer argument for type parameter PTV)
// REASON: compile-time failure in K2/Native, K2/WASM, K2/JS (org.jetbrains.kotlin.backend.common.linkage.issues.IrDisallowedErrorNode: Class found but error nodes are not allowed)

fun box(): String {
    when ("") {
        "true" -> build { setTypeVariable(TargetType()) }
        "false" -> build {}
        else -> Buildee()
    }
    return "OK"
}




class TargetType

class Buildee<TV> {
    fun setTypeVariable(value: TV) { storage = value }
    private var storage: TV = TargetType() as TV
}

fun <PTV> build(instructions: Buildee<PTV>.() -> Unit): Buildee<PTV> {
    return Buildee<PTV>().apply(instructions)
}
