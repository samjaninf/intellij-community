// "Replace with 'newFun()'" "true"
// WITH_STDLIB

@Deprecated("", ReplaceWith("newFun()"))
fun oldFun(p: Int?): Int {
    return newFun()
}

fun newFun(): Int = 0

fun foo(): Int = <caret>oldFun(bar())

fun bar(): Int? = 0

// FUS_QUICKFIX_NAME: org.jetbrains.kotlin.idea.quickfix.replaceWith.DeprecatedSymbolUsageFix
// FUS_K2_QUICKFIX_NAME: org.jetbrains.kotlin.idea.k2.codeinsight.fixes.replaceWith.DeprecatedSymbolUsageFix