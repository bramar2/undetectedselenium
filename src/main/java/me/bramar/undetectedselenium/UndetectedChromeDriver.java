package me.bramar.undetectedselenium;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.ToNumberPolicy;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;
import lombok.experimental.Delegate;
import org.openqa.selenium.*;
import org.openqa.selenium.bidi.HasBiDi;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeDriverService;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.chromium.*;
import org.openqa.selenium.devtools.HasDevTools;
import org.openqa.selenium.html5.LocationContext;
import org.openqa.selenium.html5.WebStorage;
import org.openqa.selenium.interactions.Interactive;
import org.openqa.selenium.logging.HasLogEvents;
import org.openqa.selenium.mobile.NetworkConnection;
import org.openqa.selenium.net.PortProber;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.openqa.selenium.virtualauthenticator.HasVirtualAuthenticator;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.MalformedURLException;
import java.net.URL;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// ported from Python undetected-chromedriver, uses some reflection

public class UndetectedChromeDriver implements WebDriver, JavascriptExecutor, HasCapabilities, HasVirtualAuthenticator, Interactive, PrintsPage, TakesScreenshot,
        HasAuthentication, HasBiDi, HasCasting, HasCdp, HasDevTools, HasLaunchApp, HasLogEvents, HasNetworkConditions, HasPermissions, LocationContext, NetworkConnection, WebStorage {
    @Delegate(excludes = LombokDelegateExclude.UndetectedChromeDriverExclude.class)
    @Getter private final ChromeDriver driver;
    @Getter private final ChromeOptions options;
    @Getter private final boolean overrideUserAgent;
    private final Thread shutdownHook;
    private String otherUserAgent; // if overrideUserAgent is null, this is the user agent that will be set
    private final Random rnd = new Random();
    private UndetectedChromeDriver(
            ChromeDriverService service,
            ChromeOptions options,
            Thread shutdownHook, boolean overrideUserAgent) {
        this.shutdownHook = shutdownHook;
        this.overrideUserAgent = overrideUserAgent;
        this.driver = new ChromeDriver(service, this.options = options);
        this.otherUserAgent = String.valueOf(executeScript("return navigator.userAgent")).replace("Headless", "");
    }
    public void updateUserAgent() {
        if(overrideUserAgent) return;
        String ua = String.valueOf(otherUserAgent == null ? executeScript("return navigator.userAgent") : otherUserAgent).replace("Headless","");
        executeCdpCommand("Network.setUserAgentOverride",
                Map.of(
                        "userAgent", ua
                )
        );
    }
    @Override
    public void get(String url) {
        preGetScript();
        driver.get(url);
        postGetScript();
    }
    @Override
    public void quit() {
        driver.quit();
        if(shutdownHook != null) {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
            shutdownHook.start();
            try {
                shutdownHook.join();
            }catch(InterruptedException ignored) {}
        }
    }
    @Override
    public TargetLocator switchTo() {
        return new UCTargetLocator(this);
    }

    @Override
    public Map<String, Object> executeCdpCommand(String commandName, Map<String, Object> parameters) {
        if(commandName.equalsIgnoreCase("Network.setUserAgentOverride")) {
            if(overrideUserAgent) return Collections.emptyMap();
            if(parameters.containsKey("userAgent"))
                otherUserAgent = String.valueOf(Objects.requireNonNullElse(parameters.get("userAgent"), "undefined"));
        }
        return driver.executeCdpCommand(commandName, parameters);
    }

    private void delay() {
        delay(rnd.nextInt(500, 1050));
    }
    private void delay(int n) {
        try {
            Thread.sleep(n);
        }catch(InterruptedException ignored) {}
    }
    /**
     * Can bypass CF but isnt guaranteed to work (especially on VPN/Data center IPs)
     * Takes 10s (not recommended to use on a non-cloudflare site)
     * @param url CF-protected site
     * @return true if success (no checkbox detected), otherwise false
     */
    public boolean cloudflareGet(String url) {
        return cloudflareGet(url, 10000);
    }
    /**
     * @see UndetectedChromeDriver#cloudflareGet(String)
     * @param url CF-protected site
     * @param timeToWait wait time for CF to process in milliseconds
     * @return true if success (no checkbox detected), otherwise false
     */
    public boolean cloudflareGet(String url, int timeToWait) {
        try {
            new URL(url);
        }catch(MalformedURLException e) {
            throw new IllegalArgumentException(e);
        }
        get("http://google.com");
        Set<String> pre = getWindowHandles();
        driver.navigate().refresh();
        delay();
        preGetScript();
        String uuid = UUID.randomUUID().toString();
        String script = "window.open('%s', '%s');".formatted(url, uuid);
        driver.executeScript(script);
        try {
            // WebDriverWait may trigger Cloudflare
            Thread.sleep(timeToWait);
        }catch(InterruptedException ignored) {}
        close();
        Set<String> post = getWindowHandles();
        post.removeAll(pre);
        switchTo().window(post.iterator().next());
        return driver.findElements(By.id("cf-stage")).isEmpty();
    }

    private boolean _legacyCloudflareGet(String url) {
        try {
            new URL(url);
        }catch(MalformedURLException e) {
            throw new IllegalArgumentException(e);
        }
        get("about:blank");
        delay();
        preGetScript();
        String current = getWindowHandle();
        Set<String> handles = getWindowHandles();
        String script = "window.open('%s', '_blank');".formatted(url);
        driver.executeScript(script);
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofMillis(rnd.nextLong(3000,4000)));
        wait.until(ExpectedConditions.numberOfWindowsToBe(handles.size()+1));
        Set<String> newHandles = getWindowHandles();
        newHandles.removeAll(handles);
        String handle = newHandles.iterator().next();
        switchTo().window(handle);
        try {
            try {
                Thread.sleep(2139 + rnd.nextInt(49));
            }catch(InterruptedException ignored) {}
            driver.navigate().refresh();
            wait.until(ExpectedConditions.not(ExpectedConditions.presenceOfElementLocated(By.id("cf-stage"))));
        }catch(TimeoutException e) {
            switchTo().window(current);
            close();
            switchTo().window(handle);
            return cloudflareGet(url);
        }
        switchTo().window(current);
        delay();
        close();
        switchTo().window(handle);
        return true;
    }
    public void postGetScript() {
        updateUserAgent();
    }

    public void preGetScript() {
        updateUserAgent();
        if(executeScript("return navigator.webdriver") != null) {
            executeCdpCommand(
                    "Page.addScriptToEvaluateOnNewDocument",
                    Map.of("source", """
                                Object.defineProperty(window, "navigator", {
                                    value: new Proxy(navigator, {
                                        has: (target, key) => (key === "webdriver" ? false : key in target),
                                        get: (target, key) => key === "webdriver" ? false : typeof target[key] === "function"  ? target[key].bind(target) : target[key]
                                    })
                                });
                                """));
            executeCdpCommand("Page.addScriptToEvaluateOnNewDocument",
                    Map.of("source", """
                            Object.defineProperty(navigator, 'maxTouchPoints', {get: () => 1});
                            Object.defineProperty(navigator.connection, 'rtt', {get: () => 100});

                            // https://github.com/microlinkhq/browserless/blob/master/packages/goto/src/evasions/chrome-runtime.js
                            window.chrome = {
                                app: {
                                    isInstalled: false,
                                    InstallState: {
                                        DISABLED: 'disabled',
                                        INSTALLED: 'installed',
                                        NOT_INSTALLED: 'not_installed'
                                    },
                                    RunningState: {
                                        CANNOT_RUN: 'cannot_run',
                                        READY_TO_RUN: 'ready_to_run',
                                        RUNNING: 'running'
                                    }
                                },
                                runtime: {
                                    OnInstalledReason: {
                                        CHROME_UPDATE: 'chrome_update',
                                        INSTALL: 'install',
                                        SHARED_MODULE_UPDATE: 'shared_module_update',
                                        UPDATE: 'update'
                                    },
                                    OnRestartRequiredReason: {
                                        APP_UPDATE: 'app_update',
                                        OS_UPDATE: 'os_update',
                                        PERIODIC: 'periodic'
                                    },
                                    PlatformArch: {
                                        ARM: 'arm',
                                        ARM64: 'arm64',
                                        MIPS: 'mips',
                                        MIPS64: 'mips64',
                                        X86_32: 'x86-32',
                                        X86_64: 'x86-64'
                                    },
                                    PlatformNaclArch: {
                                        ARM: 'arm',
                                        MIPS: 'mips',
                                        MIPS64: 'mips64',
                                        X86_32: 'x86-32',
                                        X86_64: 'x86-64'
                                    },
                                    PlatformOs: {
                                        ANDROID: 'android',
                                        CROS: 'cros',
                                        LINUX: 'linux',
                                        MAC: 'mac',
                                        OPENBSD: 'openbsd',
                                        WIN: 'win'
                                    },
                                    RequestUpdateCheckStatus: {
                                        NO_UPDATE: 'no_update',
                                        THROTTLED: 'throttled',
                                        UPDATE_AVAILABLE: 'update_available'
                                    }
                                }
                            }

                            // https://github.com/microlinkhq/browserless/blob/master/packages/goto/src/evasions/navigator-permissions.js
                            if (!window.Notification) {
                                window.Notification = {
                                    permission: 'denied'
                                }
                            }

                            const originalQuery = window.navigator.permissions.query
                            window.navigator.permissions.__proto__.query = parameters =>
                                parameters.name === 'notifications'
                                    ? Promise.resolve({ state: window.Notification.permission })
                                    : originalQuery(parameters)

                            const oldCall = Function.prototype.call
                            function call() {
                                return oldCall.apply(this, arguments)
                            }
                            Function.prototype.call = call

                            const nativeToStringFunctionString = Error.toString().replace(/Error/g, 'toString')
                            const oldToString = Function.prototype.toString

                            function functionToString() {
                                if (this === window.navigator.permissions.query) {
                                    return 'function query() { [native code] }'
                                }
                                if (this === functionToString) {
                                    return nativeToStringFunctionString
                                }
                                return oldCall.call(oldToString, this)
                            }
                            // eslint-disable-next-line
                            Function.prototype.toString = functionToString
                            """));
        }
    }

    private static Map<String, Object> undotKey(String key, Object value) {
        if(key.contains(".")) {
            String[] split = key.split("\\.", 2);
            key = split[0];
            value = undotKey(split[1], value);
        }
        Map<String, Object> m = new HashMap<>();
        m.put(key, value);
        return m;
    }
    private static <K, V> void mergeNested(Map<K,V> a, Map<K,V> b) {
        for(K key : new HashSet<>(b.keySet())) {
            if(a.containsKey(key)) {
                if(a.get(key) instanceof Map && b.get(key) instanceof Map)
                    mergeNested((Map<Object, Object>)a.get(key), (Map<Object, Object>) b.get(key));
            }else a.put(key, b.get(key));
        }
    }
    private static Field argField;
    private static Field experimentalField;
    private static Field capsField;
    private static Map<String, Object> caps(MutableCapabilities cap) throws IllegalAccessException, IllegalArgumentException {
        return (Map<String, Object>) capsField.get(cap);
    }
    private static Map<String, Object> experimental(ChromeOptions options) throws IllegalAccessException, IllegalArgumentException {
        return (Map<String, Object>) experimentalField.get(options);
    }
    private static List<String> arguments(ChromeOptions options) throws IllegalAccessException, IllegalArgumentException {
        return (List<String>) argField.get(options);
    }

    public static UCBuilder builder() {
        return new UCBuilder();
    }

    @Accessors(fluent = true) @Setter @NoArgsConstructor(access = AccessLevel.PRIVATE)
    public static class UCBuilder {
        private static final Gson gson = new GsonBuilder()
                .setNumberToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
                .setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create();
        private String userDataDir;
        private String userAgent;
        private File driverExecutable;
        private File binaryExecutable;
        private int port = 0;
        private int versionMain = 0;
        private boolean legacyHeadless = false;
        private boolean headless = false;
        private boolean suppressWelcome = true;
        private boolean noSandbox = true;
        private boolean enableCdpEvents = false;
        private boolean autoOpenDevtools = false;
        private boolean patchProvidedDriver = false;
        private boolean driverFromCFT = false; // driver from chrome for testing (googlechromelabs.github.io)
        private boolean onlyStableCFT = true; // recommended to be true
        private Map<String, Object> desiredCapabilities = new HashMap<>();
        private ChromeDriverService.Builder serviceBuilder;
        private PageLoadStrategy pageLoadStrategy;
        private SeleniumStealthOptions seleniumStealth;
        private ChromeOptions options = new ChromeOptions();
        public UCBuilder desiredCapability(String key, String value) {
            desiredCapabilities.put(key, value);
            return this;
        }
        public UndetectedChromeDriver build()
                throws IllegalArgumentException, IOException, IllegalAccessException, NoSuchFieldException {
            if(argField == null || experimentalField == null || capsField == null) {
                argField = ChromiumOptions.class.getDeclaredField("args");
                experimentalField = ChromiumOptions.class.getDeclaredField("experimentalOptions");
                capsField = MutableCapabilities.class.getDeclaredField("caps");
                argField.setAccessible(true);
                experimentalField.setAccessible(true);
                capsField.setAccessible(true);
            }
            if(serviceBuilder == null) serviceBuilder = new ChromeDriverService.Builder();
            DriverPatcher patcher = null;
            if(driverExecutable == null) {
                patcher = new DriverPatcher(null, versionMain, driverFromCFT, onlyStableCFT);
                patcher.auto(0);
                driverExecutable = patcher.getExecutablePath();
            }else if(patchProvidedDriver) {
                patcher = new DriverPatcher(driverExecutable.getAbsolutePath(), 0, false, true);
                patcher.auto(0);
            }

            int debugPort = port != 0 ? port : PortProber.findFreePort();
            String debugHost = "127.0.0.1";
            options.addArguments(
                    "--remote-debugging-host=" + debugHost,
                    "--remote-debugging-port=" + debugPort);

            if(enableCdpEvents) {
                options.setCapability("goog:loggingPrefs",
                        Map.of("performance", "ALL",
                                "browser", "ALL"));
            }
            if(userDataDir != null)
                options.addArguments("--user-data-dir=" + userDataDir);
            List<String> args = arguments(options);
            String language = null;
            for(String arg : new ArrayList<>(args)) {
                if(arg.contains("headless")) {
                    headless = true;
                    args.remove(arg);
                }
                if(arg.contains("lang")) {
                    if(arg.contains("="))
                        language = arg.substring(arg.indexOf('=') + 1);
                    else
                        language = "en-US,en;q=0.9";
                }
                if(userDataDir == null && arg.contains("user-data-dir")) {
                    userDataDir = arg.substring(arg.indexOf('=') + 1);
                }
            }
            if(language == null)
                language = Locale.getDefault().toLanguageTag();
            options.addArguments("--lang=" + language);
            Thread shutdownHook = null;
            if(userDataDir == null) {
                File tempDir =
                        new File(new File(System.getProperty("java.io.tmpdir")), "temp-java-uc-" + System.currentTimeMillis() + "-" + new Random().nextInt(100));
                tempDir.mkdirs();
                Runtime.getRuntime().addShutdownHook(shutdownHook = new Thread(() -> {
                    for(int i = 0; i < 20; i++) {
                        try {
                            tryDelete(tempDir);
                            if(!tempDir.exists()) return;
                            Thread.sleep(500);
                        }catch(InterruptedException e) {
                            return;
                        }
                    }
                    throw new IllegalStateException("failed to delete temp directory " + tempDir);
                }));
                userDataDir = tempDir.getAbsolutePath();
                options.addArguments("--user-data-dir=" + userDataDir);
            }
            if(suppressWelcome) {
                options.addArguments("--no-default-browser-check", "--no-first-run");
            }
            if(noSandbox) {
                options.addArguments("--no-sandbox");
            }
            if(headless) {
                if((patcher != null && patcher.getVersionMain() < 108) || (patcher == null && legacyHeadless)) {
                    options.addArguments("--headless=chrome");
                }else {
                    options.addArguments("--headless=new");
                }
            }
            if(autoOpenDevtools)
                options.addArguments("--auto-open-devtools-for-tabs");

            options.addArguments("--window-size=1920,1080", "--start-maximized", "--log-level=0");
            options.setExperimentalOption("excludeSwitches", new String[] {"enable-automation"});
            File defaultPath = new File(userDataDir, "Default");
            File prefsFile = new File(defaultPath, "Preferences");
            // handlePreferences(userDataDir)
            // commented because untested code, may break
//            Map<String, Object> exp = experimental(options);
//            Map<String, Object> prefs = (Map<String, Object>) exp.get("prefs");
//            if(prefs != null) {
//                defaultPath.mkdirs();
//                Map<String, Object> undotPrefs = new HashMap<>();
//                prefs.forEach((k, v) -> mergeNested(undotPrefs, undotKey(k, v)));
//                if(prefsFile.exists()) {
//                    try(JsonReader reader = new JsonReader(new FileReader(prefsFile))) {
//                        Map<String, Object> map = gson.fromJson(reader,
//                                TypeToken.getParameterized(Map.class, String.class, Object.class).getType());
//                        mergeNested(undotPrefs, map);
//                    }
//                }
//                try(FileWriter writer = new FileWriter(prefsFile)) {
//                    writer.write(gson.toJson(undotPrefs));
//                }
//
//                exp.remove("prefs");
//            }
            // fix exit_type flag to prevent tab-restore
            if(prefsFile.exists()) {
                Map<String, Object> prefsFileMap;
                try(JsonReader reader = new JsonReader(new FileReader(prefsFile))) {
                    prefsFileMap = gson.fromJson(reader,
                            TypeToken.getParameterized(Map.class, String.class, Object.class).getType());
                }
                if(prefsFileMap.get("profile") instanceof Map<?,?> profileMap && profileMap.containsKey("exit_type")) {
                    profileMap = new HashMap<>(profileMap);
                    profileMap.remove("exit_type");
                    prefsFileMap.put("profile", profileMap);
                    try(FileWriter writer = new FileWriter(prefsFile)) {
                        gson.toJson(prefsFileMap, writer);
                    }
                }
            }

            if(desiredCapabilities == null || desiredCapabilities.isEmpty())
                desiredCapabilities = caps(options);
            if(pageLoadStrategy != null)
                options.setPageLoadStrategy(pageLoadStrategy);
            if(userAgent != null)
                options.addArguments("--user-agent=" + userAgent);
            if(binaryExecutable != null)
                options.setBinary(binaryExecutable);
            serviceBuilder.usingDriverExecutable(driverExecutable);
            UndetectedChromeDriver driver = new UndetectedChromeDriver(
                    serviceBuilder.build(),
                    options, shutdownHook, userAgent != null);
            if(userAgent != null)
                driver.getDriver().executeCdpCommand("Network.setUserAgentOverride", Map.of("userAgent", userAgent));
            if(seleniumStealth != null) seleniumStealth.apply(driver);
            return driver;
        }
    }
    public static void tryDelete(File directory) {
        File[] files = directory.listFiles();
        if(files != null) {
            for(File file : files) {
                if(file.isDirectory()) tryDelete(file);
                else file.delete();
            }
        }
        directory.delete();
    }
    // for debugging
    public static String abbreviate(Map<Object, String> seen, Object stringify) {
        if (stringify == null) {
            return "null";
        } else {
            StringBuilder value = new StringBuilder();
            if (stringify.getClass().isArray()) {
                value.append("[");
                value.append(Stream.of((Object[])stringify).map((item) -> abbreviate(seen, item)).collect(Collectors.joining(", ")));
                value.append("]");
            } else if (stringify instanceof Collection) {
                value.append("[");
                value.append(((Collection<?>)stringify).stream().map((item) -> abbreviate(seen, item)).collect(Collectors.joining(", ")));
                value.append("]");
            } else if (stringify instanceof Map) {
                value.append("{");
                value.append(((Map<?,?>)stringify).entrySet().stream().sorted(Comparator.comparing((entry) -> String.valueOf(entry.getKey()))).map((entry) -> String.format("%s: %s", entry.getKey(), abbreviate(seen, entry.getValue()))).collect(Collectors.joining(", ")));
                value.append("}");
            } else {
                value.append(stringify);
            }

            seen.put(stringify, value.toString());
            return value.toString();
        }
    }
}
