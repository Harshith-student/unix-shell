import java.io.File;
import java.util.Scanner;

public class Main {
    public static void main(String[] args) throws Exception {
         
         Scanner scanner = new Scanner(System.in);
         
         
         while(true){
            System.out.print("$ ");
            String input = scanner.nextLine();
            if(input.equals("exit")){
                break;
            }
            else if(input.startsWith("type")){
                if(input.substring(5).equals("echo") || input.substring(5).equals("exit") || input.substring(5).equals("type")){
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
         scanner.close();
    }
    public static String type(String command){
        String commands[] = {"exit","type","echo"};
        String path = System.getenv("PATH");
        String pathDirs[] = path.split(File.pathSeparator);

        for(int i = 0; i<commands.length; i++){
            if(commands[i].equals(command)){
                return command+" is a shell builtin" ;
            }
        }

        for(int i = 0; i<pathDirs.length; i++){
            File file  = new File(pathDirs[i],command);
            if(file.exists() && file.canExecute()){
                return command+" is "+ file.getAbsolutePath();
            }
        }
        return command+": not found";
        
    }
}
