// "Replace with 'new'" "true"
// WITH_RUNTIME

class A {
    @Deprecated("msg", ReplaceWith("new"))
    var old
        get() = new
        set(value) {
            new = value
        }

    var new = ""
}

fun foo() {
    val a = A()
    // Works incorrectly yet
    a.<caret>old = "foo"
}