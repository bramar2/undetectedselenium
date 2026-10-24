package io.github.bramar2.undetectedselenium;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.Getter;
import org.jspecify.annotations.Nullable;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Patches Chromedriver binaries to avoid bot detection.
 * <p>
 * This is achieved by modifying the executable and replacing
 * all occurences of {@code window.cdc}. This prevents automated
 * browser detection scripts from identifying the WebDriver instance.
 * <p>
 * Credit: Based on patcher.py from ultrafunkamsterdam/undetected-chromedriver
 */
@Getter
public class DriverPatcher {
    private static final Logger logger = Logger.getLogger(DriverPatcher.class.getName());
    private static final Pattern VERSION_PATTERN = Pattern.compile("[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}");

    private final File latestStable;
    private final String urlRepo = "https://storage.googleapis.com/chrome-for-testing-public";
    private final String zipName;
    private final File zipPath;
    private final File dataPath;
    private final boolean onlyStableBuilds;

    private int versionMain;
    private String versionFull;
    private File executablePath;
    private boolean customExePath;

    /**
     * Initializes a new {@code DriverPatcher} instance.
     * @param executablePath Path to an existing ChromeDriver binary to be patched.
     *                       If {@code null}, the patcher will attempt to download or reuse a ChromeDriver binary matching {@code versionMain}.
     * @param versionMain The major version of ChromeDriver to download (e.g., {@code 155}), if the {@code executablePath} is {@code null}.
     *                    Set to {@code 0} to automatically receive the latest build.
     * @param onlyStableBuilds If true, forces the patcher to only download stable builds, otherwise it will download the absolute latest build.
     * @throws IOException if an I/O error occurs while creating local directories and files, or while fetching data.
     */
    public DriverPatcher(@Nullable String executablePath,
                         int versionMain,
                         boolean onlyStableBuilds) throws IOException {
        this.onlyStableBuilds = onlyStableBuilds;

        String osName = System.getProperty("os.name", "").toLowerCase();
        String osArch = System.getProperty("os.arch", "").toLowerCase();
        boolean is64bit = osArch.contains("64");
        boolean arm64 = osArch.equals("aarch64") || osArch.equals("arm64");
        boolean x64 = osArch.equals("amd64") || osArch.equals("x86_64") || osArch.equals("x64");

        String exeName = osName.contains("windows") ? "chromedriver.exe" : "chromedriver";

        if(osName.contains("windows")) {
            this.dataPath = userPath("appdata/roaming/java_undetected_chromedriver");
        }else if(System.getenv().containsKey("LAMBDA_TASK_ROOT")) {
            this.dataPath = new File("tmp/java_undetected_chromedriver");
        }else if(osName.contains("linux")) {
            this.dataPath = userPath(".local/share/java_undetected_chromedriver");
        }else if(osName.contains("darwin") || osName.contains("mac")) {
            this.dataPath = userPath("Library/Application Support/java_undetected_chromedriver");
        }else {
            this.dataPath = userPath(".java_undetected_chromedriver");
        }

        if(osName.contains("windows")) {
            this.zipName = is64bit ? "chromedriver-win64.zip" : "chromedriver-win32.zip";
        }else if(osName.contains("linux") && arm64) {
            this.zipName = "chromedriver-linux-arm64.zip";
        }else if(osName.contains("linux") && x64) {
            this.zipName = "chromedriver-linux64.zip";
        }else if((osName.contains("darwin") || osName.contains("mac")) && arm64) {
            this.zipName = "chromedriver-mac-arm64.zip";
        }else if((osName.contains("darwin") || osName.contains("mac")) && x64) {
            this.zipName = "chromedriver-mac-x64.zip";
        }else {
            this.zipName = "chromedriver_%s.zip";
        }

        this.latestStable = new File(dataPath, "LATEST_STABLE");
        if(!this.dataPath.exists()) {
            Files.createDirectory(dataPath.toPath());
        }

        this.zipPath = new File(dataPath, "java_undetected");
        this.versionMain = versionMain;
        this.versionFull = null;

        if(executablePath != null) {
            this.executablePath = new File(executablePath);
            this.customExePath = true;
        }else {
            this.executablePath = new File(dataPath, "java_undetected_" + fetchReleaseNumber().substring(1) + "_" + exeName);
        }
    }

    private File userPath(String file) {
        return new File(new File(System.getProperty("user.home")), file);
    }

    /**
     * Checks if the ChromeDriver binary in {@code executablePath} is patched by detecting a pattern.
     * @return True if the pattern was detected in the binary.
     */
    public boolean isBinaryPatched() {
        return isBinaryPatched(null);
    }

    /**
     * Checks if the binary in {@code executablePath} is patched by detecting a pattern.
     * @param executablePath Path to an existing ChromeDriver binary to be checked. If {@code null}, it will default to the patcher's binary at {@code this.executablePath}
     * @return True if the pattern was detected in the binary.
     * @throws NullPointerException if {@code executablePath} is {@code null} and the patcher doesn't have an {@code executablePath}.
     */
    public boolean isBinaryPatched(File executablePath) throws NullPointerException {
        executablePath = executablePath == null ? this.executablePath : executablePath;
        if(executablePath == null) throw new NullPointerException("no executable found");
        String patternString = "undetected chromedriver";
        char[] pattern = patternString.toCharArray();
        int patternLen = pattern.length;
        int b;
        try(FileInputStream in = new FileInputStream(executablePath)) {
            byte[] buffer = new byte[4096];
            int len;
            a:
            while((len = in.read(buffer)) > 0) {
                b:
                for(int i = 0; i < len; i++) {
                    if(buffer[i] == pattern[0]) {
                        for(int offset = 1; offset < patternLen; offset++) {
                            if((i + offset) >= len) {
                                if((b = in.read()) == -1)
                                    return false;
                                if(b != pattern[offset])
                                    continue a;
                            }else if(buffer[i + offset] != pattern[offset]) {
                                i += offset;
                                continue b;
                            }
                        }
                        return true;
                    }
                }
            }
        }catch(IOException ignored) {}
        return false;
    }

    /**
     * Executes the patcher workflow.
     * @throws IOException if an I/O error occurs or an HTTP request failed.
     */
    public void auto() throws IOException {
        if(customExePath) {
            boolean patched = isBinaryPatched(executablePath);
            if(!patched) {
                patchExe();
            }
            return;
        }
        String release = fetchReleaseNumber();
        boolean isCached = release.startsWith("C");
        release = release.substring(1);
        this.versionMain = Integer.parseInt(release.split("\\.")[0]);
        if(isCached && !executablePath.exists() && !customExePath) {
            // No internet fail-safe (uses latest stable driver available)
            File driverFile = latestLocalFile();
            if(driverFile == null)
                throw new IOException("Failed to fetch chromedriver and no local drivers available");
            this.executablePath = driverFile;
            patchExe();
        }else {
            this.versionFull = release;
            if(this.executablePath.exists()) {
                if(isBinaryPatched())
                    return;
                Files.delete(this.executablePath.toPath());
            }
            logger.log(Level.INFO, "Downloading chromedriver " + release);
            File downloaded = fetchPackage();
            unzipPackage(downloaded);
            downloaded.delete();
            patchExe();
        }
    }

    /**
     * Patches the chromedriver binary at {@code executablePath}
     * @throws IOException if an I/O error occurs.
     */
    public void patchExe() throws IOException {
        StringBuilder contentBuilder = new StringBuilder();
        try(FileReader reader = new FileReader(this.executablePath, StandardCharsets.ISO_8859_1)) {
            char[] buffer = new char[8192];
            int len;
            while((len = reader.read(buffer)) > 0) {
                if(len == buffer.length) contentBuilder.append(buffer);
                else {
                    char[] slice = new char[len];
                    System.arraycopy(buffer, 0, slice, 0, len);
                    contentBuilder.append(slice);
                }
            }
        }
        String content = contentBuilder.toString();
        Matcher matcher = Pattern.compile("\\{window\\.cdc.*?;\\}").matcher(content);
        boolean found = false;
        while(matcher.find()) {
            found = true;
            int len = matcher.group().length();
            String toReplace = "{let a=\"undetected chromedriver\"}";
            String filler = "A".repeat(len - toReplace.length());
            toReplace = toReplace.replace("chromedriver", "chromedriver" + filler);
            content = content.substring(0, matcher.start()) + toReplace + content.substring(matcher.end());
        }
        content = content.replaceAll("window\\.cdc", "window.xyz");
        if(!found) logger.log(Level.WARNING, "Something went wrong patching the binary, could not find injection code block");

        try(FileWriter writer = new FileWriter(this.executablePath, StandardCharsets.ISO_8859_1)) {
            writer.write(content.toCharArray());
        }
    }

    private File latestLocalFile() {
        File[] files = dataPath.listFiles();
        File driverFile = null;
        int[] driver = null;

        if(files == null) return null;
        file_loop:
        for(File file : files) {
            try {
                String name = file.getName();
                if(!name.endsWith(".exe") || !name.contains("driver")) continue;
                Matcher matcher = VERSION_PATTERN.matcher(name);
                if(!matcher.find()) continue;
                String version = matcher.group();
                String[] s = version.split("\\.");
                int[] versionSplit = new int[4];
                for(int i = 0; i < s.length; i++)
                    versionSplit[i] = Integer.parseInt(s[i]);


                if(versionMain == 0 || versionSplit[0] == versionMain) {
                    if(driver != null) {
                        for(int i = 0; i < versionSplit.length; i++) {
                            if(driver[i] > versionSplit[i]) continue file_loop;
                        }
                    }
                    driverFile = file;
                    driver = versionSplit;
                }
            }catch(NumberFormatException|ArrayIndexOutOfBoundsException ignored) {}
        }
        return driverFile;
    }

    private void unzipPackage(File zipFile) throws IOException {
        if(this.executablePath.exists() && !this.executablePath.delete()) {
            Files.delete(this.executablePath.toPath());
        }
        try(FileSystem fs = FileSystems.newFileSystem(zipFile.toPath(), Collections.emptyMap())) {
            try(Stream<Path> stream = Files.walk(fs.getRootDirectories().iterator().next())) {
                for(Path p : stream.toList()) {
                    if(p.getFileName() != null && p.getFileName().toString().matches(".*chromedriver\\.exe")) {
                        Files.copy(p, this.executablePath.toPath());
                        return;
                    }
                }
            }
        }
    }

    private File fetchPackage() throws IOException {
        File tempFolder = new File(System.getProperty("java.io.tmpdir"));
        File outFile = new File(tempFolder, "ju-chromedriver-" + this.versionMain + "-" + new Random().nextInt(999_999) + ".zip");

        String driverType = zipName.replace(".zip", "")
                .replace("chromedriver-", "");
        // chromedriver-win32.zip -> win32
        String nameUrl = driverType + "/" + zipName;

        URL url = new URL("%s/%s/%s".formatted(urlRepo, versionFull, nameUrl));
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36");
        connection.connect();
        int res = connection.getResponseCode();
        if(res != 200) {
            connection.disconnect();
            return null;
        }
        try(FileOutputStream out = new FileOutputStream(outFile);
            InputStream in = connection.getInputStream()) {
            byte[] buffer = new byte[4096];
            int len;
            while((len = in.read(buffer)) > 0) {
                if(len == 4096) out.write(buffer);
                else {
                    byte[] slice = new byte[len];
                    System.arraycopy(buffer, 0, slice, 0, len);
                    out.write(slice);
                }
            }
        }
        connection.disconnect();
        return outFile;
    }

    private void fetchLatestStable() throws IOException {
        URL url = new URL("https://googlechromelabs.github.io/chrome-for-testing/last-known-good-versions.json");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36");
        connection.connect();
        String message;
        try(InputStream is = connection.getInputStream()) {
            message = new String(is.readAllBytes());
        }
        int res = connection.getResponseCode();
        connection.disconnect();
        if(res < 200 || res > 299) {
            throw new IOException(connection.getURL() + " Response code " + connection.getResponseCode() + " with message " + message.substring(0, Math.min(message.length(), 100)));
        }else {
            String latestStableBuild = JsonParser.parseString(message).getAsJsonObject()
                    .getAsJsonObject("channels").getAsJsonObject("Stable").get("version").getAsString();
            try(FileWriter writer = new FileWriter(latestStable)) {
                writer.write(latestStableBuild);
            }
        }
    }

    private String fetchFromCFT() throws IOException {
        if(versionMain != 0 && versionMain < 113) throw new IllegalArgumentException("version 112 and below is not supported");

        String latestStableBuild = null;
        fetchLatestStable();
        if(latestStable.exists())  {
            try(FileInputStream in = new FileInputStream(latestStable)) {
                latestStableBuild = new String(in.readAllBytes());
            }
        }
        int latestStableMajor = Integer.parseInt(latestStableBuild.split("\\.")[0]);

        URL url = new URL("https://googlechromelabs.github.io/chrome-for-testing/latest-versions-per-milestone.json");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36");
        connection.connect();
        String message;
        try(InputStream is = connection.getInputStream()) {
            message = new String(is.readAllBytes());
        }
        int res = connection.getResponseCode();
        connection.disconnect();
        if(res < 200 || res > 299) {
            throw new IOException(connection.getURL() + " Response code " + connection.getResponseCode() + " with message " + message.substring(0, Math.min(message.length(), 100)));
        }else {
            JsonObject json = JsonParser.parseString(message).getAsJsonObject();
            JsonObject milestones = json.getAsJsonObject("milestones");
            if(milestones.has(versionMain + "")) {
                if(onlyStableBuilds && versionMain > latestStableMajor) {
                    throw new IllegalArgumentException("version main (" + versionMain + ") does not have stable builds, meanwhile onlyStableBuilds is turned on.");
                }
                return milestones.getAsJsonObject(versionMain + "").get("version").getAsString();
            }else if(versionMain == 0) {
                if(onlyStableBuilds) {
                    return latestStableBuild;
                }else {
                    // not recommended since Canary/Dev/Beta builds can be unstable
                    int highestMainVersion = milestones.keySet().stream().mapToInt(Integer::parseInt).max().orElse(0);
                    if(highestMainVersion == 0)
                        throw new IllegalStateException("failed to get release number from CFT, please manually input the version for chrome driver");
                    return milestones.getAsJsonObject(highestMainVersion + "").get("version").getAsString();
                }
            }else {
                throw new IllegalArgumentException("version " + versionMain + " is not available");
            }
        }
    }

    private String fetchReleaseNumber() throws IOException {
        try {
            return "R" + fetchFromCFT();
        }catch(IOException e) {
            logger.log(Level.SEVERE, "Failed to fetch release number from CFT", e);
            if(versionMain == 0) {
                try(FileInputStream in = new FileInputStream(latestStable)) {
                    return "C" + new String(in.readAllBytes());
                }catch(IOException e2) {
                    throw (IOException) e2.initCause(e);
                }
            }else {
                File driverFile = latestLocalFile();
                if(driverFile == null) throw e;
                return "C" + VERSION_PATTERN.matcher(driverFile.getName()).group();
            }
        }
    }
}
