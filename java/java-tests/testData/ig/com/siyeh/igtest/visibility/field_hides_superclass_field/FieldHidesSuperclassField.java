class FieldHidesSuperclassField {
  String s;
  static String S;

}
class Sub extends FieldHidesSuperclassField {
  static String <warning descr="Field 's' hides field in superclass">s</warning>;
  static String S;
}
class Sub2 extends FieldHidesSuperclassField {
  String a;
  String <warning descr="Field 's' hides field in superclass">s</warning>;
}
<error descr="Cyclic inheritance involving 'A'">class A extends B</error> {
  String s = "";

  void f() { }
}

<error descr="Cyclic inheritance involving 'B'">class B extends A</error> {
  void f() { }
}