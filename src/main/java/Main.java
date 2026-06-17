import java.util.Scanner;

public class Main {
    public static void main(String[] args) throws Exception {
        // TODO: Uncomment the code below to pass the first stage
         
         Scanner scanner = new Scanner(System.in);
         
         
         while(true){
            System.out.print("$ ");
            String input = scanner.nextLine();
            if(input.equals("exit")){
                break;
            }
            else if(input.startsWith("type")){
                if(input.substring(5) == "echo" || input.substring(5) == "exit" || input.substring(5) == "type"){
                    System.out.println(input.substring(5)+" is a shell builtin");
                }
                else{
                    System.out.println(input.substring(5)+": not found");
                }
            }
            else if(input.startsWith("echo")){
                System.out.println(input.substring(5));
            }
            else{
                System.out.println(input + ": command not found");
            }
            
         }
    }
}
