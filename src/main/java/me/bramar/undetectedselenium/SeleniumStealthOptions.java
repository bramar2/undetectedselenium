package me.bramar.undetectedselenium;

import com.google.gson.Gson;
import lombok.Builder;
import org.openqa.selenium.chromium.HasCdp;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

// ported from Python selenium_stealth (which has parts from Puppeteer)
// can be used with any chrome and chromium driver
@Builder
public class SeleniumStealthOptions {
    private static Gson gson = new Gson();
    private String userAgent;
    private String platform;
    @Builder.Default private String[] languages = new String[] {"en-US", "en"};
    @Builder.Default private String vendor = "Google Inc.";
    @Builder.Default private String webglVendor = "Intel Inc.";
    @Builder.Default private String renderer = "Intel Iris OpenGL Engine";
    @Builder.Default private boolean fixHairline = false;
    @Builder.Default private boolean runOnInsecureOrigins = false;

    public void apply(HasCdp driver) {
        evaluateEmpty(driver,
                "utils.js", "chrome.app.js", "iframe.contentWindow.js", "media.codecs.js",
                "navigator.permissions.js", "navigator.plugins.js", "navigator.webdriver.js", "window.outerdimensions.js");
        evaluateScript(driver, "chrome.runtime.js", runOnInsecureOrigins);
        evaluateScript(driver, "navigator.languages.js", (Object) languages);
        evaluateScript(driver, "navigator.vendor.js", vendor);
        evaluateScript(driver, "webgl.vendor.js", webglVendor, renderer);
        overrideUserAgent(driver);
    }
    public void overrideUserAgent(HasCdp driver) {
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
    public void evaluateEmpty(HasCdp driver, String... scriptFiles) {
        for(String scriptFile : scriptFiles) {
            evaluateScript(driver, scriptFile);
        }
    }
    public String evaluationString(String func, Object... args) {
        String argsString = "";
        for(int i = 0; i < args.length; i++) {
            Object arg = args[i];
            argsString += arg == null ? "undefined" : gson.toJson(arg);
            if(i != args.length - 1)
                argsString += ", ";
        }
        return "(%s)(%s)".formatted(func, argsString);

    }
    public void evaluateScript(HasCdp driver, String scriptName, Object... args) {
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
    public static SeleniumStealthOptions getDefault() {
        return builder().build();
    }
    public static class SeleniumStealthOptionsBuilder {
        // string example: "en-US,en"
        public SeleniumStealthOptionsBuilder languageArgument(String languageArg) {
            return languages(languageArg.split(","));
        }
    }
}
