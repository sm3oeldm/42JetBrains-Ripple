package com.ripple.inspection

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

// Headless proof that the hypothesizer fires (no sandbox eyeballing needed).
class EdgeCaseInspectionTest : LightJavaCodeInsightFixtureTestCase() {

    override fun getTestDataPath() = ""

    fun testOffByOneFlagged() {
        myFixture.configureByText(
            "A.java",
            "class A { int sum(int[] xs) { int s = 0; " +
                "for (int i = 0; <warning>i<=xs.length</warning>; i++) { s += xs[i]; } " +
                "return s; } }"
        )
        myFixture.enableInspections(EdgeCaseInspection::class.java)
        myFixture.checkHighlighting()
    }

    fun testCleanLoopSilent() {
        myFixture.configureByText(
            "B.java",
            "class B { int sum(int[] xs) { int s = 0; " +
                "for (int i = 0; i < xs.length; i++) { s += xs[i]; } " +
                "return s; } }"
        )
        myFixture.enableInspections(EdgeCaseInspection::class.java)
        myFixture.checkHighlighting()
    }

    fun testUnguardedRecursionFlagged() {
        myFixture.configureByText(
            "C.java",
            "class C { int <warning>ping</warning>(int n) { return ping(n - 1); } }"
        )
        myFixture.enableInspections(EdgeCaseInspection::class.java)
        myFixture.checkHighlighting()
    }

    fun testGuardedRecursionSilent() {
        myFixture.configureByText(
            "D.java",
            "class D { int fib(int n) { if (n <= 1) { return n; } return fib(n-1) + fib(n-2); } }"
        )
        myFixture.enableInspections(EdgeCaseInspection::class.java)
        myFixture.checkHighlighting()
    }
}
