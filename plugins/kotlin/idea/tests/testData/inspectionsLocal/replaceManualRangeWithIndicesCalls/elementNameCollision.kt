// WITH_STDLIB
// FIX: Replace with loop over elements
// IGNORE_K1
fun test() {
    val element = mutableListOf<String>("hello", "world")
    for (i in 0 unt<caret>il element.size) {
        element[i].length
        element.size
    }
}
