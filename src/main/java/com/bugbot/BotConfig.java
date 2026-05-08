package com.bugbot;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class BotConfig {

    private static final Properties PROPS = new Properties();

    static {
        try (InputStream is = BotConfig.class.getClassLoader()
                .getResourceAsStream("config.properties")) {
            if (is == null) throw new RuntimeException("未找到 config.properties");
            PROPS.load(is);
        } catch (IOException e) {
            throw new RuntimeException("加载配置文件失败", e);
        }
    }

    public static String getBotToken() {
        return require("bot.token");
    }

    public static String getBotUsername() {
        return require("bot.username");
    }

    public static String getDbUrl() {
        return PROPS.getProperty("db.url", "jdbc:sqlite:bugs.db");
    }

    public static int getDailyReportMinuteOfDay() {
        return Integer.parseInt(PROPS.getProperty("report.minute_of_day", "540"));
    }

    public static int getDailyReportIntervalHours() {
        return Integer.parseInt(PROPS.getProperty("report.interval_hours", "24"));
    }

    private static String require(String key) {
        String val = PROPS.getProperty(key);
        if (val == null || val.trim().isEmpty() || val.startsWith("YOUR_")) {
            throw new RuntimeException("请在 config.properties 中填写 " + key);
        }
        return val.trim();
    }
}
