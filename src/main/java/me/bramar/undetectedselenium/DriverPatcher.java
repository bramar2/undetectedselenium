package me.bramar.undetectedselenium;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

// ported from Python undetected-chromedriver
@Getter
public class DriverPatcher {
    private static final Logger logger = LoggerFactory.getLogger(DriverPatcher.class);
//    old
//    private static final String chromeLabsRepo = "https://edgedl.me.gvt1.com/edgedl/chrome/chrome-for-testing";
    private static final String chromeForTestingRepo = "https://storage.googleapis.com/chrome-for-testing-public";
    private final File latestStable;
    private String urlRepo = "https://chromedriver.storage.googleapis.com";
    private final String zipName;
    private File executablePath;
    private int versionMain;
    private String versionFull;
    private File dataPath;
    private boolean customExePath;
    private boolean isPosix;
    private final File zipPath;
    private final boolean chromeForTesting;
    private final boolean onlyStableBuilds;

    public DriverPatcher(@Nullable String executablePath,
                         int versionMain /* 0 = automatic */,
                         boolean chromeForTesting,
                         boolean onlyStableBuilds /* setting for chromeLabs=true and versionMain=0, otherwise no impact*/) throws IOException {
        this.chromeForTesting = chromeForTesting;
        this.onlyStableBuilds = onlyStableBuilds;
        if(chromeForTesting) urlRepo = chromeForTestingRepo;
        String osName = System.getProperty("os.name", "").toLowerCase();
        boolean is64bit = System.getProperty("os.arch").contains("64");
        isPosix = false;
        String exeName;
        String _zipName;
        if(osName.contains("windows")) {
            if(is64bit) {
                _zipName = "chromedriver_win64.zip";
            }else {
                _zipName = "chromedriver_win32.zip";
            }
            exeName = "chromedriver.exe";
            dataPath = userPath("appdata/roaming/java_undetected_chromedriver");
        }else {
            exeName = "chromedriver";
            if(osName.contains("nux")) {
                _zipName = "chromedriver_linux64.zip";
                dataPath = userPath(".local/share/java_undetected_chromedriver");
                isPosix = true;
            }else if(osName.contains("darwin") || osName.contains("mac")) {
                // FIXME: no check for ARM64 arch
                _zipName = chromeForTesting ? "chromedriver-mac-x64.zip" : "chromedriver_mac64.zip";
                dataPath = userPath("Library/Application Support/java_undetected_chromedriver");
                isPosix = true;
            }else {
                dataPath = userPath(".java_undetected_chromedriver");
                _zipName = "chromedriver_%s.zip";
            }
            if(System.getenv().containsKey("LAMBDA_TASK_ROOT")) {
                dataPath = new File("tmp/java_undetected_chromedriver");
            }
        }
        this.latestStable = new File(dataPath, "LATEST_STABLE");
        if(chromeForTesting) _zipName = _zipName.replace("_", "-");
        this.zipName = _zipName;
        if(!dataPath.exists()) {
            Files.createDirectory(dataPath.toPath());
        }
        if(!isPosix && executablePath != null && !executablePath.endsWith(".exe")) {
            executablePath += ".exe";
        }

        zipPath = new File(dataPath, "java_undetected");
        // removed code that makes it relative to program working dir

        if(executablePath != null) {
            this.executablePath = new File(executablePath);
            this.customExePath = true;
        }else {
            this.executablePath = new File(dataPath, "java_undetected_" + fetchReleaseNumber().substring(1) + "_" + exeName);
        }
        this.versionMain = versionMain;
        this.versionFull = null;
    }
    private File userPath(String file) {
        return new File(new File(System.getProperty("user.home")), file);
    }
    public boolean isBinaryPatched() {
        return isBinaryPatched(null);
    }

    public boolean isBinaryPatched(File executablePath) {
        executablePath = executablePath == null ? this.executablePath : executablePath;
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

    public void auto(int versionMain) throws IOException {
        if(customExePath) {
            boolean patched = isBinaryPatched(this.executablePath);
            if(!patched) {
                patchExe();
            }
            return;
        }
        String release = fetchReleaseNumber();
        boolean isCached = release.startsWith("C");
        release = release.substring(1);
        String[] dotSplit = release.split("\\.");
        this.versionMain = Integer.parseInt(dotSplit[0]);
        if(isCached && !this.executablePath.exists() && !customExePath) {
            // No internet fail-safe (uses latest stable driver available)
            File[] files = dataPath.listFiles();
            if(files == null)
                throw new IOException("Failed to fetch chrome driver and no local drivers available");
            File driverFile = null;
            int[] driver = null;
            main:
            for(File file : files) {
                try {
                    String name = file.getName();
                    if(!name.endsWith(".exe") || !name.contains("driver")) continue;
                    Matcher matcher = Pattern.compile("[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}")
                            .matcher(name);
                    if(!matcher.find()) continue;
                    String version = matcher.group();
                    String[] s = version.split("\\.");
                    int[] versionSplit = new int[4];
                    for(int i = 0; i < s.length; i++)
                        versionSplit[i] = Integer.parseInt(s[i]);


                    if(versionSplit[0] <= versionMain) {
                        if(driver != null) {
                            for(int i = 0; i < versionSplit.length; i++) {
                                if(driver[i] > versionSplit[i]) continue main;
                            }
                        }
                        driverFile = file;
                        driver = versionSplit;
                    }
                }catch(NumberFormatException|ArrayIndexOutOfBoundsException ignored) {}
            }
            if(driverFile == null)
                throw new IOException("Failed to fetch chromedriver and no local drivers available");
            this.executablePath = driverFile;
            patchExe();
            return;
        }else
            this.versionFull = release;

        if(versionMain != 0) {
            this.versionMain = versionMain;
        }
        if(this.executablePath.exists()) {
            if(isBinaryPatched())
                return;
            this.executablePath.delete();
        }
        logger.info("Downloading chromedriver " + release);
        File downloaded = fetchPackage();
        unzipPackage(downloaded);
        downloaded.delete();
        patchExe();
    }
    public void unzipPackage(File zipFile) throws IOException {
        if(this.executablePath.exists() && !this.executablePath.delete()) {
            Files.delete(this.executablePath.toPath());
        }
        try(FileSystem fs = FileSystems.newFileSystem(zipFile.toPath(), Collections.emptyMap())) {
            try(Stream<Path> stream = Files.walk(fs.getRootDirectories().iterator().next())) {
                for(Path p : stream.toList()) {
                    if(p.getFileName() != null && p.getFileName().toString().matches(".*chromedriver\\.exe")) {
                        Files.copy(p, executablePath.toPath());
                        return;
                    }
                }
            }
        }
    }
    public File fetchPackage() throws IOException {
        File tempFolder = new File(System.getProperty("java.io.tmpdir"));
        File outFile = new File(tempFolder, "ju-chromedriver-" + this.versionMain + "-" + new Random().nextInt(999_999) + ".zip");
        String nameUrl;
        if(chromeForTesting) {
            String driverType = zipName.replace(".zip", "")
                    .replace("chromedriver-", "");
            // chromedriver-win32.zip -> win32
            nameUrl = driverType + "/" + zipName;
        }else nameUrl = zipName;
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
    private String _fetchFromCFT() throws IOException {
        if(versionMain != 0 && versionMain < 113) throw new IllegalArgumentException("version 112 and below is not available from chromelabs/chrome for testing");

        IOException stableException = null;
        String latestStableBuild = null;
        if(onlyStableBuilds && versionMain == 0) {
            try {
                fetchLatestStable();
            }catch(IOException e) {stableException = e;}
            if(latestStable.exists())  {
                try(FileInputStream in = new FileInputStream(latestStable)) {
                    latestStableBuild = new String(in.readAllBytes());
                }catch(IOException e) {if(stableException == null) stableException = e;}
            }
        }



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
            if(milestones.has(versionMain+""))
                return milestones.getAsJsonObject(versionMain+"").get("version").getAsString();
            else if(versionMain == 0) {
                if(onlyStableBuilds) {
                    if(latestStableBuild == null)
                        throw new IOException("Failed to get latest stable build", stableException);
                    return latestStableBuild;
                }else {
                    // not recommended since Canary/Dev/Beta builds can be unstable
                    int highestMainVersion = milestones.keySet().stream().mapToInt(Integer::parseInt).max().orElse(0);
                    if(highestMainVersion == 0)
                        throw new IllegalStateException("failed to get release number from CFT, please manually input the version for chrome driver");
                    return milestones.getAsJsonObject(highestMainVersion + "").get("version").getAsString();
                }
            }else
                throw new IllegalArgumentException("version " + versionMain + " is not available");
        }
    }
    private String _fetch() throws IOException {
        String path = "/latest_release";
        if(versionMain != 0)
            path += "_" + versionMain;
        URL url = new URL(this.urlRepo + path.toUpperCase());
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
            throw new IOException("Response code " + connection.getResponseCode() + " with message " + message.substring(0, Math.min(message.length(), 100)));
        }else
            return message;
    }

    public String fetchReleaseNumber() throws IOException {
        try {
            return "R" + (chromeForTesting ? _fetchFromCFT() : _fetch());
        }catch(IOException e) {
            logger.warn("Failed to fetch release number from " + (chromeForTesting ? "CFT" : "chromedriver.storage.googleapis.com"), e);
            if(latestStable.exists()) {
                try(FileInputStream in = new FileInputStream(latestStable)) {
                    return "C"+new String(in.readAllBytes());
                }catch(IOException e2) {
                    try {
                        e2.initCause(e);
                    }catch(IllegalStateException ignored) {}
                    throw e2;
                }
            }else throw e;
        }
    }
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
        content = content.replaceAll("window\\.cdc", "window.arh");
        if(!found) logger.warn("Something went wrong patching the binary, could not find injection code block");

        try(FileWriter writer = new FileWriter(this.executablePath, StandardCharsets.ISO_8859_1)) {
            writer.write(content.toCharArray());
        }
    }
}
