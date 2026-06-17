import java.util.Scanner;

public class Main {
    public static void main(String[] args) throws Exception {
        // TODO: Uncomment the code below to pass the first stage
         
         Scanner scanner = new Scanner(System.in);
         
         
         while(true){
            System.out.print("$ ");
            String input = scanner.nextLine();
            System.out.println(input + ": command not found");

         }
    }
}
