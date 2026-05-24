package org.example.personachat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** 文件日志，写到 软件目录/data/log.txt。全量追加，每次启动重置（保留本次运行的全部记录）。 */
public final class Log {
    private static final Path FILE = Path.of(System.getProperty("user.dir"), "data", "log.txt");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss");

    private Log() {}

    public static synchronized void w(String tag, String msg) {
        try {
            Files.createDirectories(FILE.getParent());
            String line = "[" + LocalDateTime.now().format(TS) + "] [" + tag + "] " + msg + System.lineSeparator();
            Files.writeString(FILE, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }

    /** 启动时调用：清空旧日志，开始记录本次运行。 */
    public static synchronized void reset() {
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, "===== PersonaChat 启动 " + LocalDateTime.now().format(TS) + " =====" + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception ignored) {
        }
    }

    public static void err(String tag, Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        w(tag, "EXCEPTION: " + sw);
    }

    /** 截断长字符串、把换行压成 \n，便于单行查看。 */
    public static String cut(String s, int n) {
        if (s == null) return "";
        s = s.replace("\n", "\\n");
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }
}
