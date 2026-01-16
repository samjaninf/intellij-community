from typing import overload, Any

class TestDefaults:
    @overload
    def foo(self, a: str) -> None: ... # OK

    @overload
    def foo(self, a: str, b: int) -> None: ...

    def foo(self, a: str, b: int = 1) -> None:
        pass

class TestWideSignature:
    @overload
    def foo(self, abcde: str) -> None: ...

    @overload
    def foo(self, x: int, y: int, *, z: bool = False) -> int: ...

    def foo(self, *args: Any, **kwargs: Any) -> Any:
        pass

class TestPositionalWidening:
    @overload
    def foo(self, x: int, y: str, /) -> int: ...

    @overload
    def foo(self, x: int, /, y: str) -> int: ...

    def foo(self, x: int, y: str) -> int:
        return x

class TestKwargsHandling:
    @overload
    def foo(self, *, a: bool) -> None: ...

    @overload
    def foo(self, *, b: str) -> None: ...

    def foo(self, *, a: bool = False, b: str | None = None) -> None:
        pass

class TestExtraArg:
    @overload
    def foo(self, a: str) -> str: ...

    @overload
    def <warning descr="Signature of this @overload-decorated method is not compatible with the implementation">foo</warning>(self, a: str, b: str) -> str: ...

    def foo(self, a: str) -> str:
        return a

class TestArgMissing:
    @overload
    def <warning descr="At least two @overload-decorated methods must be present"><warning descr="Signature of this @overload-decorated method is not compatible with the implementation">foo</warning></warning>(self, a: str) -> str: ...

    def foo(self, a: str, b: int) -> str:
        return a

class TestWrongKwargName:
    @overload
    def <warning descr="At least two @overload-decorated methods must be present"><warning descr="Signature of this @overload-decorated method is not compatible with the implementation">foo</warning></warning>(self, *, a: str) -> str: ...

    def foo(self, *, b: str) -> str:
        return b

class TestPositionalRestriction:
    @overload
    def <warning descr="Signature of this @overload-decorated method is not compatible with the implementation">foo</warning>(self, a: str) -> None: ...

    @overload
    def <warning descr="Signature of this @overload-decorated method is not compatible with the implementation">foo</warning>(self, a: int) -> None: ...

    def foo(self, a: Any, /) -> None:
        pass