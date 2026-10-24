package io.github.bramar2.undetectedselenium;

import com.google.gson.Gson;
import lombok.Builder;
import lombok.Setter;
import org.openqa.selenium.chromium.HasCdp;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Stealth options to patch to an instance of Selenium ChromeDriver. <br>
 * Default: {@code SeleniumStealthOptions.getDefault()} or {@code new SeleniumStealthOptions()}
 * <p>
 * {@code userAgent}, default: unchanged (HeadlessChrome -> Chrome). <br>
 *      ex: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/83.0.4103.53 Safari/537.36
 * <p>
 * {@code platform}, default: unchanged. <br>
 *      ex: Win32
 * <p>
 * {@code languages}, default: en-US,en <br>
 *      ex: en,en-US,es
 * <p>
 * {@code vendor}, default: Google Inc.
 * <p>
 * {@code webglVendor}, default: Intel Inc.
 * <p>
 * {@code renderer}, default: Intel Iris OpenGL Engine.
 * <p>
 * {@code fixHairline}, default: false.
 * <p>
 * {@code runOnInsecureOrigins}, default: false.
 * <p>
 * Credit: Based on __init__.py from diprajpatra/selenium-stealth, puppeteer-extra-plugin-stealth from berstend/puppeteer-extra
 */
@Setter
public class SeleniumStealthOptions {
    private static Gson gson = new Gson();
    private String userAgent;
    private String platform;
    private String[] languages = new String[] {"en-US", "en"};
    private String vendor = "Google Inc.";
    private String webglVendor = "Intel Inc.";
    private String renderer = "Intel Iris OpenGL Engine";
    private boolean fixHairline = false;
    private boolean runOnInsecureOrigins = false;

    /**
     * Executes scripts to patch the driver.
     * @param driver ChromeDriver or ChromiumDriver instance (but more loosely anything that implements HasCdp).
     */
    public void apply(HasCdp driver) {
        evaluateEmpty(driver,
                "utils.js", "chrome.app.js", "iframe.contentWindow.js", "media.codecs.js",
                "navigator.permissions.js", "navigator.plugins.js", "navigator.webdriver.js", "window.outerdimensions.js");
        evaluateScript(driver, "chrome.runtime.js", runOnInsecureOrigins);
        evaluateScript(driver, "navigator.languages.js", (Object) languages);
        evaluateScript(driver, "navigator.vendor.js", vendor);
        evaluateScript(driver, "webgl.vendor.js", webglVendor, renderer);
        overrideUserAgent(driver);
        if(fixHairline)
            evaluateEmpty(driver, "hairline.fix.js");
    }

    /**
     * Returns a new instance of SeleniumStealthOptions with default options.
     * @return SeleniumStealthOptions with default options.
     */
    public static SeleniumStealthOptions getDefault() {
        return new SeleniumStealthOptions();
    }

    private void overrideUserAgent(HasCdp driver) {
        String userAgent = this.userAgent != null ? this.userAgent :
                String.valueOf(driver.executeCdpCommand("Browser.getVersion", Collections.emptyMap())
                        .get("userAgent"));
        userAgent = userAgent.replace("HeadlessChrome", "Chrome");
        Map<String, Object> override = new HashMap<>();
        override.put("userAgent", userAgent);
        // modified from original
        if(languages != null && languages.length != 0)
            override.put("acceptLanguage", String.join(",", languages));
        if(platform != null)
            override.put("platform", platform);
        driver.executeCdpCommand("Network.setUserAgentOverride", override);
    }

    private void evaluateEmpty(HasCdp driver, String... scriptFiles) {
        for(String scriptFile : scriptFiles) {
            evaluateScript(driver, scriptFile);
        }
    }

    private String evaluationString(String func, Object... args) {
        String argsString = "";
        for(int i = 0; i < args.length; i++) {
            Object arg = args[i];
            argsString += arg == null ? "undefined" : gson.toJson(arg);
            if(i != args.length - 1)
                argsString += ", ";
        }
        return "(%s)(%s)".formatted(func, argsString);
    }

    private void evaluateScript(HasCdp driver, String scriptName, Object... args) {
        String script = null;
        try(InputStream in = getClass().getClassLoader().getResourceAsStream("selenium-stealth/" + scriptName)) {
            if(in != null)
                script = new String(in.readAllBytes());
        }catch(NullPointerException|IOException ignored) {}
        if(script == null) throw new NullPointerException("Failed to get script from embedded JS file");
        driver.executeCdpCommand(
                "Page.addScriptToEvaluateOnNewDocument",
                Map.of("source", evaluationString(script, args)));
    }
}
