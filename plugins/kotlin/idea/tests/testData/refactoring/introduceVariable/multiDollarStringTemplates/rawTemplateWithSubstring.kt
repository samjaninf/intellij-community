// INPLACE_VARIABLE_NAME: s
fun foo(a: Int): String {
    val x = """_c$a
    :${a + 1}d_"""
    val y = "_$a:${a + 1}d_"
    val z = """_c$a:${a + 1}d_"""
    val u = "_c$a\n:${a + 1}d_"
    return $$"""ab<selection>c$$a
    :$${a + 1}d</selection>ef"""
}