import java.io.File;
import java.io.PrintStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Scanner;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.ArrayList;

public class Main {
    private static String currentDir = System.getProperty("user.dir");

    public static class Job {
        public int jobNumber;
        public long pid;
        public String status;
        public List<String> command;
        public Process process;

        public Job(int jobNumber, long pid, String status, List<String> command, Process process) {
            this.jobNumber = jobNumber;
            this.pid = pid;
            this.status = status;
            this.command = command;
            this.process = process;
        }
    }

    private static List<Job> jobsList = new ArrayList<>();

    public static void main(String[] args) throws Exception {
         
         Scanner sc = new Scanner(System.in);
        String path = System.getenv("PATH");
        String pathDirs[] = path.split(File.pathSeparator);
         
          while (true) {
            reapJobs(false);
            System.out.print("$ ");
            String command = sc.nextLine();

            List<Token> tokens = parseArguments(command);
            if (tokens.isEmpty()) {
                continue;
            }

            boolean isBackground = false;
            if (!tokens.isEmpty()) {
                Token lastToken = tokens.get(tokens.size() - 1);
                if (!lastToken.quoted && lastToken.text.equals("&")) {
                    isBackground = true;
                    tokens.remove(tokens.size() - 1);
                }
            }

            String redirectFile = null;
            String redirectErrFile = null;
            boolean appendOut = false;
            boolean appendErr = false;
            for (int i = 0; i < tokens.size(); i++) {
                Token t = tokens.get(i);
                if (!t.quoted && (t.text.equals(">") || t.text.equals("1>"))) {
                    if (i + 1 < tokens.size()) {
                        redirectFile = tokens.get(i + 1).text;
                        appendOut = false;
                        tokens.remove(i + 1);
                        tokens.remove(i);
                        i -= 1;
                    }
                } else if (!t.quoted && (t.text.equals(">>") || t.text.equals("1>>"))) {
                    if (i + 1 < tokens.size()) {
                        redirectFile = tokens.get(i + 1).text;
                        appendOut = true;
                        tokens.remove(i + 1);
                        tokens.remove(i);
                        i -= 1;
                    }
                } else if (!t.quoted && t.text.equals("2>")) {
                    if (i + 1 < tokens.size()) {
                        redirectErrFile = tokens.get(i + 1).text;
                        appendErr = false;
                        tokens.remove(i + 1);
                        tokens.remove(i);
                        i -= 1;
                    }
                } else if (!t.quoted && t.text.equals("2>>")) {
                    if (i + 1 < tokens.size()) {
                        redirectErrFile = tokens.get(i + 1).text;
                        appendErr = true;
                        tokens.remove(i + 1);
                        tokens.remove(i);
                        i -= 1;
                    }
                }
            }

            List<String> parsedArgs = new ArrayList<>();
            for (Token t : tokens) {
                parsedArgs.add(t.text);
            }

            String cmd = parsedArgs.get(0);

            PrintStream originalOut = System.out;
            PrintStream originalErr = System.err;
            FileOutputStream fos = null;
            PrintStream fileOut = null;
            FileOutputStream fosErr = null;
            PrintStream fileErr = null;
            try {
                if (redirectFile != null) {
                    File file = new File(redirectFile);
                    if (!file.isAbsolute() && !redirectFile.startsWith("/")) {
                        file = new File(currentDir, redirectFile);
                    }
                    File parent = file.getParentFile();
                    if (parent != null && !parent.exists()) {
                        parent.mkdirs();
                    }
                    fos = new FileOutputStream(file, appendOut);
                    fileOut = new PrintStream(fos);
                    System.setOut(fileOut);
                }

                if (redirectErrFile != null) {
                    File file = new File(redirectErrFile);
                    if (!file.isAbsolute() && !redirectErrFile.startsWith("/")) {
                        file = new File(currentDir, redirectErrFile);
                    }
                    File parent = file.getParentFile();
                    if (parent != null && !parent.exists()) {
                        parent.mkdirs();
                    }
                    fosErr = new FileOutputStream(file, appendErr);
                    fileErr = new PrintStream(fosErr);
                    System.setErr(fileErr);
                }

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
                } else if (cmd.equals("jobs")) {
                    reapJobs(true);
                }
                else if(getExecutable(cmd) != null){
                    ProcessBuilder pb = new ProcessBuilder(parsedArgs);
                    pb.directory(new File(currentDir));
                    if (redirectFile != null) {
                        File file = new File(redirectFile);
                        if (!file.isAbsolute() && !redirectFile.startsWith("/")) {
                            file = new File(currentDir, redirectFile);
                        }
                        if (appendOut) {
                            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(file));
                        } else {
                            pb.redirectOutput(ProcessBuilder.Redirect.to(file));
                        }
                    } else {
                        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                    }

                    if (redirectErrFile != null) {
                        File file = new File(redirectErrFile);
                        if (!file.isAbsolute() && !redirectErrFile.startsWith("/")) {
                            file = new File(currentDir, redirectErrFile);
                        }
                        if (appendErr) {
                            pb.redirectError(ProcessBuilder.Redirect.appendTo(file));
                        } else {
                            pb.redirectError(ProcessBuilder.Redirect.to(file));
                        }
                    } else {
                        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                    }
                    Process process = pb.start();
                    if (isBackground) {
                        int jobNum = 1;
                        if (!jobsList.isEmpty()) {
                            int maxJobNum = 0;
                            for (Job j : jobsList) {
                                if (j.jobNumber > maxJobNum) {
                                    maxJobNum = j.jobNumber;
                                }
                            }
                            jobNum = maxJobNum + 1;
                        }
                        long pid = process.pid();
                        System.out.println("[" + jobNum + "] " + pid);
                        jobsList.add(new Job(jobNum, pid, "Running", parsedArgs, process));
                    } else {
                        process.waitFor();
                    }
                }
                else {
                    System.out.println(cmd + ": command not found");
                }
            } finally {
                if (fileOut != null) {
                    fileOut.close();
                }
                if (fos != null) {
                    try { fos.close(); } catch (IOException e) {}
                }
                System.setOut(originalOut);

                if (fileErr != null) {
                    fileErr.close();
                }
                if (fosErr != null) {
                    try { fosErr.close(); } catch (IOException e) {}
                }
                System.setErr(originalErr);
            }
        }
         sc.close();
    }

    public static void reapJobs(boolean printRunning) {
        List<Job> nextJobsList = new ArrayList<>();
        int size = jobsList.size();
        for (int i = 0; i < size; i++) {
            Job job = jobsList.get(i);
            char marker = ' ';
            if (i == size - 1) {
                marker = '+';
            } else if (i == size - 2) {
                marker = '-';
            }
            if (job.process.isAlive()) {
                if (printRunning) {
                    String cmdStr = String.join(" ", job.command) + " &";
                    System.out.printf("[%d]%c  %-24s%s\n", job.jobNumber, marker, "Running", cmdStr);
                }
                nextJobsList.add(job);
            } else {
                String cmdStr = String.join(" ", job.command);
                System.out.printf("[%d]%c  %-24s%s\n", job.jobNumber, marker, "Done", cmdStr);
            }
        }
        jobsList = nextJobsList;
    }

    public static String type(String command){
        String commands[] = {"exit","type","echo","pwd","cd","jobs"};
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

    public static class Token {
        public String text;
        public boolean quoted;
        public Token(String text, boolean quoted) {
            this.text = text;
            this.quoted = quoted;
        }
    }

    public static List<Token> parseArguments(String input) {
        List<Token> args = new ArrayList<>();
        StringBuilder currentArg = new StringBuilder();
        boolean inSingleQuotes = false;
        boolean inDoubleQuotes = false;
        boolean hasContent = false;
        boolean wasQuoted = false;

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
                wasQuoted = true;
            } else if (inDoubleQuotes) {
                if (c == '\\') {
                    if (i + 1 < input.length()) {
                        char nextC = input.charAt(i + 1);
                        if (nextC == '"' || nextC == '\\') {
                            currentArg.append(nextC);
                            i++;
                            hasContent = true;
                        } else {
                            currentArg.append(c);
                            hasContent = true;
                        }
                    } else {
                        currentArg.append(c);
                        hasContent = true;
                    }
                } else if (c == '"') {
                    inDoubleQuotes = false;
                    hasContent = true;
                } else {
                    currentArg.append(c);
                    hasContent = true;
                }
                wasQuoted = true;
            } else {
                if (c == '\\') {
                    if (i + 1 < input.length()) {
                        currentArg.append(input.charAt(i + 1));
                        i++;
                        hasContent = true;
                    }
                    wasQuoted = true;
                } else if (c == '\'') {
                    inSingleQuotes = true;
                    wasQuoted = true;
                } else if (c == '"') {
                    inDoubleQuotes = true;
                    wasQuoted = true;
                } else if (Character.isWhitespace(c)) {
                    if (hasContent || currentArg.length() > 0) {
                        args.add(new Token(currentArg.toString(), wasQuoted));
                        currentArg.setLength(0);
                        hasContent = false;
                        wasQuoted = false;
                    }
                } else {
                    currentArg.append(c);
                    hasContent = true;
                }
            }
        }

        if (hasContent || currentArg.length() > 0) {
            args.add(new Token(currentArg.toString(), wasQuoted));
        }

        return args;
    }
}
