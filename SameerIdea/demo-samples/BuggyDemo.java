import java.util.ArrayList;
import java.util.List;

public class BuggyDemo {
    // TODO fix this later
    public static void main(String[] args) {
        System.out.println("Starting demo...");
        List<String> users = new ArrayList<>();
        // BUG 1: IndexOutOfBounds — list is empty
        System.out.println(users.get(0));

        // BUG 2: NullPointerException
        String name = null;
        System.out.println(name.length());

        try {
            Thread.sleep(5000);
        } catch (Exception e) {
            // swallowed
        }

        String password = "admin123"; // hardcoded secret
        System.out.println(password);
    }

    // Long method to trigger health warning (add 60+ lines here in demo)
    public void longMethod() {
        System.out.println("line");
        System.out.println("line");
        System.out.println("line");
    }
}
