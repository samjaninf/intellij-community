// "Replace with '"p = $p"'" "true"

@Deprecated("", ReplaceWith("\"p = \$p\""))
fun oldFun(p: Int) = "p = $p"

fun foo(p: Int) {
    val s = <caret>oldFun(p + 1)
}

// FUS_QUICKFIX_NAME: org.jetbrains.kotlin.idea.quickfix.replaceWith.DeprecatedSymbolUsageFix
// FUS_K2_QUICKFIX_NAME: org.jetbrains.kotlin.idea.k2.codeinsight.fixes.replaceWith.DeprecatedSymbolUsageFix