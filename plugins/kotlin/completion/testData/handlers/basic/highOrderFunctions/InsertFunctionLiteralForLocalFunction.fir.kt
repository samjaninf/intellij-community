// FIR_COMPARISON
fun usage() {
    fun myLocalFun(action: () -> Unit) {}

    myLocalFu<caret>
}

// ELEMENT: myLocalFun
// TAIL_TEXT: " { action: () -> Unit }"