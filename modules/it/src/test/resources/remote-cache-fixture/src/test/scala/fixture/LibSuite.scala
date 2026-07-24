package fixture

class LibSuite extends munit.FunSuite:
  test("hello") {
    assertEquals(Lib.hello("zipx"), "hello, zipx")
  }
