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

            List<List<Token>> stages = new ArrayList<>();
            List<Token> currentStage = new ArrayList<>();
            for (Token t : tokens) {
                if (!t.quoted && t.text.equals("|")) {
                    stages.add(currentStage);
                    currentStage = new ArrayList<>();
                } else {
                    currentStage.add(t);
                }
            }
            stages.add(currentStage);

            if (stages.size() > 1) {
                int N = stages.size();
                List<CommandParsed> parsedStages = new ArrayList<>();
                boolean allValid = true;

                for (int i = 0; i < N; i++) {
                    CommandParsed cp = parseRedirectsAndArgs(stages.get(i));
                    if (cp.args.isEmpty()) {
                        System.out.println("Invalid pipeline command");
                        allValid = false;
                        break;
                    }
                    String cmd = cp.args.get(0);
                    if (!isBuiltin(cmd) && getExecutable(cmd) == null) {
                        System.out.println(cmd + ": command not found");
                        allValid = false;
                        break;
                    }
                    parsedStages.add(cp);
                }

                if (allValid) {
                    java.io.PipedOutputStream[] pipeOut = new java.io.PipedOutputStream[N - 1];
                    java.io.PipedInputStream[] pipeIn = new java.io.PipedInputStream[N - 1];
                    try {
                        for (int j = 0; j < N - 1; j++) {
                            pipeOut[j] = new java.io.PipedOutputStream();
                            pipeIn[j] = new java.io.PipedInputStream(pipeOut[j], 65536);
                        }
                    } catch (IOException e) {
                        System.out.println("Pipeline setup failed: " + e.getMessage());
                        continue;
                    }

                    List<Process> processesList = new ArrayList<>();
                    List<Thread> threadsList = new ArrayList<>();

                    for (int i = 0; i < N; i++) {
                        CommandParsed cp = parsedStages.get(i);
                        String cmd = cp.args.get(0);

                        java.io.InputStream stageIn;
                        if (i == 0) {
                            stageIn = System.in;
                        } else {
                            stageIn = pipeIn[i - 1];
                        }

                        java.io.OutputStream stageOut;
                        if (i == N - 1) {
                            if (cp.redirectFile != null) {
                                try {
                                    File file = new File(cp.redirectFile);
                                    if (!file.isAbsolute() && !cp.redirectFile.startsWith("/")) {
                                        file = new File(currentDir, cp.redirectFile);
                                    }
                                    File parent = file.getParentFile();
                                    if (parent != null && !parent.exists()) {
                                        parent.mkdirs();
                                    }
                                    stageOut = new FileOutputStream(file, cp.appendOut);
                                } catch (IOException e) {
                                    System.out.println("Redirection failed: " + e.getMessage());
                                    stageOut = System.out;
                                }
                            } else {
                                stageOut = System.out;
                            }
                        } else {
                            stageOut = pipeOut[i];
                        }

                        final int stageIndex = i;
                        final java.io.InputStream fStageIn = stageIn;
                        final java.io.OutputStream fStageOut = stageOut;

                        if (isBuiltin(cmd)) {
                            Thread t = new Thread(() -> {
                                try {
                                    PrintStream outStream = (fStageOut instanceof PrintStream)
                                        ? (PrintStream) fStageOut
                                        : new PrintStream(fStageOut);
                                    executeBuiltinWithRedirects(cp, outStream);
                                } finally {
                                    if (fStageOut != System.out) {
                                        try { fStageOut.close(); } catch (IOException e) {}
                                    }
                                    if (stageIndex > 0 && fStageIn != System.in) {
                                        try (java.io.InputStream in = fStageIn) {
                                            byte[] buf = new byte[4096];
                                            while (in.read(buf) != -1) {
                                                // discard remaining
                                            }
                                        } catch (IOException e) {
                                            // ignore
                                        }
                                    }
                                }
                            });
                            threadsList.add(t);
                            t.start();
                        } else {
                            try {
                                ProcessBuilder pb = new ProcessBuilder(cp.args);
                                pb.directory(new File(currentDir));
                                if (cp.redirectErrFile != null) {
                                    File file = new File(cp.redirectErrFile);
                                    if (!file.isAbsolute() && !cp.redirectErrFile.startsWith("/")) {
                                        file = new File(currentDir, cp.redirectErrFile);
                                    }
                                    if (cp.appendErr) {
                                        pb.redirectError(ProcessBuilder.Redirect.appendTo(file));
                                    } else {
                                        pb.redirectError(ProcessBuilder.Redirect.to(file));
                                    }
                                } else {
                                    pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                                }

                                Process process = pb.start();
                                processesList.add(process);

                                Thread copyStdin = new Thread(() -> {
                                    try (java.io.OutputStream dest = process.getOutputStream()) {
                                        byte[] buf = new byte[4096];
                                        int len;
                                        while ((len = fStageIn.read(buf)) != -1) {
                                            dest.write(buf, 0, len);
                                        }
                                        dest.flush();
                                    } catch (IOException e) {
                                        // ignore
                                    } finally {
                                        if (fStageIn != System.in) {
                                            try { fStageIn.close(); } catch (IOException e) {}
                                        }
                                    }
                                });
                                copyStdin.start();

                                Thread copyStdout = new Thread(() -> {
                                    try (java.io.InputStream src = process.getInputStream()) {
                                        byte[] buf = new byte[4096];
                                        int len;
                                        while ((len = src.read(buf)) != -1) {
                                            fStageOut.write(buf, 0, len);
                                        }
                                        fStageOut.flush();
                                    } catch (IOException e) {
                                        // ignore
                                    } finally {
                                        if (fStageOut != System.out) {
                                            try { fStageOut.close(); } catch (IOException e) {}
                                        }
                                    }
                                });
                                copyStdout.start();

                            } catch (IOException e) {
                                System.out.println("Failed to start process: " + cmd);
                            }
                        }
                    }

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
                        Process lastProcess = null;
                        if (!processesList.isEmpty()) {
                            lastProcess = processesList.get(processesList.size() - 1);
                        }
                        long pid = lastProcess != null ? lastProcess.pid() : 0;
                        System.out.println("[" + jobNum + "] " + pid);
                        List<String> fullCmd = new ArrayList<>();
                        for (int i = 0; i < N; i++) {
                            fullCmd.addAll(parsedStages.get(i).args);
                            if (i < N - 1) {
                                fullCmd.add("|");
                            }
                        }
                        jobsList.add(new Job(jobNum, pid, "Running", fullCmd, lastProcess));
                    } else {
                        for (Process p : processesList) {
                            try {
                                p.waitFor();
                            } catch (InterruptedException e) {
                                // ignore
                            }
                        }
                        for (Thread t : threadsList) {
                            try {
                                t.join();
                            } catch (InterruptedException e) {
                                // ignore
                            }
                        }
                    }
                }
                continue;
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
        reapJobs(printRunning, System.out);
    }

    public static void reapJobs(boolean printRunning, PrintStream out) {
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
            if (job.process != null && job.process.isAlive()) {
                if (printRunning) {
                    String cmdStr = String.join(" ", job.command) + " &";
                    out.printf("[%d]%c  %-24s%s\n", job.jobNumber, marker, "Running", cmdStr);
                }
                nextJobsList.add(job);
            } else {
                String cmdStr = String.join(" ", job.command);
                out.printf("[%d]%c  %-24s%s\n", job.jobNumber, marker, "Done", cmdStr);
            }
        }
        jobsList = nextJobsList;
    }

    public static class CommandParsed {
        public List<String> args = new ArrayList<>();
        public String redirectFile = null;
        public String redirectErrFile = null;
        public boolean appendOut = false;
        public boolean appendErr = false;
    }

    public static CommandParsed parseRedirectsAndArgs(List<Token> tokens) {
        CommandParsed cp = new CommandParsed();
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (!t.quoted && (t.text.equals(">") || t.text.equals("1>"))) {
                if (i + 1 < tokens.size()) {
                    cp.redirectFile = tokens.get(i + 1).text;
                    cp.appendOut = false;
                    tokens.remove(i + 1);
                    tokens.remove(i);
                    i -= 1;
                }
            } else if (!t.quoted && (t.text.equals(">>") || t.text.equals("1>>"))) {
                if (i + 1 < tokens.size()) {
                    cp.redirectFile = tokens.get(i + 1).text;
                    cp.appendOut = true;
                    tokens.remove(i + 1);
                    tokens.remove(i);
                    i -= 1;
                }
            } else if (!t.quoted && t.text.equals("2>")) {
                if (i + 1 < tokens.size()) {
                    cp.redirectErrFile = tokens.get(i + 1).text;
                    cp.appendErr = false;
                    tokens.remove(i + 1);
                    tokens.remove(i);
                    i -= 1;
                }
            } else if (!t.quoted && t.text.equals("2>>")) {
                if (i + 1 < tokens.size()) {
                    cp.redirectErrFile = tokens.get(i + 1).text;
                    cp.appendErr = true;
                    tokens.remove(i + 1);
                    tokens.remove(i);
                    i -= 1;
                }
            }
        }
        for (Token t : tokens) {
            cp.args.add(t.text);
        }
        return cp;
    }

    public static boolean isBuiltin(String cmd) {
        String commands[] = {"exit", "type", "echo", "pwd", "cd", "jobs"};
        for (String c : commands) {
            if (c.equals(cmd)) {
                return true;
            }
        }
        return false;
    }

    public static void executeBuiltin(List<String> parsedArgs, PrintStream out) {
        String cmd = parsedArgs.get(0);
        if (cmd.equals("echo")) {
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i < parsedArgs.size(); i++) {
                sb.append(parsedArgs.get(i));
                if (i < parsedArgs.size() - 1) {
                    sb.append(" ");
                }
            }
            out.println(sb.toString());
        } else if (cmd.equals("type")) {
            if (parsedArgs.size() > 1) {
                out.println(type(parsedArgs.get(1)));
            } else {
                out.println("type: missing argument");
            }
        } else if (cmd.equals("pwd")) {
            out.println(currentDir);
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
                    out.println("cd: " + targetPath + ": No such file or directory");
                }
            } catch (Exception e) {
                out.println("cd: " + targetPath + ": No such file or directory");
            }
        } else if (cmd.equals("jobs")) {
            reapJobs(true, out);
        }
    }

    public static void executeBuiltinWithRedirects(CommandParsed cp, PrintStream out) {
        PrintStream originalErr = System.err;
        FileOutputStream fos = null;
        PrintStream fileOut = null;
        FileOutputStream fosErr = null;
        PrintStream fileErr = null;
        try {
            PrintStream currentOut = out;
            if (cp.redirectFile != null) {
                File file = new File(cp.redirectFile);
                if (!file.isAbsolute() && !cp.redirectFile.startsWith("/")) {
                    file = new File(currentDir, cp.redirectFile);
                }
                File parent = file.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                fos = new FileOutputStream(file, cp.appendOut);
                fileOut = new PrintStream(fos);
                currentOut = fileOut;
            }

            if (cp.redirectErrFile != null) {
                File file = new File(cp.redirectErrFile);
                if (!file.isAbsolute() && !cp.redirectErrFile.startsWith("/")) {
                    file = new File(currentDir, cp.redirectErrFile);
                }
                File parent = file.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                fosErr = new FileOutputStream(file, cp.appendErr);
                fileErr = new PrintStream(fosErr);
                System.setErr(fileErr);
            }

            executeBuiltin(cp.args, currentOut);
        } catch (IOException e) {
            System.err.println("Redirection failed: " + e.getMessage());
        } finally {
            if (fileOut != null) {
                fileOut.close();
            }
            if (fos != null) {
                try { fos.close(); } catch (IOException e) {}
            }
            if (fileErr != null) {
                fileErr.close();
            }
            if (fosErr != null) {
                try { fosErr.close(); } catch (IOException e) {}
            }
            System.setErr(originalErr);
        }
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
            if (!command.endsWith(".exe")) {
                File fileExe = new File(pathDirs[i], command + ".exe");
                if (fileExe.exists() && fileExe.canExecute()) {
                    return command+" is "+ fileExe.getAbsolutePath();
                }
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
            if (!cmd.endsWith(".exe")) {
                File fileExe = new File(dir, cmd + ".exe");
                if (fileExe.exists() && fileExe.canExecute()) {
                    return fileExe.getAbsolutePath();
                }
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
