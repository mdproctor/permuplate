package io.quarkiverse.permuplate.example;

/**
 * Demo class for testing "Find Usages across permutation family" in the Permuplate plugin.
 *
 * How to test:
 * 1. Open Callable1.java
 * 2. Right-click the call() method → Find Usages
 * 3. Results should include ALL call() usages below (Callable2, Callable3, Callable4)
 * not just direct Callable1 usages.
 */
public class FindUsagesDemo {

    static void callableDemo() {
        Callable2 c2 = (o1, o2) -> System.out.println(o1 + ", " + o2);
        c2.call("a", "b");

        Callable3 c3 = (o1, o2, o3) -> System.out.println(o1 + ", " + o2 + ", " + o3);
        c3.call("a", "b", "c");

        Callable4 c4 = (o1, o2, o3, o4) -> System.out.println(o1 + ", " + o2 + ", " + o3 + ", " + o4);
        c4.call("a", "b", "c", "d");
    }

    static void useCallable(Callable2 fn, Object a, Object b) {
        fn.call(a, b);
    }
}
