fun foo() {
    <caret>A()
      .getB()
      .f1()
}

class A {
    fun getB() = B()
}

class B {
    fun f1() {}
}

// EXISTS: constructor A(), getB(), f1()
