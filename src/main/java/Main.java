import java.io.File;
import java.util.Scanner;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.ArrayList;

public class Main {
    private static String currentDir = System.getProperty("user.dir");

    public static void main(String[] args) throws Exception {
         
         Scanner sc = new Scanner(System.in);
        String path = System.getenv("PATH");
        String pathDirs[] = path.split(File.pathSeparator);
         
          while (true) {
            System.out.print("$ ");
            String command = sc.nextLine();

            List<String> parsedArgs = parseArguments(command);
            if (parsedArgs.isEmpty()) {
                continue;
            }

            String cmd = parsedArgs.get(0);

            if (cmd.equals("exit")) {
                break;
            } else if (cmd.equals("echo")) {
                StringBuilder sb = new StringBuilder();
                for (int i = 1; i < parsedArgs.size(); i++) {
                    sb.append(parsedArgs.get(i));
                    if (i < parsedArgs.size() - 1) {
                        sb.append(" ");
                    }
                }
                System.out.println(sb.toString());
            } else if (cmd.equals("type")) {
                if (parsedArgs.size() > 1) {
                    System.out.println(type(parsedArgs.get(1)));
                } else {
                    System.out.println("type: missing argument");
                }
            } else if (cmd.equals("pwd")) {
                System.out.println(currentDir);
            } else if (cmd.equals("cd")) {
                String targetPath = parsedArgs.size() > 1 ? parsedArgs.get(1) : "~";
                if (targetPath.isEmpty() || targetPath.equals("~")) {
                    targetPath = System.getenv("HOME");
                    if (targetPath == null) {
                        targetPath = System.getProperty("user.home");
                    }
                }
                File newDir = new File(targetPath);
                if (!newDir.isAbsolute() && !targetPath.startsWith("/")) {
                    newDir = new File(currentDir, targetPath);
                }
                try {
                    if (newDir.exists() && newDir.isDirectory()) {
                        currentDir = newDir.getCanonicalPath();
                    } else {
                        System.out.println("cd: " + targetPath + ": No such file or directory");
                    }
                } catch (Exception e) {
                    System.out.println("cd: " + targetPath + ": No such file or directory");
                }
            }
            else if(getExecutable(cmd) != null){
                String[] cmdArray = parsedArgs.toArray(new String[0]);
                Process process = Runtime.getRuntime().exec(cmdArray, null, new File(currentDir));
                process.getInputStream().transferTo(System.out);
            }
            else {
                System.out.println(cmd + ": command not found");
            }
        }
         sc.close();
    }
    public static String type(String command){
        String commands[] = {"exit","type","echo","pwd","cd"};
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

    public static List<String> parseArguments(String input) {
        List<String> args = new ArrayList<>();
        StringBuilder currentArg = new StringBuilder();
        boolean inSingleQuotes = false;
        boolean hasContent = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            if (inSingleQuotes) {
                if (c == '\'') {
                    inSingleQuotes = false;
                    hasContent = true;
                } else {
                    currentArg.append(c);
                    hasContent = true;
                }
            } else {
                if (c == '\'') {
                    inSingleQuotes = true;
                } else if (Character.isWhitespace(c)) {
                    if (hasContent || currentArg.length() > 0) {
                        args.add(currentArg.toString());
                        currentArg.setLength(0);
                        hasContent = false;
                    }
                } else {
                    currentArg.append(c);
                    hasContent = true;
                }
            }
        }

        if (hasContent || currentArg.length() > 0) {
            args.add(currentArg.toString());
        }

        return args;
    }
}
