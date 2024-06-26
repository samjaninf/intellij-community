// PSI_ELEMENT: org.jetbrains.kotlin.psi.KtParameter
// OPTIONS: usages
// PSI_ELEMENT_AS_TITLE: "n: Int"

package pack

data class A(val <caret>n: Int, val s: String, val o: Any) {
    fun f() {
        "a".apply {
            this@A.toString()
        }
    }
}

abstract class X : Comparable<A>

fun A.ext1() {
    val (x, y) = getThis()
}

/**
 * Doc-comment reference 1: [A]
 * Doc-comment reference 2: [ext1]
 */
fun List<A>.ext1() {
    val (x, y) = get(0)
}

fun <T> T.getThis(): T = this




// IGNORE_PLATFORM_JS: Java-specific code
// IGNORE_PLATFORM_NATIVE: Java-specific code
