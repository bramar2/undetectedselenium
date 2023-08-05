package me.bramar.undetectedselenium;

import org.openqa.selenium.PageLoadStrategy;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeDriverService;
import org.openqa.selenium.chromium.ChromiumDriverLogLevel;

import java.io.IOException;
import java.util.Scanner;

class Test {
    private static final String testUrl = "https://nowsecure.nl/";
    public static void main(String[] args) throws IOException, ReflectiveOperationException {
        test3();
    }
    public static void w() {
        Scanner scanner = new Scanner(System.in);
        System.out.println("Press [ENTER] to quit");
        scanner.nextLine();
        scanner.close();
        System.out.println("Quitting");
    }
    public static void test1() throws IOException, ReflectiveOperationException {
        UndetectedChromeDriver driver = UndetectedChromeDriver.builder().userDataDir("C:\\Users\\marce\\AppData\\Local\\Temp\\temp-java-uc-1690730364883-28").build();
        driver.cloudflareGet(testUrl);
        w();
        driver.quit();
    }
    public static void test2() {
        ChromeDriver driver = new ChromeDriver();
        SeleniumStealthOptions.getDefault().apply(driver);
        driver.get(testUrl);
        w();
        driver.quit();
    }
    public static void test3() throws IOException, ReflectiveOperationException {
        UndetectedChromeDriver driver = UndetectedChromeDriver.builder()
                .pageLoadStrategy(PageLoadStrategy.NONE)
                .headless(false)
                .driverFromCFT(true)
                .versionMain(115)
                .autoOpenDevtools(true)
                .serviceBuilder(new ChromeDriverService.Builder().withSilent(true).withLogLevel(ChromiumDriverLogLevel.OFF))
                .seleniumStealth(SeleniumStealthOptions.getDefault()).build();
        System.out.println("Bypassed: " + driver.cloudflareGet(testUrl));
        w();
        driver.quit();
    }
    public static void cloudflareTest() throws IOException, ReflectiveOperationException, InterruptedException {
        int success = 0;
        int fail = 0;
        int attempts = 100;
        boolean headless = false;
        for(int i = 0; i < attempts; i++) {
            UndetectedChromeDriver driver = UndetectedChromeDriver.builder()
                    .pageLoadStrategy(PageLoadStrategy.NONE)
                    .headless(headless)
                    .driverFromCFT(true)
                    .versionMain(115)
                    .autoOpenDevtools(true)
                    .seleniumStealth(SeleniumStealthOptions.getDefault()).build();
            if(driver.cloudflareGet(testUrl)) success++;
            else fail++;
            Thread.sleep(2391);
            driver.quit();
            System.out.println((headless ? "Headless" : "Headful") + " Success: " + success + " Fail: " + fail + " | Success Rate: " + (success / attempts * 100d) + "% Attempts = " + (i+1) + "/" + attempts + " (" + ((i+1)/attempts*100d)+"%)");
        }
        System.out.println((headless ? "Headless" : "Headful") + " Success: " + success + " Fail: " + fail + " | Success Rate: " + (success / attempts * 100d) + "%");
    }
}
