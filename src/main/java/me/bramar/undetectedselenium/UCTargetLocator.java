package me.bramar.undetectedselenium;

import lombok.experimental.Delegate;
import org.openqa.selenium.WebDriver;

/*
Wraps the original Target locator and updates user agent when switchTo().window() is called
 */
class UCTargetLocator implements WebDriver.TargetLocator {
    private interface TargetLocatorExclude {
        WebDriver window(String nameOrHandle);
    }
    @Delegate(excludes = TargetLocatorExclude.class)
    private final WebDriver.TargetLocator original;
    private final UndetectedChromeDriver driver;

    UCTargetLocator(UndetectedChromeDriver driver) {
        this.driver = driver;
        this.original = driver.getDriver().switchTo();
    }

    @Override
    public WebDriver window(String nameOrHandle) {
        original.window(nameOrHandle);
        driver.updateUserAgent();
        return driver;
    }
}
