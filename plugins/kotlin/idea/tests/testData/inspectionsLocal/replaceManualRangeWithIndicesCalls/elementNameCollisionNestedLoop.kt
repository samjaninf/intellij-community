// WITH_STDLIB
// IGNORE_K1
fun test() {
    val outer = listOf(listOf("a", "b"), listOf("c", "d"))
    for (element in outer) {
        for (j in 0 unt<caret>il element.size) {
            element[j].length
        }
    }
}
