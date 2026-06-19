import java.io.File;
import java.util.Scanner;
import java.util.Arrays;
import java.util.HashSet;

public class Main {
    public static void main(String[] args) throws Exception {
         
         Scanner sc = new Scanner(System.in);
        String path = System.getenv("PATH");
        String pathDirs[] = path.split(File.pathSeparator);
         
         while (true) {
            System.out.print("$ ");
            String command = sc.nextLine();

            String cmd = command.indexOf(" ") == -1 ? command : command.substring(0, command.indexOf(" "));
            String rem = command.indexOf(" ") == -1 ? "" : command.substring(command.indexOf(" ")+1);

            if (cmd.equals("exit")) {
                break;
            } else if (cmd.equals("echo")) {
                System.out.println(rem);
            } else if (cmd.equals("type")) {
                System.out.println(type(rem));
            }
            else if(getExecutable(cmd) != null){
                Process process = Runtime.getRuntime().exec(command.split(" "));
                process.getInputStream().transferTo(System.out);
            }
            else {
                System.out.println(command + ": command not found");
            }
        }
         sc.close();
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
    public static String getExecutable(String cmd){
        String path = System.getenv("PATH");
        String pathDir[] = path.split(File.pathSeparator);

        for(String dir: pathDir){
            File file = new File(dir, cmd);
            if(file.exists() && file.canExecute()){
                return file.getAbsolutePath();
            }
        }
        return null;

    }
}
