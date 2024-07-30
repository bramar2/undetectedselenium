# Undetected Selenium

A Java implementation of undetected selenium from python library undetected-chromedriver and selenium-stealth.
## Disclaimer
UndetectedChromeDriver uses the Java Reflection API to access and modify private fields specifically on the following fields:
- ChromiumOptions.args  \[Map<String, Object>]
- ChromiumOptions.experimentalOptions \[Map<String, Object>]
- MutableCapabilities.caps \[List\<String>]

You can use SeleniumStealthOptions safely without reflection.
## Install

To use the library, you can clone the repository and just copy all the src code into your project. \
If you plan on using selenium-stealth, make sure to include the JS files located at src/main/resources/selenium-stealth/\*.js. \
The library tries to find selenium-stealth JS in classpath:selenium-stealth/\*.js. SeleniumStealth will fail (or work unexpectedly) if not included.

## Dependencies
Make sure these dependencies are installed and up to date:
1. Selenium (org.seleniumhq.selenium:selenium-java)
2. Gson (com.google.code.gson:gson)
3. Slf4j-simple (org.slf4j:slf4j-simple) \
Slf4j-simple is used to log ChromeDriver downloads and errors for ChromeDriver downloads. If you do not want to use Slf4j-simple, you can remove the usage of it in `me.bramar.undetectedselenium.DriverPatcher`.
4. Lombok (org.projectlombok:lombok)
## Drivers

ChromeDrivers downloaded through undetected-selenium will be stored at the following locations. \
Currently you cannot change this. But you may use your own chromedriver using `UndetectedChromeDriver.driverExecutable(new File("path/to/chromedriver"))` \
Windows: Appdata/Roaming/java_undetected_chromedriver \
Mac: Library/Application Support/java_undetected_chromedriver \
Linux: .local/share/java_undetected_chromedriver \
These are copied from undetected-chromedriver. Drivers are downloaded from: \
CFT: https://storage.googleapis.com/chrome-for-testing-public \
Legacy: https://chromedriver.storage.googleapis.com

## Usage

```java

import me.bramar.undetectedselenium.SeleniumStealthOptions;
import me.bramar.undetectedselenium.UndetectedChromeDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.edge.EdgeDriver;

public class Main {
    public static void main(String[] args) {
        // mimics undetected-chromedriver
        UndetectedChromeDriver uc = UndetectedChromeDriver.builder().build();
        // mimics selenium-stealth (that uses puppeteer stealth)
        ChromeDriver chromeDriver = new ChromeDriver();
        EdgeDriver edgeDriver = new ChromeDriver();
        SeleniumStealthOptions.getDefault().apply(driver);
        SeleniumStealthOptions.getDefault().apply(edgeDriver);
        // mimics both undetected-chromedriver and selenium-stealth
        // warning: may cause issues
        UndetectedChromeDriver uc2 = UndetectedChromeDriver.builder()
                .seleniumStealth(SeleniumStealthOptions.getDefault())
                .build();

        // selenium-stealth custom options
        SeleniumStealthOptions.builder()
                .languageArgument("de")
                .userAgent("CustomUserAgent")
                .renderer("Custom Renderer")
                .webglVendor("Custom WebGL Vendor")
                .platform("Custom Platform")
                .build()
                .apply(chromeDriver);
        // undetected-chromedriver custom options
        UndetectedChromeDriver uc3 = UndetectedChromeDriver.builder()
                .versionMain(125)
                .driverExecutable(driverExecutableFile) // chromedriver.exe
                .binaryExecutable(binaryExecutableFile) // chrome.exe
                .userDataDir("path/to/user/data/dir") // defaults to using temp folder
                .headless(true)
                .patchProvidedDriver(true) // defaults to false if you use custom driver, else it defaults to true
                .build();
        ChromeDriver underlyingDriverUsed = uc3.getDriver();
        
        // CF bypass that works sometimes but also slow (10s)
        // Use with caution.
        boolean success = uc3.cloudflareGet("https://nowsecure.nl");
        uc3.quit();
    }
}

```


Refer to `me.bramar.undetectedselenium.Test` for examples

## Credit
- Selenium-stealth/puppeteer-stealth JS files are from https://github.com/berstend/puppeteer-extra/tree/master/packages/puppeteer-extra-plugin-stealth/evasions
- UndetectedChromeDriver stealth techniques are partially from https://github.com/ultrafunkamsterdam/undetected-chromedriver
