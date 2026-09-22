import java.util.Arrays;

// Ripple demo target (§6): one deliberately broken method with a SILENT wrong result.
// Run:  javac -d out Demo.java && java -cp out Demo
public class Demo {

    public static void main(String[] args) {
        int[] data = {1, 2, 3, 4, 5};
        System.out.println("before:   " + Arrays.toString(data));
        reverse(data);
        System.out.println("after:    " + Arrays.toString(data));
        System.out.println("expected: [5, 4, 3, 2, 1]");
    }

    // BUG (mutation-before-read): overwrites a[i] before its original value
    // has been saved anywhere. No temp variable, no swap. No exception —
    // just a silently wrong array. The state trail will show a[1]'s original
    // value 2 being destroyed at iteration i=1, and position 3 keeping a
    // stale 4 instead of receiving 2.
    static void reverse(int[] a) {
        for (int i = 0; i < a.length / 2; i++) {
            a[i] = a[a.length - 1 - i];
        }
    }

    // ---- Static-only snippets for the Phase 2 (P1) hypothesizer demo ----
    // Never called from main. Exist so the inspection has cue-ready targets.

    // Off-by-one risk: '<=' against .length (would throw if executed).
    static int sumAllRisky(int[] xs) {
        int sum = 0;
        for (int i = 0; i <= xs.length; i++) {
            sum += xs[i];
        }
        return sum;
    }

    // Unguarded recursion: calls itself with no base case (would overflow if executed).
    static int ping(int n) {
        return ping(n - 1);
    }
}
