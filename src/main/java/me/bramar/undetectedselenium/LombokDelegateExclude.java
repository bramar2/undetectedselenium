package me.bramar.undetectedselenium;

import org.openqa.selenium.WebDriver;

import java.util.Map;

// for lombok
class LombokDelegateExclude {
    public interface UndetectedChromeDriverExclude {
        void get(String url);
        void quit();
        Map<String, Object> executeCdpCommand(String commandName, Map<String, Object> parameters);
        WebDriver.TargetLocator switchTo();
    }
    public interface TargetLocatorExclude {
        WebDriver window(String nameOrHandle);
    }
}
