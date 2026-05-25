package org.example.personachat;

import javafx.animation.*;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

public class App extends Application {

    private String nameOther = "对方";   // 左侧（聊天窗左栏）显示名，导入人设后从档案标题取
    private String nameSelf = "我方";    // 右侧（聊天窗右栏）显示名
    private boolean personasReady = false;
    private static final int WINDOW = 40;          // 只喂模型最近 N 条
    private static final int ROUNDS = 10;          // 一批 10 个来回
    private static final int LEAVE_DELAY = 1;      // 有人说要离开后，再过几条触发事件旁白
    private static final String APP_VERSION = "1.1.2";
    private static final String APP_NAME = "LanHing-VirtualChat";
    private static final String UPDATE_API =
            "https://api.github.com/repos/lingyunalingyun/LanHing-VirtualChat-Program/releases/latest";
    private boolean seenGuide = false;             // 首次启动引导：true 表示已看过
    private boolean autoUpdate = true;             // true=新版自动下载安装；false=小版本只通知，大版本仍强制

    // ---------- API 服务商（OpenAI 兼容）----------
    private record Provider(String name, String url, String defaultModel) {}
    private static final List<Provider> PROVIDERS = List.of(
            new Provider("DeepSeek",             "https://api.deepseek.com/chat/completions",                                "deepseek-chat"),
            new Provider("OpenAI",               "https://api.openai.com/v1/chat/completions",                               "gpt-4o-mini"),
            new Provider("Moonshot Kimi",        "https://api.moonshot.cn/v1/chat/completions",                              "moonshot-v1-8k"),
            new Provider("Zhipu 智谱 GLM",        "https://open.bigmodel.cn/api/paas/v4/chat/completions",                    "glm-4-flash"),
            new Provider("Qwen 通义",             "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",       "qwen-turbo"),
            new Provider("Google Gemini",        "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions", "gemini-1.5-flash"),
            new Provider("OpenRouter",           "https://openrouter.ai/api/v1/chat/completions",                            "deepseek/deepseek-chat"),
            new Provider("SiliconFlow 硅基流动",  "https://api.siliconflow.cn/v1/chat/completions",                           "deepseek-ai/DeepSeek-V2.5"),
            new Provider("自定义",                "",                                                                          "")
    );
    private String apiProvider = "DeepSeek";
    private String apiUrl = "https://api.deepseek.com/chat/completions";

    private record Gen(String think, String msg, String leave, int gap, Conversation.Who next) {}
    private record Director(String transition, String next, int skipMinutes) {}

    // 模拟时间：内部用一个虚构起点，显示成「第N天 HH:mm」，和现实无关
    private static final LocalDateTime BASE = LocalDateTime.of(2000, 1, 1, 21, 0);
    private LocalDateTime simClock = BASE;
    private final Random rnd = new Random();
    private static final class Leave {
        final Conversation.Who who; final String activity; int remaining;
        Leave(Conversation.Who who, String activity, int remaining) {
            this.who = who; this.activity = activity; this.remaining = remaining;
        }
    }
    private Leave pendingLeave = null;
    private int sinceEvent = 99;   // 距上次事件多少条，防止刚跳完时间又立刻触发
    private static final java.util.regex.Pattern BYE =
            java.util.regex.Pattern.compile("晚安|睡了|睡觉|睡叭|睡吧|拜拜|好梦|明天见|不聊|去睡|该睡|下线|我走|去睡觉");

    private Persona plx, ly;
    private final Conversation convo = new Conversation();
    private final ChatCorpus corpus = new ChatCorpus();
    private final List<String[]> corrLeft = new ArrayList<>();   // [被纠正的话, 哪里不对]
    private final List<String[]> corrRight = new ArrayList<>();
    private final List<String> stylePrefer = new ArrayList<>(); // 整轮「肯定」总结出的风格方向
    private final List<String> styleAvoid = new ArrayList<>();  // 整轮「否定」总结出的要规避风格
    private int lastFeedbackIdx = 0;                            // 上次反馈时的时间线长度
    private volatile boolean feedbackBusy = false;
    private LocalDateTime lastMsgTime = BASE;       // 上一条已揭示消息的时间
    private Timeline clockTimer;                    // 连续流动的时钟（运行时一直 4× tick）
    private volatile boolean genInFlight = false;
    private long genEpoch = 0;   // 单调递增；事件触发/停止时 ++，让所有在飞的 gen 作废
    private int producedCount = 0, targetCount = 0;
    private Gen pendingGen;                         // 已生成、等时钟流到点才揭示的下一句
    private Conversation.Who pendingWho;
    private LocalDateTime pendingDue;
    /** 一次生成被拆成多条气泡时的排队：流到 due 就 poll 一条揭示。最后一条揭示后才轮 next。 */
    private final java.util.ArrayDeque<Pending> pendingQueue = new java.util.ArrayDeque<>();
    private record Pending(Conversation.Who who, String msg, String think,
                           LocalDateTime due, boolean isLast, Conversation.Who nextAfter,
                           String leaveAfter, String imagePath) {
        // 兼容旧构造，imagePath 默认空
        Pending(Conversation.Who who, String msg, String think, LocalDateTime due,
                boolean isLast, Conversation.Who nextAfter, String leaveAfter) {
            this(who, msg, think, due, isLast, nextAfter, leaveAfter, "");
        }
    }
    /** AI 输出的 [图:tag] 标记；占独立一行才算图行。 */
    private static final java.util.regex.Pattern IMAGE_MARK =
            java.util.regex.Pattern.compile("^\\s*\\[\\s*图\\s*(?::\\s*([^\\]]*))?\\]\\s*$");
    private final ImageLibrary imgLeft = new ImageLibrary("left");
    private final ImageLibrary imgRight = new ImageLibrary("right");
    private String runKey, runModel, runScenario;
    private Conversation.Who current = Conversation.Who.LEFT;
    private volatile boolean busy = false;
    private volatile boolean stopReq = false;

    // 控件
    private final PasswordField keyField = new PasswordField();
    private final TextField modelField = new TextField();
    private final TextField scenarioField = new TextField();
    private final ComboBox<String> starterCombo = new ComboBox<>();
    private final TextField interField = new TextField();
    private final VBox messagesBox = new VBox(2);
    private final ScrollPane scroll = new ScrollPane(messagesBox);
    private final Label status = new Label("加载语料中…");
    private final Label corpusLabel = new Label("");
    private final Label clockLabel = new Label("第1天 21:00");
    private final Label clockPeriod = new Label("晚上");
    private final TextField relationField = new TextField();   // 当前双方关系（数据持有，UI 不展示编辑器）
    private final Label relationChip = new Label();             // 只读 chip：显示当前关系
    private Label titleSub;
    private String runRelation = "";
    private String corpusPath = "", corpusLeft = "", corpusRight = "";   // 可选语料：jsonl 路径 + 双方 accountName
    private Button genBtn, contBtn, stopBtn, clearBtn, startBtn, leftBtn, rightBtn, narrBtn, posBtn, negBtn;

    // ---------- 时间线（多档迭代） ----------
    private String currentTimeline = "默认";
    private final ComboBox<String> timelineCombo = new ComboBox<>();
    private volatile boolean timelineSwitching = false;   // 防止程序设值时触发 onAction
    private String legacyRelationship = "";   // 老版本 config.json 里的 relationship，迁移用

    // ---------- 主题 ----------
    private String themeMode = "day";          // day / night / system
    private String themeAccent = "blue";       // blue / pink / green / yellow / red / purple / custom
    private String themeCustomColor = "#1C4FA0";   // 自定义色（hex）
    private VBox rootBox;                      // 顶层节点，主题类挂这里

    // ---------- 时钟流速 / 起始时刻 ----------
    private int flowSpeed = 4;                              // N× 流速：每真实秒推 N 个模拟秒
    private static final int[] SPEED_PRESETS = {1, 2, 4, 8, 16, 20};
    private final ComboBox<String> speedCombo = new ComboBox<>();
    private LocalTime startTimeOfDay = LocalTime.of(21, 0); // 起始时刻（每条时间线自己存）
    private final TextField startTimeField = new TextField();

    @Override
    public void start(Stage stage) {
        Log.reset();
        Log.w("APP", "启动");
        Stage splash = showSplash();
        BorderPane content = new BorderPane();
        content.getStyleClass().add("content-root");
        content.setTop(new VBox(buildTimelineBar(), buildScenarioBar(), buildRelationBar()));
        content.setCenter(buildCenter());
        content.setBottom(buildControls());

        migrateLegacyData();
        loadConfig();
        // 校准当前时间线（用户在外部删了目录、或第一次启动时）
        List<String> existing = listTimelines();
        if (!existing.contains(currentTimeline)) currentTimeline = existing.get(0);
        loadMeta();
        loadCorrections();
        loadStyle();
        loadPersonas();
        loadImageLibs();
        loadTimeline();
        refreshTimelineCombo();
        renderAll();
        renderClock();

        Path corpusFile = corpusPath.isBlank() ? null : Path.of(corpusPath);
        if (corpusFile != null && Files.exists(corpusFile) && !corpusLeft.isBlank() && !corpusRight.isBlank()) {
            corpusLabel.setText("语料：加载中…");
            corpus.loadAsync(corpusFile, corpusLeft, corpusRight, () -> {
                corpusLabel.setText("语料：已加载 " + corpus.size() + " 条");
                Log.w("CORPUS", "已加载 " + corpus.size() + " 条");
                if (!busy) status.setText(convo.isEmpty()
                        ? "写开场 → 选先说 → 开始 → 生成10轮"
                        : "已载入上次时间线，点「生成10轮」继续。轮到：" + name(current));
            });
        } else {
            corpusLabel.setText("语料：未配置（可选，纯人设也能跑）");
            if (!busy) status.setText(convo.isEmpty()
                    ? "导入左右人设 → 填当前关系 → 写开场 → 开始"
                    : "已载入上次时间线，点「生成10轮」继续。");
        }

        VBox root = new VBox(buildTitleBar(stage), content);
        root.getStyleClass().add("app-root");
        VBox.setVgrow(content, Priority.ALWAYS);
        rootBox = root;

        stage.initStyle(StageStyle.UNDECORATED);
        Scene scene = new Scene(root, 1180, 860);
        scene.getStylesheets().add(getClass().getResource("/style.css").toExternalForm());
        applyTheme();
        stage.setTitle(APP_NAME);
        try {
            // 多分辨率塞进 getIcons()，让 Windows 任务栏/Alt-Tab 自动挑合适的
            Image icon = new Image(getClass().getResourceAsStream("/icon.png"));
            stage.getIcons().add(icon);
        } catch (Exception e) {
            Log.w("APP", "加载图标失败：" + e.getMessage());
        }
        stage.setScene(scene);
        // splash 总时长 ~5s：fade-in 300 + 保持 4400 + fade-out 300，完事关闭 splash 再显示主窗
        javafx.animation.PauseTransition hold = new javafx.animation.PauseTransition(Duration.millis(4700));
        hold.setOnFinished(e -> {
            if (splash != null) {
                javafx.animation.FadeTransition fo = new javafx.animation.FadeTransition(Duration.millis(300), splash.getScene().getRoot());
                fo.setFromValue(1); fo.setToValue(0);
                fo.setOnFinished(ev -> { splash.close(); stage.show(); maybeShowFirstRunGuide(); });
                fo.play();
            } else {
                stage.show();
                maybeShowFirstRunGuide();
            }
        });
        hold.play();
    }

    private void maybeShowFirstRunGuide() {
        if (!seenGuide) {
            Platform.runLater(() -> showFirstRunGuide(false));
        }
        // 启动时静默检查更新；有新版本就强制弹窗
        autoCheckUpdate();
    }

    /** 启动后台检查更新；连不上 GitHub 静默跳过。 */
    private void autoCheckUpdate() {
        new Thread(() -> {
            try {
                java.net.http.HttpClient c = java.net.http.HttpClient.newBuilder()
                        .connectTimeout(java.time.Duration.ofSeconds(6))
                        .followRedirects(java.net.http.HttpClient.Redirect.NORMAL).build();
                java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(UPDATE_API))
                        .header("Accept", "application/vnd.github+json")
                        .header("User-Agent", "LanHing-VirtualChat-Updater")
                        .timeout(java.time.Duration.ofSeconds(8))
                        .GET().build();
                java.net.http.HttpResponse<String> resp = c.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
                JSONObject o = new JSONObject(resp.body());
                String tag = o.optString("tag_name", "").replaceFirst("^v", "");
                if (tag.isEmpty() || compareVersion(tag, APP_VERSION) <= 0) return;
                String name = o.optString("name", "v" + tag);
                String html = o.optString("html_url", "https://github.com/lingyunalingyun/LanHing-VirtualChat-Program/releases");
                String body = o.optString("body", "");
                String msiUrl = findMsiAssetUrl(o);
                boolean major = isMajorBump(tag, APP_VERSION);
                Log.w("UPDATE", "远端 v" + tag + "（本地 v" + APP_VERSION + "）major=" + major
                        + " autoUpdate=" + autoUpdate + " msi=" + (msiUrl == null ? "(无)" : "ok"));
                Platform.runLater(() -> {
                    if (major || autoUpdate) {
                        // 大版本必更新 / 开了自动更新 → 走带进度条的自动下载流程
                        showAutoUpdateDialog(tag, name, body, html, msiUrl, major);
                    } else {
                        // 小版本+用户关了自动更新 → 仅通知，提供链接
                        showUpdateNotice(tag, name, html, body);
                    }
                });
            } catch (Exception e) {
                Log.w("UPDATE", "自动检查失败（静默）：" + e.getMessage());
            }
        }, "update-autocheck").start();
    }

    /** 从 release JSON 的 assets[] 里找 .msi 资产的下载 URL。找不到返回 null。 */
    private static String findMsiAssetUrl(JSONObject release) {
        try {
            JSONArray arr = release.optJSONArray("assets");
            if (arr == null) return null;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject a = arr.getJSONObject(i);
                String n = a.optString("name", "");
                if (n.toLowerCase().endsWith(".msi")) return a.optString("browser_download_url", null);
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** 大版本判断：首段不等且远端 > 本地。 */
    private static boolean isMajorBump(String remote, String local) {
        String[] r = remote.split("\\.");
        String[] l = local.split("\\.");
        int rm = 0, lm = 0;
        try { rm = Integer.parseInt(r[0].replaceAll("[^0-9]", "")); } catch (Exception ignored) {}
        try { lm = Integer.parseInt(l[0].replaceAll("[^0-9]", "")); } catch (Exception ignored) {}
        return rm > lm;
    }

    /** 小版本+autoUpdate=off：单一"通知"弹窗，去浏览器看。 */
    private void showUpdateNotice(String tag, String name, String html, String body) {
        Dialog<Void> d = new Dialog<>();
        d.setTitle("有新版本可下载");
        d.setHeaderText("✨ 新版本 v" + tag + "（当前 v" + APP_VERSION + "）· 自动更新已关，仅通知");
        Label nm = new Label(name);
        nm.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");
        TextArea notes = new TextArea(body.isEmpty() ? "（无更新说明）" : body);
        notes.setEditable(false); notes.setWrapText(true);
        notes.setPrefRowCount(10); notes.setPrefColumnCount(56);
        VBox box = new VBox(8, nm, new Label("更新说明："), notes);
        box.setPrefWidth(540);
        d.getDialogPane().setContent(box);
        ButtonType go = new ButtonType("前往下载", ButtonBar.ButtonData.OK_DONE);
        ButtonType later = new ButtonType("稍后再说", ButtonBar.ButtonData.CANCEL_CLOSE);
        d.getDialogPane().getButtonTypes().addAll(go, later);
        themeDialog(d);
        d.setResultConverter(bt -> { if (bt == go) openInBrowser(html); return null; });
        d.showAndWait();
    }

    /** 自动更新弹窗：带 ProgressBar 拉取 MSI；下载完启动 msiexec + 退出本进程。 */
    private void showAutoUpdateDialog(String tag, String name, String body, String html,
                                      String msiUrl, boolean major) {
        Dialog<Void> d = new Dialog<>();
        d.setTitle(major ? "🔴 重要更新 · 必须升级" : "✨ 新版本");
        d.setHeaderText((major ? "🔴 重要更新 v" : "✨ 新版本 v") + tag + "（当前 v" + APP_VERSION + "）");
        Label nm = new Label(name);
        nm.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");
        TextArea notes = new TextArea(body.isEmpty() ? "（无更新说明）" : body);
        notes.setEditable(false); notes.setWrapText(true);
        notes.setPrefRowCount(8); notes.setPrefColumnCount(56);
        ProgressBar progress = new ProgressBar(0);
        progress.setPrefWidth(480);
        Label progressLbl = new Label(msiUrl == null
                ? "⚠️ 此次发布没有 .msi 资产，无法自动下载，请前往下载页手动安装。"
                : "点击「立即更新」开始下载 MSI 安装包");

        Button downloadBtn = new Button("立即更新");
        downloadBtn.getStyleClass().add("btn");
        Button openWebBtn = new Button("前往下载页");
        openWebBtn.getStyleClass().add("btn-ghost");
        openWebBtn.setOnAction(e -> openInBrowser(html));
        Button laterBtn = new Button(major ? "稍后（重启再提醒）" : "暂不更新");
        laterBtn.getStyleClass().add("btn-ghost");
        laterBtn.setOnAction(e -> d.close());
        if (msiUrl == null) downloadBtn.setDisable(true);
        downloadBtn.setOnAction(e -> {
            downloadBtn.setDisable(true);
            laterBtn.setDisable(true);
            new Thread(() -> doDownloadAndInstall(msiUrl, progress, progressLbl), "update-dl").start();
        });
        HBox btns = new HBox(8, downloadBtn, openWebBtn, laterBtn);
        VBox box = new VBox(10, nm, new Label("更新说明："), notes, progress, progressLbl, btns);
        box.setPrefWidth(540);
        d.getDialogPane().setContent(box);
        // 必须有 CLOSE 让 ESC 工作；隐藏
        d.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        Node closeNode = d.getDialogPane().lookupButton(ButtonType.CLOSE);
        if (closeNode != null) { closeNode.setVisible(false); closeNode.setManaged(false); }
        themeDialog(d);
        d.showAndWait();
    }

    /** 实际下载 + 安装：BodyHandlers.ofInputStream 边读边写文件并刷新进度。完成后启动 msiexec 并 Platform.exit。 */
    private void doDownloadAndInstall(String msiUrl, ProgressBar progress, Label statusLbl) {
        try {
            Platform.runLater(() -> statusLbl.setText("连接服务器…"));
            java.nio.file.Path tmp = java.nio.file.Files.createTempFile("LanHing-Update-", ".msi");
            java.net.http.HttpClient c = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(15))
                    .followRedirects(java.net.http.HttpClient.Redirect.NORMAL).build();
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(msiUrl))
                    .header("User-Agent", "LanHing-VirtualChat-Updater")
                    .timeout(java.time.Duration.ofMinutes(10))
                    .GET().build();
            java.net.http.HttpResponse<java.io.InputStream> resp = c.send(req,
                    java.net.http.HttpResponse.BodyHandlers.ofInputStream());
            long total = resp.headers().firstValueAsLong("Content-Length").orElse(-1);
            long downloaded = 0;
            try (java.io.InputStream in = resp.body();
                 java.io.OutputStream out = java.nio.file.Files.newOutputStream(tmp)) {
                byte[] buf = new byte[32 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    downloaded += n;
                    final long d = downloaded;
                    final long t = total;
                    Platform.runLater(() -> {
                        if (t > 0) {
                            progress.setProgress((double) d / t);
                            statusLbl.setText(String.format("下载中… %.1f / %.1f MB", d / 1048576.0, t / 1048576.0));
                        } else {
                            statusLbl.setText(String.format("下载中… %.1f MB", d / 1048576.0));
                        }
                    });
                }
            }
            Log.w("UPDATE", "下载完成 " + downloaded + " 字节 → " + tmp);
            Platform.runLater(() -> {
                progress.setProgress(1.0);
                statusLbl.setText("✅ 下载完成，正在启动安装程序…");
            });
            Thread.sleep(800);
            new ProcessBuilder("msiexec", "/i", tmp.toString()).start();
            // 让安装程序接管；本进程退出，释放文件占用
            Platform.runLater(() -> { saveTimeline(); saveMeta(); Platform.exit(); });
        } catch (Exception e) {
            Log.err("UPDATE", e);
            Platform.runLater(() -> statusLbl.setText("❌ 失败：" + e.getMessage() + "（可手动前往下载页）"));
        }
    }

    /** 启动 splash：无边框透明窗，居中 logo + 名字，渐显。 */
    private Stage showSplash() {
        try {
            Stage sp = new Stage(StageStyle.TRANSPARENT);
            ImageView logo = new ImageView(new Image(getClass().getResourceAsStream("/icon.png"), 180, 180, true, true));
            Label name = new Label(APP_NAME);
            name.setStyle("-fx-text-fill: white; -fx-font-size: 24px; -fx-font-weight: bold;" +
                    "-fx-font-family: 'Microsoft YaHei UI', 'Segoe UI', sans-serif;");
            Label sub = new Label("双 AI 对话模拟");
            sub.setStyle("-fx-text-fill: rgba(255,255,255,0.85); -fx-font-size: 13px;" +
                    "-fx-font-family: 'Microsoft YaHei UI', 'Segoe UI', sans-serif;");
            VBox box = new VBox(14, logo, name, sub);
            box.setAlignment(Pos.CENTER);
            box.setStyle("-fx-background-color: linear-gradient(to bottom right, #6A7BF0 0%, #79E08F 100%);" +
                    "-fx-background-radius: 20; -fx-padding: 36 56 36 56;" +
                    "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.35), 28, 0.2, 0, 8);");
            StackPane wrap = new StackPane(box);
            wrap.setStyle("-fx-background-color: transparent;");
            wrap.setPadding(new Insets(20));
            Scene sc = new Scene(wrap, 360, 360);
            sc.setFill(Color.TRANSPARENT);
            sp.setScene(sc);
            try { sp.getIcons().add(new Image(getClass().getResourceAsStream("/icon.png"))); } catch (Exception ignored) {}
            // 用主屏视觉范围算几何中心（centerOnScreen 在 TRANSPARENT+UNDECORATED 上不太稳）
            javafx.geometry.Rectangle2D vb = javafx.stage.Screen.getPrimary().getVisualBounds();
            sp.setX(vb.getMinX() + (vb.getWidth() - 360) / 2);
            sp.setY(vb.getMinY() + (vb.getHeight() - 360) / 2);
            sp.show();
            // 渐显
            box.setOpacity(0);
            javafx.animation.FadeTransition fi = new javafx.animation.FadeTransition(Duration.millis(300), box);
            fi.setFromValue(0); fi.setToValue(1);
            fi.play();
            return sp;
        } catch (Exception e) {
            Log.w("APP", "splash 失败：" + e.getMessage());
            return null;
        }
    }

    private double dragX, dragY;

    private Node buildTitleBar(Stage stage) {
        ImageView logo;
        try {
            logo = new ImageView(new Image(getClass().getResourceAsStream("/icon.png"), 22, 22, true, true));
            logo.getStyleClass().add("title-logo");
        } catch (Exception e) {
            logo = new ImageView();
        }
        Label title = new Label(APP_NAME);
        title.getStyleClass().add("title-text");
        titleSub = new Label("· 双 AI 对话模拟");
        titleSub.getStyleClass().add("title-sub");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button settings = new Button("⚙");
        settings.getStyleClass().add("win-btn");
        settings.setOnAction(e -> showSettingsDialog());
        Button theme = new Button("🎨");
        theme.getStyleClass().add("win-btn");
        theme.setOnAction(e -> showThemeDialog());
        Button min = new Button("—");
        min.getStyleClass().add("win-btn");
        min.setOnAction(e -> stage.setIconified(true));
        Button close = new Button("✕");
        close.getStyleClass().addAll("win-btn", "win-close");
        close.setOnAction(e -> { saveTimeline(); saveMeta(); Platform.exit(); });
        HBox bar = new HBox(8, logo, title, titleSub, spacer, settings, theme, min, close);
        bar.getStyleClass().add("title-bar");
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setOnMousePressed(e -> { dragX = e.getScreenX() - stage.getX(); dragY = e.getScreenY() - stage.getY(); });
        bar.setOnMouseDragged(e -> { stage.setX(e.getScreenX() - dragX); stage.setY(e.getScreenY() - dragY); });
        return bar;
    }

    // ---------- UI ----------

    private Label barLabel(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("bar-label");
        return l;
    }

    // 顶部 settingsBar 已删除 —— Key/模型/服务商现在都在 ⚙ 设置对话框里。

    /** 时间线栏：切换/新建/重命名/删除。 */
    private Node buildTimelineBar() {
        timelineCombo.setPrefWidth(180);
        timelineCombo.getStyleClass().add("combo");
        timelineCombo.setOnAction(e -> {
            if (timelineSwitching) return;
            String sel = timelineCombo.getValue();
            if (sel != null && !sel.equals(currentTimeline)) switchTimeline(sel);
        });
        Button create = new Button("新建");
        create.getStyleClass().add("btn-ghost");
        create.setOnAction(e -> createTimelineDialog());
        Button rename = new Button("重命名");
        rename.getStyleClass().add("btn-ghost");
        rename.setOnAction(e -> renameTimelineDialog());
        Button del = new Button("删除");
        del.getStyleClass().add("btn-ghost");
        del.setOnAction(e -> deleteTimelineDialog());
        HBox bar = new HBox(8, barLabel("时间线"), timelineCombo, create, rename, del);
        bar.getStyleClass().add("bar");
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    /** 当前关系栏：只读 chip，初始在创建时间线时填，之后由 AI 巡视对话自动更新。 */
    private Node buildRelationBar() {
        relationChip.textProperty().bind(relationField.textProperty().map(s ->
                s == null || s.isBlank() ? "（未设定 · 新建时间线时可指定）" : s));
        relationChip.getStyleClass().add("relation-chip");
        Label hint = new Label("🤖 AI 会随对话自动调整");
        hint.getStyleClass().add("status-label");
        HBox bar = new HBox(10, barLabel("当前关系"), relationChip, hint);
        bar.getStyleClass().add("bar");
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    private Node buildScenarioBar() {
        scenarioField.setPromptText("开场场景，如：高三那年的晚自习后，LX 决定向 HMD 表白…");
        scenarioField.getStyleClass().add("field");
        HBox.setHgrow(scenarioField, Priority.ALWAYS);
        starterCombo.getItems().addAll(nameOther, nameSelf);
        starterCombo.setValue(nameOther);
        starterCombo.getStyleClass().add("combo");
        startTimeField.setPromptText("21:00");
        startTimeField.setText(fmtHHmm(startTimeOfDay));
        startTimeField.setPrefWidth(60);
        startTimeField.getStyleClass().add("field");
        startTimeField.focusedProperty().addListener((o, was, is) -> {
            if (was && !is) {
                LocalTime t = parseHHmm(startTimeField.getText());
                if (t != null) {
                    startTimeOfDay = t;
                    saveMeta();
                } else {
                    startTimeField.setText(fmtHHmm(startTimeOfDay));   // 还原非法输入
                }
            }
        });
        startBtn = new Button("开始");
        startBtn.getStyleClass().add("btn");
        startBtn.setOnAction(e -> startTimeline());
        Button aiStartBtn = new Button("AI 开场");
        aiStartBtn.getStyleClass().add("btn");
        aiStartBtn.setOnAction(e -> startTimelineByAI());
        HBox bar = new HBox(barLabel("先说"), starterCombo, barLabel("起始时刻"), startTimeField, scenarioField, startBtn, aiStartBtn);
        bar.getStyleClass().add("bar");
        return bar;
    }

    /** 起始时刻锚到 BASE 这一天，让"第N天"计算照常工作。 */
    private LocalDateTime startMoment() {
        return LocalDateTime.of(BASE.toLocalDate(), startTimeOfDay);
    }

    private static String fmtHHmm(LocalTime t) {
        return String.format("%02d:%02d", t.getHour(), t.getMinute());
    }
    private static LocalTime parseHHmm(String s) {
        if (s == null) return null;
        s = s.strip();
        try {
            String[] p = s.split(":");
            if (p.length != 2) return null;
            int h = Integer.parseInt(p[0].trim());
            int m = Integer.parseInt(p[1].trim());
            if (h < 0 || h > 23 || m < 0 || m > 59) return null;
            return LocalTime.of(h, m);
        } catch (Exception e) {
            return null;
        }
    }

    private Node buildCenter() {
        messagesBox.setPadding(new Insets(8));
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("chat-scroll");
        HBox header = new HBox(colHeader("直出（发出去的话）"), colHeader("内心想法"));
        header.getStyleClass().add("col-header-bar");
        VBox center = new VBox(buildClockBar(), header, scroll);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        return center;
    }

    /** 对话框上方的时间流逝表。 */
    private Node buildClockBar() {
        Label icon = new Label("🕐");
        icon.getStyleClass().add("clock-icon");
        clockLabel.getStyleClass().add("clock-time");
        clockPeriod.getStyleClass().add("clock-period");
        for (int s : SPEED_PRESETS) speedCombo.getItems().add(s + "×");
        speedCombo.setValue(flowSpeed + "×");
        speedCombo.getStyleClass().addAll("combo", "clock-rate");
        speedCombo.setOnAction(e -> {
            String v = speedCombo.getValue();
            if (v == null) return;
            try {
                int n = Integer.parseInt(v.replace("×", "").trim());
                if (n != flowSpeed) {
                    flowSpeed = n;
                    savePrefs();
                    if (clockTimer != null) startClock();   // 运行中变速立即生效
                    Log.w("CLOCK", "流速改为 " + flowSpeed + "×");
                }
            } catch (NumberFormatException ignored) {}
        });
        Label rateLbl = new Label("流速");
        rateLbl.getStyleClass().add("clock-rate");
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        HBox bar = new HBox(8, icon, clockLabel, clockPeriod, sp, rateLbl, speedCombo);
        bar.getStyleClass().add("clock-bar");
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    /** 把流逝表刷成当前模拟时间（秒级，让它肉眼可见地在动）。 */
    private void renderClock() {
        clockLabel.setText(fmtTimeSec(simClock));
        clockPeriod.setText(period(simClock.getHour()));
    }
    private String fmtTimeSec(LocalDateTime dt) {
        long day = ChronoUnit.DAYS.between(BASE.toLocalDate(), dt.toLocalDate()) + 1;
        return String.format("第%d天 %02d:%02d:%02d", day, dt.getHour(), dt.getMinute(), dt.getSecond());
    }

    private Label colHeader(String text) {
        Label l = new Label(text);
        l.setMaxWidth(Double.MAX_VALUE);
        l.setAlignment(Pos.CENTER);
        l.getStyleClass().add("col-header");
        HBox.setHgrow(l, Priority.ALWAYS);
        return l;
    }

    private Node buildControls() {
        genBtn = new Button("生成10轮 ▶▶");
        genBtn.getStyleClass().add("btn");
        genBtn.setOnAction(e -> runMessages(ROUNDS * 2));
        contBtn = new Button("继续1句");
        contBtn.getStyleClass().add("btn-ghost");
        contBtn.setOnAction(e -> runMessages(1));
        stopBtn = new Button("停止");
        stopBtn.getStyleClass().add("btn-ghost");
        stopBtn.setDisable(true);
        stopBtn.setOnAction(e -> { stopReq = true; if (busy) stopRun("已手动停止。"); });
        clearBtn = new Button("清空");
        clearBtn.getStyleClass().add("btn-ghost");
        clearBtn.setOnAction(e -> clearTimeline());
        negBtn = new Button("👎否定");
        negBtn.getStyleClass().add("btn-ghost");
        negBtn.setOnAction(e -> feedbackRound(false));
        posBtn = new Button("👍肯定");
        posBtn.getStyleClass().add("btn-ghost");
        posBtn.setOnAction(e -> feedbackRound(true));

        interField.setPromptText("插一句话…");
        interField.getStyleClass().add("field");
        HBox.setHgrow(interField, Priority.ALWAYS);
        leftBtn = new Button("以" + nameOther);
        leftBtn.getStyleClass().add("btn-ghost");
        rightBtn = new Button("以" + nameSelf);
        rightBtn.getStyleClass().add("btn-ghost");
        narrBtn = new Button("旁白");
        narrBtn.getStyleClass().add("btn-ghost");
        leftBtn.setOnAction(e -> interject(Conversation.Who.LEFT));
        rightBtn.setOnAction(e -> interject(Conversation.Who.RIGHT));
        narrBtn.setOnAction(e -> interject(Conversation.Who.NARRATION));

        MenuButton imgBtn = new MenuButton("📷 发图");
        imgBtn.getStyleClass().add("btn-ghost");
        MenuItem imgLeftItem = new MenuItem();
        MenuItem imgRightItem = new MenuItem();
        Runnable refreshImgItems = () -> {
            imgLeftItem.setText("以 " + nameOther + " 发图（库 " + imgLeft.size() + "）");
            imgRightItem.setText("以 " + nameSelf + " 发图（库 " + imgRight.size() + "）");
            imgLeftItem.setDisable(imgLeft.isEmpty());
            imgRightItem.setDisable(imgRight.isEmpty());
        };
        refreshImgItems.run();
        imgBtn.setOnShowing(e -> refreshImgItems.run());
        imgLeftItem.setOnAction(e -> pickAndInsertImage(Conversation.Who.LEFT));
        imgRightItem.setOnAction(e -> pickAndInsertImage(Conversation.Who.RIGHT));
        imgBtn.getItems().addAll(imgLeftItem, imgRightItem);

        status.getStyleClass().add("status-label");

        HBox row1 = new HBox(8, genBtn, contBtn, stopBtn, clearBtn, negBtn, posBtn);
        row1.setAlignment(Pos.CENTER_LEFT);
        HBox row2 = new HBox(8, interField, leftBtn, rightBtn, narrBtn, imgBtn);
        row2.setAlignment(Pos.CENTER_LEFT);
        status.setMaxWidth(Double.MAX_VALUE);
        VBox box = new VBox(8, row1, row2, status);
        box.getStyleClass().add("controls");
        return box;
    }

    // ---------- 行为 ----------

    private void startTimeline() {
        if (!convo.isEmpty()) {
            Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                    "会清空当前时间线（纠正记录保留），确定从头开始？", ButtonType.OK, ButtonType.CANCEL);
            a.setHeaderText(null);
            themeDialog(a);
            if (a.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        }
        convo.clear();
        pendingLeave = null;
        simClock = startMoment();
        lastFeedbackIdx = 0;
        renderClock();
        messagesBox.getChildren().clear();
        String sc = scenarioField.getText().trim();
        if (!sc.isEmpty()) addBubble(convo.add(Conversation.Who.NARRATION, sc, "", stampNow()));
        current = nameOther.equals(starterCombo.getValue()) ? Conversation.Who.LEFT : Conversation.Who.RIGHT;
        saveTimeline();
        status.setText("开场就绪，点「生成10轮」让 " + name(current) + " 先开口");
    }

    /** AI 自己决定谁先开口 + 第一句说什么；和「开始」一样会清空当前时间线。 */
    private void startTimelineByAI() {
        if (busy) { alert("先停下当前运行"); return; }
        if (!personasReady) { alert("先导入双方人设"); return; }
        final String key = keyField.getText().trim();
        if (key.isEmpty()) { alert("请先填写 DeepSeek API Key 并保存"); return; }
        final String model = modelField.getText().trim().isEmpty() ? "deepseek-chat" : modelField.getText().trim();
        if (!convo.isEmpty()) {
            Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                    "会清空当前时间线（纠正/风格保留），让 AI 重新开场？", ButtonType.OK, ButtonType.CANCEL);
            a.setHeaderText(null);
            themeDialog(a);
            if (a.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        }
        convo.clear();
        pendingLeave = null;
        simClock = startMoment();
        lastFeedbackIdx = 0;
        renderClock();
        messagesBox.getChildren().clear();
        String sc = scenarioField.getText().trim();
        if (!sc.isEmpty()) addBubble(convo.add(Conversation.Who.NARRATION, sc, "", stampNow()));
        saveTimeline();
        savePrefs();

        feedbackBusy = true; setBusyUI(false);
        status.setText("AI 正在决定谁先开口…");
        final String relation = relationField.getText().trim();
        new Thread(() -> {
            try {
                String sys = "你是一段虚拟对话的开场导演。两个角色：\n"
                        + "- " + nameOther + "\n- " + nameSelf + "\n"
                        + (relation.isEmpty() ? "" : "他俩当前的关系/阶段：" + relation + "\n")
                        + (sc.isEmpty() ? "（用户没指定场景，请自行设定一个合理的当下）\n"
                                        : "用户给的开场场景：" + sc + "\n")
                        + "现在 " + fmtTimeSec(simClock) + "（" + period(simClock.getHour()) + "）。"
                        + "请你根据他俩的人设和关系判断这种场景下谁更可能先开口，并替他/她说出真实自然的第一句（就像在发微信，可以一次发1-3条，用换行分隔）。\n"
                        + "严格按下面三段输出，不要别的内容：\n"
                        + "【先说】" + nameOther + " 或 " + nameSelf + "\n"
                        + "【心理】此刻这个人真实的内心想法，第一人称、简短\n"
                        + "【消息】要发出去的话本身，每行=一条独立气泡；不要写名字前缀，不要写括号动作旁白";
                JSONArray msgs = new JSONArray();
                msgs.put(new JSONObject().put("role", "system").put("content", sys));
                msgs.put(new JSONObject().put("role", "user").put("content",
                        "【" + nameOther + " 人设】\n" + plx.skillText() + "\n\n【" + nameSelf + " 人设】\n" + ly.skillText()));
                String raw = DeepSeekClient.chat(apiUrl, key, model, msgs);
                String firstStr = section(raw, "【先说】", "【心理】");
                String think = clean(section(raw, "【心理】", "【消息】"));
                String msg = stripActions(clean(section(raw, "【消息】", null)));
                if (msg.isEmpty()) msg = stripActions(clean(raw));
                Conversation.Who first = firstStr.contains(nameSelf) ? Conversation.Who.RIGHT
                        : firstStr.contains(nameOther) ? Conversation.Who.LEFT : Conversation.Who.LEFT;
                Log.w("AISTART", "先说=" + name(first) + " 心理=" + Log.cut(think, 50) + " 消息=" + Log.cut(msg, 80));
                String[] parts = splitBurst(msg);
                if (parts.length == 0) { Platform.runLater(() -> alert("AI 没给出消息，请重试或用「开始」手动开场")); return; }
                final Conversation.Who fw = first;
                final String[] fp = parts;
                final String fthink = think;
                Platform.runLater(() -> {
                    LocalDateTime[] t = { simClock };
                    for (int i = 0; i < fp.length; i++) {
                        String th = (i == 0) ? fthink : "";
                        addBubble(convo.add(fw, fp[i], th, t[0].toString()));
                        if (i < fp.length - 1) t[0] = t[0].plusSeconds(5 + rnd.nextInt(20));
                    }
                    simClock = t[0];
                    lastMsgTime = t[0];
                    renderClock();
                    current = opposite(fw);
                    starterCombo.setValue(fw == Conversation.Who.LEFT ? nameOther : nameSelf);
                    saveTimeline();
                    status.setText("AI 开场完成：" + name(fw) + " 先发 " + fp.length + " 条 · 点「生成10轮」让 " + name(current) + " 接话");
                });
            } catch (Exception ex) {
                Log.err("AISTART", ex);
                Platform.runLater(() -> alert("AI 开场失败：" + ex.getMessage()));
            } finally {
                Platform.runLater(() -> { feedbackBusy = false; setBusyUI(busy); });
            }
        }, "ai-start").start();
    }

    private void clearTimeline() {
        if (busy) return;
        if (!convo.isEmpty()) {
            Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                    "清空当前聊天界面和时间线（纠正记录保留），确定？", ButtonType.OK, ButtonType.CANCEL);
            a.setHeaderText(null);
            themeDialog(a);
            if (a.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        }
        convo.clear();
        pendingLeave = null;
        sinceEvent = 99;
        simClock = startMoment();
        lastFeedbackIdx = 0;
        renderClock();
        messagesBox.getChildren().clear();
        current = nameOther.equals(starterCombo.getValue()) ? Conversation.Who.LEFT : Conversation.Who.RIGHT;
        saveTimeline();
        status.setText("已清空。写开场或直接生成都行。");
    }

    /** 开始连续运行：时钟一直 4× 流动，AI 自己决定每句在未来哪个时刻冒出来；发够 count 条才停。 */
    private void runMessages(int count) {
        if (busy) return;
        if (!personasReady) { alert("请先在上方「导入对方人设 / 导入我方人设」两份档案"); return; }
        runKey = keyField.getText().trim();
        if (runKey.isEmpty()) { alert("请先填写 DeepSeek API Key 并保存"); return; }
        runModel = modelField.getText().trim().isEmpty() ? "deepseek-chat" : modelField.getText().trim();
        runScenario = scenarioField.getText().trim();
        runRelation = relationField.getText().trim();
        savePrefs();

        busy = true; stopReq = false; setBusyUI(true);
        producedCount = 0; targetCount = count;
        lastMsgTime = simClock;
        pendingGen = null; pendingDue = null; pendingWho = current; genInFlight = false;
        pendingQueue.clear();
        Log.w("RUN", "开始连续运行 target=" + count + " 起点=" + fmtTimeSec(simClock) + " 先说=" + name(current));
        scheduleNext(current);   // 后台预生成第一句
        startClock();
    }

    /** 启动连续时钟：tick 间隔 = 1000ms / flowSpeed（每真实秒走 flowSpeed 个模拟秒）。 */
    private void startClock() {
        if (clockTimer != null) clockTimer.stop();
        double tickMs = Math.max(20, 1000.0 / Math.max(1, flowSpeed));
        clockTimer = new Timeline(new KeyFrame(Duration.millis(tickMs), e -> clockTick()));
        clockTimer.setCycleCount(Timeline.INDEFINITE);
        clockTimer.play();
    }

    /** 时钟每一拍：推进模拟时间；若已备好的气泡到点了就一条条揭示。 */
    private void clockTick() {
        simClock = simClock.plusSeconds(1);
        renderClock();
        if (!busy) return;
        // 排队里到点的气泡全揭出来（一拍可能跨过好几条）
        while (!pendingQueue.isEmpty() && !simClock.isBefore(pendingQueue.peek().due())) {
            revealOne(pendingQueue.poll());
            if (!busy) return;   // revealOne 里可能 stopRun
        }
    }

    /** 揭示一条已备好的气泡。 */
    private void revealOne(Pending p) {
        try {
            Log.w("REV", name(p.who()) + " due=" + fmtTimeSec(p.due())
                    + " img=" + (p.imagePath().isEmpty() ? "-" : p.imagePath())
                    + " text=" + Log.cut(p.msg(), 40));
        } catch (Throwable ignored) {}
        Conversation.Turn t = convo.add(p.who(), p.msg(), p.think(), p.due().toString(), p.imagePath());
        lastMsgTime = p.due();
        try { addBubble(t); }
        catch (Throwable ex) { Log.err("REV", ex); }
        saveTimeline();
        if (!p.isLast()) {
            status.setText("运行中… " + producedCount + "/" + targetCount + "（连发 · 时钟 " + fmtTimeSec(simClock) + "）");
            return;
        }
        // 这是这次 Gen 的最后一条气泡 —— 处理"离开"事件计时器
        boolean firedLeave = false;
        if (pendingLeave != null) {
            pendingLeave.remaining--;
            if (pendingLeave.remaining <= 0) {
                Conversation.Who lw = pendingLeave.who;
                String act = pendingLeave.activity;
                pendingLeave = null;
                fireLeaveEvent(lw, act);
                firedLeave = true;
            }
        } else if (p.leaveAfter() != null && !p.leaveAfter().isEmpty()) {
            pendingLeave = new Leave(p.who(), p.leaveAfter(), LEAVE_DELAY);
            Log.w("EVENT", name(p.who()) + " 表示要去 " + p.leaveAfter() + "（" + LEAVE_DELAY + " 条后触发）");
        }
        producedCount++;
        status.setText("运行中… " + producedCount + "/" + targetCount + "（时钟 " + fmtTimeSec(simClock) + "）");
        if (producedCount >= targetCount) { stopRun("发够 " + targetCount + " 条，已停。"); return; }
        if (!firedLeave) {
            current = p.nextAfter();
            scheduleNext(current);
        }
        // firedLeave 时 fireLeaveEvent 内部会 scheduleNext，这里就不重复了
    }

    /** "离开"事件到点：插入「X去Y了」旁白 + 按活动估个时长跳分钟 + 让 X 回来后接话。 */
    private void fireLeaveEvent(Conversation.Who who, String activity) {
        pendingQueue.clear();   // X 已经去做事了，已排队的 X 后续消息作废
        genEpoch++;             // 让任何在飞的 gen 回来时直接丢弃，避免拿旧 lastMsgTime 排到事件前
        LocalDateTime t = simClock.plusMinutes(1);
        Conversation.Turn evt = convo.add(Conversation.Who.NARRATION,
                name(who) + " 去" + activity + "了。", "", t.toString());
        addBubble(evt);
        int skip = estimateActivityDuration(activity);
        simClock = t.plusMinutes(skip);
        lastMsgTime = simClock;
        renderClock();
        Conversation.Turn after = convo.add(Conversation.Who.NARRATION,
                "（" + fmtSkipHuman(skip) + "后）", "", simClock.toString());
        addBubble(after);
        Log.w("EVENT", "触发：" + name(who) + " 去" + activity + " 跳 " + skip + " 分");
        saveTimeline();
        current = who;   // 回来的人先开口
        if (busy && !stopReq) scheduleNext(current);
    }

    /** 按活动类型估算停留分钟。 */
    private int estimateActivityDuration(String activity) {
        if (activity == null) return 30;
        String a = activity;
        if (a.contains("睡")) return 420 + rnd.nextInt(180);   // 7-10h
        if (a.contains("画")) return 30 + rnd.nextInt(60);     // 30-90 min
        if (a.contains("游戏") || a.contains("打游") || a.contains("局")) return 25 + rnd.nextInt(35);
        if (a.contains("吃") || a.contains("饭")) return 20 + rnd.nextInt(20);
        if (a.contains("洗") || a.contains("澡")) return 15 + rnd.nextInt(20);
        if (a.contains("课") || a.contains("上课") || a.contains("学习") || a.contains("自习")) return 60 + rnd.nextInt(60);
        if (a.contains("跑") || a.contains("运动") || a.contains("健身")) return 30 + rnd.nextInt(30);
        if (a.contains("买") || a.contains("逛") || a.contains("超市")) return 30 + rnd.nextInt(60);
        if (a.contains("看") && (a.contains("剧") || a.contains("电影") || a.contains("视频"))) return 60 + rnd.nextInt(60);
        if (a.contains("出门") || a.contains("路上")) return 30 + rnd.nextInt(30);
        return 20 + rnd.nextInt(40);
    }

    private static String fmtSkipHuman(int minutes) {
        if (minutes >= 60) return (minutes / 60) + " 小时" + (minutes % 60 == 0 ? "" : (minutes % 60) + " 分钟");
        return minutes + " 分钟";
    }

    /** 后台生成下一句，并按 AI 给的【隔】定起始时刻；再按换行拆成多条气泡排队（每条相隔 5-25 模拟秒）。 */
    private void scheduleNext(Conversation.Who who) {
        if (genInFlight) return;
        genInFlight = true;
        pendingWho = who;
        final String key = runKey, model = runModel, scenario = runScenario;
        final long myEpoch = genEpoch;
        new Thread(() -> {
            try {
                Gen g = generateOne(who, key, model, scenario);
                Platform.runLater(() -> {
                    genInFlight = false;
                    if (!busy) return;
                    if (myEpoch != genEpoch) {
                        // 事件触发/状态切换让这个 gen 过期了，扔掉，让下一句按新状态生成
                        Log.w("RUN", "丢弃过期 gen（epoch " + myEpoch + " < " + genEpoch + " · " + name(who) + "）");
                        if (busy && !stopReq) scheduleNext(current);
                        return;
                    }
                    if (g == null) { stopRun(stopReq ? "已停止。" : "模型连续返回空，已停。"); return; }
                    enqueueGen(g, who);
                });
            } catch (Exception ex) {
                Log.err("RUN", ex);
                Platform.runLater(() -> { genInFlight = false; if (busy) stopRun("生成失败：" + ex.getMessage()); });
            }
        }, "gen").start();
    }

    /** 把一次 Gen 拆成 1~N 条气泡入队；第 1 条延迟 = AI 给的【隔】，后续每条 5-25 模拟秒。 */
    private record Bubble(String text, String imagePath) {}
    private void enqueueGen(Gen g, Conversation.Who who) {
        try {
            Log.w("ENQ", "enter " + name(who) + " msg=" + Log.cut(g.msg(), 60));
            String[] parts = splitBurst(g.msg());
            Log.w("ENQ", "split → " + parts.length + " 段");
            if (parts.length == 0) { stopRun("生成出来全是空气泡，已停。"); return; }

            List<Bubble> bubbles = new ArrayList<>();
            ImageLibrary lib = imageLib(who);
            int imgsThisGen = 0;
            for (String s : parts) {
                java.util.regex.Matcher m = IMAGE_MARK.matcher(s);
                if (m.matches()) {
                    if (lib == null || lib.isEmpty()) {
                        Log.w("RUN", name(who) + " AI 输出 [图:] 但表情包库为空，丢弃：" + s);
                        continue;
                    }
                    if (imgsThisGen >= 2) {
                        Log.w("RUN", name(who) + " 一次发图>2 限流，丢弃：" + s);
                        continue;
                    }
                    String tag = m.group(1) == null ? "" : m.group(1).strip();
                    String picked = lib.selectByTag(tag);
                    if (picked == null) {
                        Log.w("RUN", name(who) + " 选图返回空，丢弃：" + s);
                        continue;
                    }
                    String altText = (tag.isEmpty() || tag.equals("?") || tag.equals("？")) ? "" : tag;
                    bubbles.add(new Bubble(altText, lib.relativePath(picked)));
                    imgsThisGen++;
                    Log.w("RUN", name(who) + " 发图 tag=" + (tag.isEmpty() ? "(空)" : tag) + " → " + picked);
                } else {
                    bubbles.add(new Bubble(s, ""));
                }
            }
            Log.w("ENQ", "bubbles=" + bubbles.size());
            if (bubbles.isEmpty()) { stopRun("生成出来全是空气泡，已停。"); return; }

            LocalDateTime t = lastMsgTime.plusMinutes(g.gap());
            for (int i = 0; i < bubbles.size(); i++) {
                boolean last = (i == bubbles.size() - 1);
                String think = (i == 0) ? g.think() : "";
                String leave = last ? g.leave() : null;
                Bubble b = bubbles.get(i);
                pendingQueue.add(new Pending(who, b.text(), think, t, last,
                        last ? g.next() : null, leave, b.imagePath()));
                if (!last) t = t.plusSeconds(5 + rnd.nextInt(20));
            }
            pendingDue = pendingQueue.peek().due();
            pendingGen = g;
            Log.w("RUN", "已备 " + name(who) + " 隔" + g.gap() + "分 共" + bubbles.size() + "条 起 → " + fmtTimeSec(pendingQueue.peek().due()));
            while (!pendingQueue.isEmpty() && !simClock.isBefore(pendingQueue.peek().due())) {
                revealOne(pendingQueue.poll());
                if (!busy) return;
            }
        } catch (Throwable ex) {
            Log.err("ENQ", ex);
            stopRun("enqueueGen 异常：" + ex.getClass().getSimpleName() + " " + ex.getMessage());
        }
    }

    /** 按换行拆 AI 的【消息】为多个非空气泡。strip 每行；空行跳过；都空则返回空数组。 */
    private static String[] splitBurst(String msg) {
        if (msg == null) return new String[0];
        String[] raw = msg.split("\\r?\\n+");
        List<String> out = new ArrayList<>();
        for (String r : raw) {
            String s = r.strip();
            if (!s.isEmpty()) out.add(s);
        }
        return out.toArray(new String[0]);
    }

    /** 停止运行：停时钟、清待发。 */
    private void stopRun(String msg) {
        if (clockTimer != null) clockTimer.stop();
        busy = false;
        pendingGen = null;
        pendingQueue.clear();
        genEpoch++;
        setBusyUI(false);
        status.setText(msg);
        saveTimeline();
        Log.w("RUN", "停止：" + msg + " 历史=" + convo.turns().size() + "条");
        if (producedCount > 0) checkRelationDrift();
    }

    private volatile boolean relationCheckBusy = false;

    /** AI 巡视：取最近对话片段判断当前关系是否需要更新。变了就 setText+saveMeta，否则静默。 */
    private void checkRelationDrift() {
        if (relationCheckBusy) return;
        if (!personasReady) return;
        String key = keyField.getText().trim();
        if (key.isEmpty()) return;
        String model = modelField.getText().trim().isEmpty() ? "deepseek-chat" : modelField.getText().trim();
        List<Conversation.Turn> ts = convo.turns();
        if (ts.size() < 6) return;
        relationCheckBusy = true;
        final String currentRel = relationField.getText().trim();
        final String url = apiUrl;
        new Thread(() -> {
            try {
                StringBuilder dlg = new StringBuilder();
                int from = Math.max(0, ts.size() - 30);
                for (int i = from; i < ts.size(); i++) {
                    dlg.append(name(ts.get(i).who())).append("：").append(ts.get(i).text()).append("\n");
                }
                String sys = "你是关系评估器。" + nameOther + " 和 " + nameSelf
                        + " 当前记录的关系是：「" + (currentRel.isEmpty() ? "(未设定)" : currentRel) + "」。"
                        + "下面是他俩最近的对话。基于这段对话，判断他俩此刻的关系/亲密度/状态是否发生了实质变化"
                        + "（比如：从生疏变熟、变近、变远、吵架、和好、表白、分手、有第三者出现等）。\n"
                        + "若有变化：只输出一行新的关系描述（不超过 30 字，例：「在一起 1 周，更黏了」「冷战中，互相不爱搭理」）。\n"
                        + "若没变化：只输出 SAME。\n"
                        + "只输出这一行，不要任何别的字。";
                JSONArray msgs = new JSONArray();
                msgs.put(new JSONObject().put("role", "system").put("content", sys));
                msgs.put(new JSONObject().put("role", "user").put("content", dlg.toString()));
                String raw = DeepSeekClient.chat(url, key, model, msgs).trim();
                String first = raw.split("\\r?\\n")[0].trim().replaceAll("^[\"「『]+|[\"」』]+$", "");
                if (first.isEmpty() || first.equalsIgnoreCase("SAME") || first.equals(currentRel)) {
                    Log.w("RELATION", "无变化（" + Log.cut(raw, 40) + "）");
                    return;
                }
                if (first.length() > 60) first = first.substring(0, 60);
                final String newRel = first;
                Platform.runLater(() -> {
                    relationField.setText(newRel);
                    saveMeta();
                    status.setText("🤖 关系已更新：" + newRel);
                    Log.w("RELATION", "更新：「" + Log.cut(currentRel, 30) + "」→「" + newRel + "」");
                });
            } catch (Exception e) {
                Log.w("RELATION", "检查失败：" + e.getMessage());
            } finally {
                relationCheckBusy = false;
            }
        }, "relation-check").start();
    }

    /** 处理"离开去做某事"事件：到点后插入事件旁白 + 导演时间过渡。 */
    private void handleLeave(Conversation.Who who, String leave, String key, String model) throws Exception {
        if (pendingLeave == null) {
            if (!leave.isEmpty()) {
                pendingLeave = new Leave(who, leave, LEAVE_DELAY);
                Log.w("EVENT", name(who) + " 表示要离开去：" + leave + "（" + LEAVE_DELAY + "条后触发）");
            }
            return;
        }
        pendingLeave.remaining--;
        if (pendingLeave.remaining > 0) return;
        Conversation.Who lw = pendingLeave.who;
        String act = pendingLeave.activity;
        pendingLeave = null;
        fireEvent(name(lw) + " 去" + act + "了。", key, model);
    }

    /** 道别死循环检测：最近 4 条非旁白里至少 3 条是晚安/睡/拜拜之类。 */
    private boolean windingDown() {
        int seen = 0, bye = 0;
        List<Conversation.Turn> ts = convo.turns();
        for (int i = ts.size() - 1; i >= 0 && seen < 4; i--) {
            if (ts.get(i).who() == Conversation.Who.NARRATION) continue;
            seen++;
            if (BYE.matcher(ts.get(i).text()).find()) bye++;
        }
        return seen >= 4 && bye >= 3;
    }

    /** 插入一条事件旁白，再让导演生成时间过渡并决定谁先开口。 */
    private void fireEvent(String eventText, String key, String model) throws Exception {
        Conversation.Turn evt = convo.add(Conversation.Who.NARRATION, eventText, "", stampNow());
        Platform.runLater(() -> addBubble(evt));
        Log.w("EVENT", "触发旁白：" + eventText);
        sinceEvent = 0;

        Director dir = runDirector(eventText, key, model);
        if (dir != null) {
            Log.w("DIRECTOR", "过渡=" + Log.cut(dir.transition(), 60) + " 先说=" + dir.next() + " 跳分钟=" + dir.skipMinutes());
            if (dir.skipMinutes() > 0) advance(dir.skipMinutes());
            if (!dir.transition().isBlank()) {
                Conversation.Turn tr = convo.add(Conversation.Who.NARRATION, dir.transition(), "", stampNow());
                Platform.runLater(() -> addBubble(tr));
            }
            if (nameOther.equals(dir.next())) current = Conversation.Who.LEFT;
            else if (nameSelf.equals(dir.next())) current = Conversation.Who.RIGHT;
        }
        Platform.runLater(this::renderClock);
        saveTimeline();
    }

    /** 旁白导演：事件发生后生成时间过渡，并决定谁先开口。 */
    private Director runDirector(String event, String key, String model) throws Exception {
        String sys = "你是一段恋爱故事的旁白导演。剧情里：" + event
                + " 请用一句简短旁白交代时间流逝或场景过渡（如“一局游戏后”“第二天早上”“过了好一会儿”），"
                + "并决定这之后谁先开口继续聊。严格按以下三段输出，不要别的内容：\n"
                + "【过渡旁白】这里写过渡旁白\n【先说】" + nameOther + " 或 " + nameSelf
                + "\n【跳分钟】这段时间过了多久，整数分钟（打一局游戏≈30，过一会儿≈20，睡到第二天早上≈540）";
        JSONArray msgs = new JSONArray();
        msgs.put(new JSONObject().put("role", "system").put("content", sys));
        StringBuilder ctx = new StringBuilder("最近的对话：\n");
        List<Conversation.Turn> ts = convo.turns();
        for (int i = Math.max(0, ts.size() - 10); i < ts.size(); i++) {
            ctx.append(name(ts.get(i).who())).append("：").append(ts.get(i).text()).append("\n");
        }
        msgs.put(new JSONObject().put("role", "user").put("content", ctx.toString()));
        try {
            String raw = DeepSeekClient.chat(apiUrl, key, model, msgs);
            String trans = section(raw, "【过渡旁白】", "【先说】");
            String next = section(raw, "【先说】", "【跳分钟】");
            String mins = section(raw, "【跳分钟】", null).replaceAll("[^0-9]", "");
            int skip = mins.isEmpty() ? 0 : Integer.parseInt(mins);
            return new Director(trans, next.contains(nameSelf) ? nameSelf : next.contains(nameOther) ? nameOther : "", skip);
        } catch (Exception e) {
            Log.w("DIRECTOR", "解析失败/调用异常：" + e.getMessage());
            return null;
        }
    }

    private Gen generateOne(Conversation.Who who, String key, String model, String scenario) throws Exception {
        Persona side = personaFor(who);
        String query = convo.lastSpokenText();
        if (query.isBlank()) query = scenario;
        String snippets = corpus.ready() ? corpus.retrieve(query, 4, nameOther, nameSelf) : "";
        ImageLibrary lib = imageLib(who);
        String imageHint = null;
        if (!lib.isEmpty()) {
            List<String> pool = lib.tagPool();
            imageHint = pool.isEmpty() ? "?" : String.join(", ", pool);
        }
        String system = side.buildSystem(correctionsFor(who), snippets, imageHint)
                + "\n【上一条的时间】上一条消息发生在模拟时间 " + fmtTime(stampNow()) + "（" + period(simClock.getHour())
                + "）。你自己决定隔多久再发这条（写在【隔】里），按落到的新时间点该有的状态说话（深夜会困、早上刚醒、饭点提吃饭）。"
                + (runRelation.isEmpty() ? "" : "\n【当前你俩的关系】" + runRelation + "（严格按这个关系阶段和亲密度说话，别超前也别倒退）")
                + styleGuidance();
        Log.w("GEN", name(who) + " 上一条时间=" + fmtTime(stampNow()) + " query=" + Log.cut(query, 40) + " 检索片段=" + snippets.length() + "字");
        JSONArray msgs = convo.buildMessages(system, who, WINDOW);
        String raw = "";
        for (int attempt = 1; attempt <= 3 && !stopReq; attempt++) {
            raw = DeepSeekClient.chat(apiUrl, key, model, msgs);
            if (!raw.isBlank()) break;
            Log.w("GEN", name(who) + " 第" + attempt + "次空返回，重试");
        }
        if (raw.isBlank()) return null;   // 连续空返回，交给调用方暂停

        String gapStr = section(raw, "【隔】", "【心理】").replaceAll("[^0-9]", "");
        int gap;
        try { gap = gapStr.isEmpty() ? chatGap() : Math.min(2880, Integer.parseInt(gapStr)); }
        catch (Exception e) { gap = chatGap(); }
        String think = clean(section(raw, "【心理】", "【消息】"));
        // 把 AI 可能写错的全角【图:xx】先转回 [图:xx]，否则会被 clean 整段剥掉
        String msgRaw = section(raw, "【消息】", "【离开】")
                .replaceAll("【\\s*图\\s*[:：]?\\s*([^】\\n]{0,40})】", "[图:$1]");
        String msg = clean(msgRaw);
        String leave = clean(section(raw, "【离开】", "【轮到】"));
        // AI 经常忘填【离开】，从【消息】里按关键词 fallback infer
        if (leave.isEmpty()) leave = inferLeave(msgRaw);
        String nextStr = section(raw, "【轮到】", null);
        Conversation.Who next = nextStr.contains(nameSelf) ? Conversation.Who.RIGHT
                : nextStr.contains(nameOther) ? Conversation.Who.LEFT : opposite(who);
        if (msg.isEmpty()) msg = clean(raw);   // 没按格式输出，整段当消息
        String stripped = stripActions(msg);
        if (!stripped.equals(msg)) {
            Log.w("GEN", name(who) + " 剥离括号动作：「" + Log.cut(msg, 80) + "」→「" + Log.cut(stripped, 80) + "」");
            msg = stripped;
        }
        if (msg.isEmpty()) return null;        // 全是括号旁白，整条丢弃重生成
        Gen g = new Gen(think, msg, leave, gap, next);
        Log.w("GEN", name(who) + " 隔=" + gap + "分 心理=" + Log.cut(g.think(), 50) + " | 消息=" + Log.cut(g.msg(), 80) + " | 离开=" + g.leave() + " | 轮到=" + name(next));
        return g;
    }

    /** AI 没填【离开】时，从【消息】里按关键词推断活动；返回首个匹配的关键词，否则空。 */
    private static final String[] LEAVE_KEYWORDS = {
            "打游戏","开黑","打一局","睡觉","睡了","睡叭","睡吧","去睡","该睡","下线",
            "洗澡","洗个澡","吃饭","去吃","做饭","上课","下课","画画","跑步","健身","运动",
            "逛街","出门","买东西","加班","上班","工作","学习","自习","看剧","看电影","刷视频"
    };
    private static String inferLeave(String msg) {
        if (msg == null || msg.isEmpty()) return "";
        // 排除引用对方的话："你去睡吧" 这种不算自己要走
        // 简单策略：消息里不含"你去"或"你要"才算
        for (String k : LEAVE_KEYWORDS) {
            int i = msg.indexOf(k);
            if (i < 0) continue;
            // 前缀两字符里有"你"则跳过（如"你睡吧"）
            int prefStart = Math.max(0, i - 2);
            String pref = msg.substring(prefStart, i);
            if (pref.contains("你") || pref.contains("她") || pref.contains("他")) continue;
            return k;
        }
        return "";
    }

    /** 清掉残留的标记，包括模型臆造的【X】（如【回】），并去掉首尾空白。 */
    private static String clean(String s) {
        if (s == null) return "";
        return s.replaceAll("【[^】\\n]{0,20}】", "").strip();
    }

    /** 含中文字符的括号 = AI 自塞的「（动作/旁白）」，全部剥掉；纯符号颜文字 (>_<) (≧▽≦) 保留。 */
    private static final java.util.regex.Pattern ACTION_PAREN = java.util.regex.Pattern.compile(
            "[（(][^()（）\n]{0,60}?[\\u4e00-\\u9fff][^()（）\n]{0,60}?[）)]");

    private static String stripActions(String msg) {
        if (msg == null || msg.isEmpty()) return msg;
        String r = ACTION_PAREN.matcher(msg).replaceAll("");
        // 反复剥一次，应对嵌套残留
        r = ACTION_PAREN.matcher(r).replaceAll("");
        // 清理剥完留下的悬空空白/空行
        r = r.replaceAll("[ \\t]+\n", "\n").replaceAll("\n{3,}", "\n\n").strip();
        return r;
    }

    /** 取出 startTag 与 endTag(可空,表示到结尾) 之间的内容。 */
    private static String section(String text, String startTag, String endTag) {
        int i = text.indexOf(startTag);
        if (i < 0) return "";
        int start = i + startTag.length();
        int j = (endTag == null) ? -1 : text.indexOf(endTag, start);
        return (j < 0 ? text.substring(start) : text.substring(start, j)).trim();
    }

    /** 弹窗显示某一边的人设档案原文（只读）。 */
    private void showPersonaContent(boolean left) {
        File f = dataFile("personas/" + (left ? "left.md" : "right.md"));
        String content;
        if (!f.exists()) {
            content = "（还没有导入" + (left ? "左边" : "右边") + "人设档案）";
        } else {
            try { content = Files.readString(f.toPath()); }
            catch (Exception e) { content = "（读取失败：" + e.getMessage() + "）"; }
        }
        Dialog<Void> d = new Dialog<>();
        String displayName = left ? nameOther : nameSelf;
        d.setTitle("人设档案 · " + (left ? "左" : "右") + (displayName.equals("对方") || displayName.equals("我方") ? "" : "「" + displayName + "」"));
        d.setHeaderText("当前时间线「" + currentTimeline + "」的 " + (left ? "left.md" : "right.md"));
        TextArea ta = new TextArea(content);
        ta.setEditable(false);
        ta.setWrapText(true);
        ta.setPrefRowCount(26);
        ta.setPrefColumnCount(72);
        d.getDialogPane().setContent(ta);
        d.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        themeDialog(d);
        d.showAndWait();
    }

    /** 弹窗显示当前时间线累积的风格指引（👍多保持 / 👎要规避）。 */
    private void showStyleSummary() {
        Dialog<Void> d = new Dialog<>();
        d.setTitle("风格总结 · 时间线「" + currentTimeline + "」");
        d.setHeaderText("AI 累计的反馈指引（生成时会自动注入双方 system prompt，每边各保留最近 12 条）");
        Label pLbl = new Label("👍 多保持（" + stylePrefer.size() + " 条）");
        pLbl.getStyleClass().add("bar-label");
        TextArea pArea = new TextArea(joinNumbered(stylePrefer));
        pArea.setEditable(false);
        pArea.setWrapText(true);
        pArea.setPrefRowCount(8);
        Label aLbl = new Label("👎 要规避（" + styleAvoid.size() + " 条）");
        aLbl.getStyleClass().add("bar-label");
        TextArea aArea = new TextArea(joinNumbered(styleAvoid));
        aArea.setEditable(false);
        aArea.setWrapText(true);
        aArea.setPrefRowCount(8);
        Button clearP = new Button("清空多保持");
        clearP.setOnAction(e -> {
            stylePrefer.clear(); saveStyle();
            pArea.setText(joinNumbered(stylePrefer));
            pLbl.setText("👍 多保持（0 条）");
        });
        Button clearA = new Button("清空要规避");
        clearA.setOnAction(e -> {
            styleAvoid.clear(); saveStyle();
            aArea.setText(joinNumbered(styleAvoid));
            aLbl.setText("👎 要规避（0 条）");
        });
        HBox btns = new HBox(8, clearP, clearA);
        btns.setAlignment(Pos.CENTER_LEFT);
        VBox content = new VBox(8, pLbl, pArea, aLbl, aArea, btns);
        content.setPrefWidth(560);
        d.getDialogPane().setContent(content);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.CLOSE);
        themeDialog(d);
        d.showAndWait();
    }

    private static String joinNumbered(List<String> lst) {
        if (lst.isEmpty()) return "（还没有反馈记录 —— 跑一轮后点 👍/👎 给 AI 反馈，它会自己总结成指引）";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lst.size(); i++) {
            sb.append(i + 1).append(". ").append(lst.get(i)).append("\n");
        }
        return sb.toString();
    }

    private void interject(Conversation.Who who) {
        if (busy) return;
        String t = interField.getText().trim();
        if (t.isEmpty()) return;
        addBubble(convo.add(who, t, "", stampNow()));
        if (who != Conversation.Who.NARRATION) {
            advance(chatGap());
            current = opposite(who);
            renderClock();
            status.setText("轮到：" + name(current));
        }
        interField.clear();
        saveTimeline();
    }

    /** 从一侧图库挑一张图，作为该侧的"插话"插入对话。 */
    private void pickAndInsertImage(Conversation.Who who) {
        if (busy) return;
        ImageLibrary lib = imageLib(who);
        if (lib.isEmpty()) { alert("「" + name(who) + "」还没导入表情包库（设置 → 表情包库）"); return; }

        Dialog<String> d = new Dialog<>();
        d.setTitle("选一张图发出去 · " + name(who));
        d.setHeaderText("点缩略图即插入。库共 " + lib.size() + " 张。");
        javafx.scene.layout.FlowPane grid = new javafx.scene.layout.FlowPane(8, 8);
        grid.setPrefWrapLength(560);
        final String[] picked = { null };
        for (String fname : lib.fileNames()) {
            File f = lib.file(fname);
            if (!f.exists()) continue;
            try {
                ImageView iv = new ImageView(new Image(f.toURI().toString(), 88, 88, true, true, true));
                iv.setFitWidth(88); iv.setFitHeight(88); iv.setPreserveRatio(true);
                Button cell = new Button("", iv);
                cell.getStyleClass().add("btn-ghost");
                List<String> ts = lib.tagsOf(fname);
                if (!ts.isEmpty()) cell.setTooltip(new Tooltip(String.join(", ", ts)));
                final String f0 = fname;
                cell.setOnAction(e -> { picked[0] = f0; d.setResult(f0); d.close(); });
                grid.getChildren().add(cell);
            } catch (Exception ignored) {}
        }
        ScrollPane sp = new ScrollPane(grid);
        sp.setFitToWidth(true);
        sp.setPrefSize(600, 380);
        d.getDialogPane().setContent(sp);
        d.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);
        themeDialog(d);
        d.showAndWait();
        if (picked[0] == null) return;

        // 插入图气泡（类似 interject 但带 imagePath；不切 current/advance，让用户自由）
        Conversation.Turn t = convo.add(who, "", "", stampNow(), lib.relativePath(picked[0]));
        addBubble(t);
        saveTimeline();
        status.setText("已插入" + name(who) + "的一张图：" + picked[0]);
    }

    /** 弹标签编辑器：给图气泡里的那张图打 / 改 / 新增标签。改完写回 images_meta.json。 */
    private void editImageTags(Conversation.Turn t) {
        if (!t.isImage() || t.who() == Conversation.Who.NARRATION) return;
        ImageLibrary lib = imageLib(t.who());
        String fname = new File(t.imagePath()).getName();
        File imgFile = lib.file(fname);
        if (!imgFile.exists()) { alert("图片文件不存在：" + fname); return; }

        Dialog<Void> d = new Dialog<>();
        d.setTitle("图片标签 · " + name(t.who()));
        d.setHeaderText("给「" + fname + "」打标签。AI 下次想发这类情绪时会优先挑这张。");

        ImageView preview = new ImageView(new Image(imgFile.toURI().toString(), 160, 160, true, true, true));
        preview.setPreserveRatio(true);
        preview.setFitWidth(160);
        preview.setFitHeight(160);   // 防长图把输入框挤出可视区

        Label currentLbl = new Label("当前标签（勾选 = 该图属于此标签）：");
        currentLbl.getStyleClass().add("bar-label");
        javafx.scene.layout.FlowPane chipsPane = new javafx.scene.layout.FlowPane(8, 8);
        chipsPane.setPrefWrapLength(360);

        // tagPool 是全部图的并集；勾选状态来自这张图的 tagsOf
        Set<String> selected = new java.util.LinkedHashSet<>(lib.tagsOf(fname));
        Runnable rebuildChips = new Runnable() {
            @Override public void run() {
                chipsPane.getChildren().clear();
                Set<String> union = new java.util.LinkedHashSet<>(lib.tagPool());
                union.addAll(selected);   // 包括刚 new 的
                if (union.isEmpty()) {
                    Label empty = new Label("（还没任何标签，下面新增一个）");
                    empty.getStyleClass().add("muted");
                    chipsPane.getChildren().add(empty);
                    return;
                }
                for (String tag : union) {
                    ToggleButton tb = new ToggleButton(tag);
                    tb.getStyleClass().add("tag-chip");
                    tb.setSelected(selected.contains(tag));
                    tb.selectedProperty().addListener((o, ov, nv) -> {
                        if (nv) selected.add(tag); else selected.remove(tag);
                    });
                    chipsPane.getChildren().add(tb);
                }
            }
        };
        rebuildChips.run();

        TextField newTag = new TextField();
        newTag.setPromptText("新标签…");
        newTag.getStyleClass().add("field");
        Button addTagBtn = new Button("➕ 添加");
        addTagBtn.getStyleClass().add("btn-ghost");
        Runnable addAction = () -> {
            String s = newTag.getText().strip();
            if (s.isEmpty()) return;
            if (s.length() > 20) s = s.substring(0, 20);
            selected.add(s);
            newTag.clear();
            rebuildChips.run();
        };
        addTagBtn.setOnAction(e -> addAction.run());
        newTag.setOnAction(e -> addAction.run());
        HBox addRow = new HBox(8, newTag, addTagBtn);
        HBox.setHgrow(newTag, Priority.ALWAYS);

        VBox box = new VBox(10, preview, currentLbl, chipsPane, addRow);
        box.setPrefWidth(420);
        d.getDialogPane().setContent(box);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        themeDialog(d);
        d.setResultConverter(bt -> {
            if (bt == ButtonType.OK) {
                lib.setTags(fname, new ArrayList<>(selected));
                Log.w("IMG", "标签更新 " + lib.side() + "/" + fname + " → " + String.join(",", selected));
                status.setText("已更新「" + fname + "」标签（" + selected.size() + " 个）");
            }
            return null;
        });
        d.showAndWait();
    }

    /** 标记某条说错了：记成该角色的纠正，并把这条从时间线删掉。 */
    private void flagWrong(Conversation.Turn t) {
        if (busy) return;
        TextInputDialog d = new TextInputDialog();
        d.setTitle("纠正 " + name(t.who()));
        if (t.isImage()) {
            d.setHeaderText("这张图在这里发得不合适，怎么调整？（会记成「以后这种情境别发图」）");
        } else {
            d.setHeaderText("这句不对在哪？想让 " + name(t.who()) + " 以后怎么说？");
        }
        d.setContentText("说明：");
        themeDialog(d);
        Optional<String> r = d.showAndWait();
        if (r.isEmpty() || r.get().isBlank()) return;
        String recorded = t.isImage()
                ? "[发了一张图：" + (t.text().isEmpty() ? "无标签" : t.text()) + "]"
                : t.text();
        correctionsFor(t.who()).add(new String[]{recorded, r.get().trim()});
        Log.w("CORRECT", name(t.who()) + "：「" + Log.cut(recorded, 40) + "」→ " + Log.cut(r.get().trim(), 60));
        saveCorrections();
        convo.remove(t.id());
        renderAll();
        saveTimeline();
        status.setText("已记下纠正并删除该条。以后生成会自动避免。");
    }

    /** 把肯定/否定累积的整轮风格指引拼成注入文本。 */
    private String styleGuidance() {
        StringBuilder sb = new StringBuilder();
        if (!stylePrefer.isEmpty()) {
            sb.append("\n【整体风格 · 多往这些方向（用户肯定过的聊法）】\n");
            for (String s : stylePrefer) sb.append("- ").append(s).append("\n");
        }
        if (!styleAvoid.isEmpty()) {
            sb.append("\n【整体风格 · 要避免（用户否定过的聊法）】\n");
            for (String s : styleAvoid) sb.append("- ").append(s).append("\n");
        }
        return sb.toString();
    }

    /** 对「从上次反馈到现在」这一轮做肯定/否定：存档 + 让 AI 自己读它总结风格指引 + 注入后续。 */
    private void feedbackRound(boolean positive) {
        if (busy || feedbackBusy) return;
        List<Conversation.Turn> ts = convo.turns();
        int from = Math.min(lastFeedbackIdx, ts.size());
        List<Conversation.Turn> round = new ArrayList<>(ts.subList(from, ts.size()));
        boolean hasSpoken = round.stream().anyMatch(t -> t.who() != Conversation.Who.NARRATION);
        if (!hasSpoken) { status.setText("没有新对话可评价（上次反馈后还没生成）。"); return; }
        final String key = keyField.getText().trim();
        if (key.isEmpty()) { alert("请先填写 DeepSeek API Key 并保存"); return; }
        final String model = modelField.getText().trim().isEmpty() ? "deepseek-chat" : modelField.getText().trim();

        // 弹框让用户可选填一段说明（点取消则中止；留空则让 AI 自己看着办）
        Dialog<String> input = new Dialog<>();
        input.setTitle(positive ? "👍 肯定这一轮" : "👎 否定这一轮");
        input.setHeaderText("可选：用自己的话说说" + (positive ? "好在哪 / 想保留什么风格" : "哪里不对 / 要避免什么")
                + "\n（留空 → AI 自己看对话总结；写了 → AI 按你说的整理）");
        TextArea ta = new TextArea();
        ta.setPrefRowCount(3);
        ta.setWrapText(true);
        ta.setPromptText(positive
                ? "如：黏黏的感觉很对 / 撒娇但不腻 / 这种短句节奏好"
                : "如：太正经了 / 撒娇过头 / 不像真人聊天 / 内心戏太多");
        input.getDialogPane().setContent(ta);
        input.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        themeDialog(input);
        input.setResultConverter(bt -> bt == ButtonType.OK ? ta.getText().strip() : null);
        Optional<String> r = input.showAndWait();
        if (r.isEmpty()) return;   // 取消
        final String userNote = r.get();

        StringBuilder dlg = new StringBuilder();
        for (Conversation.Turn t : round) dlg.append(name(t.who())).append("：").append(t.text()).append("\n");
        final String roundText = dlg.toString();
        String stamp = LocalDateTime.now().toString().replaceAll("[:.]", "-");
        final File rf = dataFile("rounds/round_" + stamp + "_" + (positive ? "pos" : "neg") + ".txt");
        try {
            rf.getParentFile().mkdirs();
            String head = userNote.isEmpty() ? roundText : "【用户说明】" + userNote + "\n\n" + roundText;
            Files.writeString(rf.toPath(), head);
        } catch (Exception ignored) {}

        feedbackBusy = true;
        setBusyUI(false);
        status.setText((userNote.isEmpty() ? "AI 自己" : "按你说的")
                + (positive ? " 总结这轮好在哪…" : " 总结这轮要规避什么…"));
        final int endIdx = ts.size();
        new Thread(() -> {
            String userBlock = userNote.isEmpty() ? ""
                    : "\n【用户的具体说明】" + userNote
                      + "\n请围绕用户这段说明来总结要点：用户的话是核心方向，你把它整理成 2-4 条可直接应用的指引就行，"
                      + "不要凭对话另起一套（也不要无视用户说明只看对话）。"
                      + "如果用户说明很短，也要尽量把它的精神扩成 2-4 条可操作的要点。\n";
            String sys = positive
                ? "下面是 " + nameOther + " 和 " + nameSelf + " 的一段对话。用户认为这段很贴合两人真实的聊天风格。"
                  + userBlock
                  + "请用 2-4 条简短中文要点总结：这段好在哪、以后两人应多往哪个方向发展（说话风格/语气/用词/内容）。"
                  + "只输出要点，每条一行、以 - 开头，不要别的话。"
                : "下面是 " + nameOther + " 和 " + nameSelf + " 的一段对话。用户认为这段不够像两人真实的聊天风格。"
                  + userBlock
                  + "请用 2-4 条简短中文要点总结：这段哪里不像、以后两人应避免怎样的聊法（说话风格/语气/用词/内容）。"
                  + "只输出要点，每条一行、以 - 开头，不要别的话。";
            JSONArray msgs = new JSONArray();
            msgs.put(new JSONObject().put("role", "system").put("content", sys));
            msgs.put(new JSONObject().put("role", "user").put("content", roundText));
            try {
                String raw = DeepSeekClient.chat(apiUrl, key, model, msgs);
                List<String> pts = new ArrayList<>();
                for (String line : raw.split("\n")) {
                    String s = line.strip().replaceFirst("^[-*•\\d.、）)\\s]+", "").strip();
                    if (!s.isEmpty()) pts.add(s);
                }
                List<String> bucket = positive ? stylePrefer : styleAvoid;
                Platform.runLater(() -> {
                    bucket.addAll(pts);
                    while (bucket.size() > 12) bucket.remove(0);
                    saveStyle();
                    lastFeedbackIdx = endIdx;
                    Log.w("FEEDBACK", (positive ? "肯定" : "否定") + " " + round.size() + "条"
                            + (userNote.isEmpty() ? " 用户未填说明" : " 用户说明=" + Log.cut(userNote, 60))
                            + " → 新增" + pts.size() + "条指引");
                    status.setText((positive ? "已肯定这轮，AI 会多往这风格发展。" : "已否定这轮，AI 会规避这种聊法。")
                            + "（+" + pts.size() + "条指引，存档 " + rf.getName() + "）");
                });
                try {
                    String head = userNote.isEmpty() ? "" : "【用户说明】" + userNote + "\n\n";
                    Files.writeString(rf.toPath(), head + roundText + "\n---- AI 总结 ----\n" + raw + "\n");
                } catch (Exception ignored) {}
            } catch (Exception ex) {
                Log.err("FEEDBACK", ex);
                Platform.runLater(() -> alert("总结失败：\n" + ex.getMessage()));
            } finally {
                Platform.runLater(() -> { feedbackBusy = false; setBusyUI(busy); });
            }
        }, "feedback").start();
    }

    private void loadStyle() {
        loadStyleFile("style_prefer.txt", stylePrefer);
        loadStyleFile("style_avoid.txt", styleAvoid);
    }
    private void loadStyleFile(String name, List<String> into) {
        File f = dataFile(name);
        if (!f.exists()) return;
        try {
            for (String line : Files.readString(f.toPath()).split("\n")) {
                String s = line.strip();
                if (!s.isEmpty()) into.add(s);
            }
        } catch (Exception ignored) {}
    }
    private void saveStyle() {
        saveStyleFile("style_prefer.txt", stylePrefer);
        saveStyleFile("style_avoid.txt", styleAvoid);
    }
    private void saveStyleFile(String name, List<String> from) {
        try {
            File f = dataFile(name);
            f.getParentFile().mkdirs();
            Files.writeString(f.toPath(), String.join("\n", from));
        } catch (Exception ignored) {}
    }

    // ---------- 渲染 ----------

    private void renderAll() {
        messagesBox.getChildren().clear();
        for (Conversation.Turn t : convo.turns()) addBubble(t, false);
    }

    private void addBubble(Conversation.Turn t) { addBubble(t, true); }

    private void addBubble(Conversation.Turn t, boolean animate) {
        // 旁白：横跨两栏、居中
        if (t.who() == Conversation.Who.NARRATION) {
            Label n = new Label(t.text());
            n.setWrapText(true);
            n.setMaxWidth(700);
            n.getStyleClass().add("narration");
            Label tn = new Label(fmtTime(t.time()));
            tn.getStyleClass().add("narration-time");
            HBox row = new HBox(8, n, tn);
            row.setAlignment(Pos.CENTER);
            row.setPadding(new Insets(4));
            messagesBox.getChildren().add(row);
            if (animate) animateIn(row);
            Platform.runLater(this::scrollToBottom);
            return;
        }

        boolean left = t.who() == Conversation.Who.LEFT;

        // 左栏：直出（名字 + 模拟时间）
        Label name = new Label(left ? nameOther : nameSelf);
        name.getStyleClass().add("meta-name");
        Label tm = new Label(fmtTime(t.time()));
        tm.getStyleClass().add("meta-time");
        HBox meta = new HBox(6, name, tm);
        meta.setAlignment(left ? Pos.CENTER_LEFT : Pos.CENTER_RIGHT);

        Node bubbleNode;
        Node hoverProbe;        // 鼠标悬停高亮探测节点（图气泡=ImageView 容器，文本气泡=Label）
        VBox msgV;
        Button tagBtn = null;   // 仅图气泡才有
        if (t.isImage()) {
            File img = new File(timelineDir(), t.imagePath());
            Node imgNode;
            if (img.exists()) {
                ImageView iv = new ImageView();
                try {
                    iv.setImage(new Image(img.toURI().toString(), 240, 0, true, true, true));
                } catch (Exception ignored) {}
                iv.setFitWidth(200);
                iv.setPreserveRatio(true);
                iv.setSmooth(true);
                imgNode = iv;
            } else {
                Label missing = new Label("[图片已删除] " + new File(t.imagePath()).getName());
                missing.getStyleClass().add("muted");
                imgNode = missing;
            }
            // 用 HBox 包（StackPane 在父 HBox 里默认 hgrow=SOMETIMES 会被撑满整行，导致图气泡看着像旁白）
            HBox imgWrap = new HBox(imgNode);
            imgWrap.setAlignment(Pos.CENTER);
            imgWrap.getStyleClass().add(left ? "bubble-image-other" : "bubble-image-self");
            imgWrap.setMaxWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
            imgWrap.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
            HBox.setHgrow(imgWrap, Priority.NEVER);
            bubbleNode = imgWrap;
            hoverProbe = imgWrap;
            msgV = new VBox(2, meta, imgWrap);
            msgV.setMaxWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
            HBox.setHgrow(msgV, Priority.NEVER);
            tagBtn = new Button("#");
            tagBtn.getStyleClass().add("flag-btn");
            tagBtn.setTooltip(new Tooltip("给这张图打标签（AI 以后按情境选图）"));
            final Conversation.Turn turnRef = t;
            tagBtn.setOnAction(e -> editImageTags(turnRef));
        } else {
            Label msg = new Label(t.text());
            msg.setWrapText(true);
            msg.setMaxWidth(300);
            msg.getStyleClass().add(left ? "bubble-other" : "bubble-self");
            bubbleNode = msg;
            hoverProbe = msg;
            msgV = new VBox(2, meta, msg);
        }
        msgV.setAlignment(left ? Pos.CENTER_LEFT : Pos.CENTER_RIGHT);
        Button x = new Button("✗");
        x.getStyleClass().add("flag-btn");
        x.setOnAction(e -> flagWrong(t));
        HBox btnCol = new HBox(2);
        btnCol.setAlignment(Pos.CENTER);
        if (tagBtn != null) btnCol.getChildren().add(tagBtn);
        btnCol.getChildren().add(x);
        HBox msgCell = new HBox(4);
        msgCell.setAlignment(left ? Pos.CENTER_LEFT : Pos.CENTER_RIGHT);
        // 图气泡显式用 spacer 撑掉对侧空白，避免 HBox alignment+hgrow 配合时图气泡被撑满整行
        if (t.isImage()) {
            javafx.scene.layout.Region spacer = new javafx.scene.layout.Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            if (left) msgCell.getChildren().addAll(msgV, btnCol, spacer);
            else msgCell.getChildren().addAll(spacer, btnCol, msgV);
        } else {
            if (left) msgCell.getChildren().addAll(msgV, btnCol);
            else msgCell.getChildren().addAll(btnCol, msgV);
        }

        // 右栏：内心
        String think = (t.think() == null || t.think().isBlank()) ? "—" : t.think();
        Label tht = new Label(think);
        tht.setWrapText(true);
        tht.setMaxWidth(300);
        tht.getStyleClass().add("bubble-think");
        HBox thtCell = new HBox(tht);
        thtCell.setAlignment(left ? Pos.CENTER_LEFT : Pos.CENTER_RIGHT);

        // 悬停任一边，两边都高亮
        Runnable on = () -> {
            if (!hoverProbe.getStyleClass().contains("linked")) hoverProbe.getStyleClass().add("linked");
            if (!tht.getStyleClass().contains("linked")) tht.getStyleClass().add("linked");
        };
        Runnable off = () -> { hoverProbe.getStyleClass().remove("linked"); tht.getStyleClass().remove("linked"); };
        hoverProbe.setOnMouseEntered(e -> on.run());
        hoverProbe.setOnMouseExited(e -> off.run());
        tht.setOnMouseEntered(e -> on.run());
        tht.setOnMouseExited(e -> off.run());

        // 两栏强制各占一半：minWidth=0 让 HBox 不会因为图气泡的内在 pref 把 msgCell 撑得 > 50%
        msgCell.setMinWidth(0);
        msgCell.setPrefWidth(0);
        thtCell.setMinWidth(0);
        thtCell.setPrefWidth(0);
        HBox.setHgrow(msgCell, Priority.ALWAYS);
        HBox.setHgrow(thtCell, Priority.ALWAYS);
        HBox row = new HBox(msgCell, thtCell);
        row.setPadding(new Insets(4));
        messagesBox.getChildren().add(row);
        if (animate) animateIn(row);
        Platform.runLater(this::scrollToBottom);
    }

    /** 新气泡淡入 + 轻微上滑。 */
    private void animateIn(Node node) {
        node.setOpacity(0);
        node.setTranslateY(12);
        FadeTransition ft = new FadeTransition(Duration.millis(260), node);
        ft.setFromValue(0); ft.setToValue(1); ft.setInterpolator(Interpolator.EASE_OUT);
        TranslateTransition tt = new TranslateTransition(Duration.millis(260), node);
        tt.setFromY(12); tt.setToY(0); tt.setInterpolator(Interpolator.EASE_OUT);
        new ParallelTransition(ft, tt).play();
    }

    /** 平滑滚到底部。 */
    private void scrollToBottom() {
        Timeline tl = new Timeline(new KeyFrame(Duration.millis(220),
                new KeyValue(scroll.vvalueProperty(), 1.0, Interpolator.EASE_OUT)));
        tl.play();
    }

    // ---------- 辅助 ----------

    private Persona personaFor(Conversation.Who w) { return w == Conversation.Who.LEFT ? plx : ly; }
    private ImageLibrary imageLib(Conversation.Who w) { return w == Conversation.Who.LEFT ? imgLeft : imgRight; }
    private List<String[]> correctionsFor(Conversation.Who w) { return w == Conversation.Who.LEFT ? corrLeft : corrRight; }
    private String name(Conversation.Who w) {
        return w == Conversation.Who.LEFT ? nameOther : w == Conversation.Who.RIGHT ? nameSelf : "旁白";
    }
    private Conversation.Who opposite(Conversation.Who w) {
        return w == Conversation.Who.LEFT ? Conversation.Who.RIGHT : Conversation.Who.LEFT;
    }

    // ---------- 模拟时间 ----------

    private String stampNow() { return simClock.toString(); }
    private void advance(int minutes) { simClock = simClock.plusMinutes(minutes); }
    private int chatGap() { return 1 + rnd.nextInt(4); }   // 每条消息间隔 1-4 分钟

    /** 把 ISO 时间格式化成「第N天 HH:mm」。 */
    private String fmtTime(String iso) {
        if (iso == null || iso.isBlank()) return "";
        try {
            LocalDateTime dt = LocalDateTime.parse(iso);
            long day = ChronoUnit.DAYS.between(BASE.toLocalDate(), dt.toLocalDate()) + 1;
            return String.format("第%d天 %02d:%02d", day, dt.getHour(), dt.getMinute());
        } catch (Exception e) {
            return "";
        }
    }

    private String period(int h) {
        if (h < 5) return "深夜";
        if (h < 8) return "清晨";
        if (h < 11) return "上午";
        if (h < 13) return "中午";
        if (h < 17) return "下午";
        if (h < 19) return "傍晚";
        if (h < 23) return "晚上";
        return "深夜";
    }

    private void setBusyUI(boolean b) {
        genBtn.setDisable(b);
        contBtn.setDisable(b);
        clearBtn.setDisable(b);
        startBtn.setDisable(b);
        leftBtn.setDisable(b);
        rightBtn.setDisable(b);
        narrBtn.setDisable(b);
        posBtn.setDisable(b || feedbackBusy);
        negBtn.setDisable(b || feedbackBusy);
        stopBtn.setDisable(!b);
    }

    /** 从软件目录 data/personas/ 读双方人设副本，派生显示名，组装 Persona。 */
    private void loadPersonas() {
        String other = readPersonaCopy("left.md");
        String self = readPersonaCopy("right.md");
        personasReady = other != null && self != null;
        nameOther = other != null ? parseName(other, "对方") : "对方";
        nameSelf = self != null ? parseName(self, "我方") : "我方";
        plx = new Persona(nameOther, nameSelf, other != null ? other : "（未导入对方人设）");
        ly = new Persona(nameSelf, nameOther, self != null ? self : "（未导入我方人设）");
        refreshNames();
    }

    private String readPersonaCopy(String name) {
        File f = dataFile("personas/" + name);
        if (!f.exists()) return null;
        try { return Files.readString(f.toPath()); } catch (Exception e) { return null; }
    }

    /** 取人设档案第一条 Markdown 标题里的显示名（# 小明（…） → 小明）。 */
    private String parseName(String text, String dflt) {
        for (String line : text.split("\n")) {
            String s = line.strip();
            if (s.startsWith("# ")) {
                s = s.substring(2).strip();
                int cut = s.length();
                for (String sep : new String[]{"（", "(", " ", "\t", "/", "·"}) {
                    int i = s.indexOf(sep);
                    if (i > 0) cut = Math.min(cut, i);
                }
                String n = s.substring(0, cut).strip();
                return n.isEmpty() ? dflt : n;
            }
        }
        return dflt;
    }

    /** 导入一份蒸馏人设：复制进 data/personas/（之后软件改这份副本，不动原文件）。 */
    private void importPersona(boolean left) {
        if (busy) return;
        FileChooser fc = new FileChooser();
        fc.setTitle("选择" + (left ? "左边" : "右边") + "的蒸馏人设档案");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("人设档案", "*.md", "*.txt"));
        File src = fc.showOpenDialog(null);
        if (src == null) return;
        try {
            File dst = dataFile("personas/" + (left ? "left.md" : "right.md"));
            dst.getParentFile().mkdirs();
            Files.copy(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING);
            loadPersonas();
            status.setText("已导入" + (left ? "左边" : "右边") + "人设：" + (left ? nameOther : nameSelf)
                    + "（落在时间线「" + currentTimeline + "」）" + (personasReady ? "" : " — 还差另一边"));
            Log.w("PERSONA", "导入" + (left ? "左边" : "右边") + "=" + (left ? nameOther : nameSelf) + " 来源=" + src);
        } catch (Exception e) {
            alert("导入失败：" + e.getMessage());
        }
    }

    /** 名字变了之后刷新界面上用到名字的地方。 */
    private void refreshNames() {
        if (leftBtn != null) leftBtn.setText("以" + nameOther);
        if (rightBtn != null) rightBtn.setText("以" + nameSelf);
        if (starterCombo != null) {
            starterCombo.getItems().setAll(nameOther, nameSelf);
            if (starterCombo.getValue() == null) starterCombo.setValue(nameOther);
        }
    }

    /** 数据根目录（全局）。 */
    private File dataRoot() {
        return new File(System.getProperty("user.dir"), "data");
    }
    /** 所有时间线的父目录。 */
    private File timelinesRoot() {
        return new File(dataRoot(), "timelines");
    }
    /** 当前时间线的目录。 */
    private File timelineDir() {
        return new File(timelinesRoot(), currentTimeline);
    }
    /** 全局文件（不归任何时间线管）。 */
    private File globalFile(String name) {
        return new File(dataRoot(), name);
    }
    /**
     * 路径解析：config.json 等少数文件全局；其余（timeline.json、corrections_*、style_*、personas/*、rounds/*、meta.json）
     * 都落在当前时间线目录下。
     */
    private File dataFile(String name) {
        if ("config.json".equals(name) || "log.txt".equals(name)) return globalFile(name);
        return new File(timelineDir(), name);
    }

    /** 重载左右两侧表情包库（指向当前时间线目录）。 */
    private void loadImageLibs() {
        imgLeft.load(timelineDir());
        imgRight.load(timelineDir());
        Log.w("IMG", "图库已加载 左=" + imgLeft.size() + " 右=" + imgRight.size());
    }

    /** 选一个文件夹导入到一侧的图库。 */
    private void importImageLibrary(boolean left) {
        if (busy) return;
        javafx.stage.DirectoryChooser dc = new javafx.stage.DirectoryChooser();
        dc.setTitle("选择" + (left ? "左边" : "右边") + "表情包文件夹（里面的图片会被复制进库）");
        File dir = dc.showDialog(null);
        if (dir == null) return;
        ImageLibrary lib = left ? imgLeft : imgRight;
        if (lib.imagesDir().getParentFile() == null) { alert("当前时间线目录无效"); return; }
        int n = lib.importFolder(dir);
        Log.w("IMG", "导入" + (left ? "左" : "右") + "表情包 " + n + " 张 来源=" + dir);
        status.setText("已导入" + (left ? "左边" : "右边") + " " + n + " 张表情，当前库 " + lib.size() + " 张");
    }

    /** 清空一侧表情包库（含磁盘文件，二次确认）。 */
    private void clearImageLibrary(boolean left) {
        if (busy) return;
        ImageLibrary lib = left ? imgLeft : imgRight;
        if (lib.isEmpty()) { alert((left ? "左边" : "右边") + "本来就是空的"); return; }
        Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                "确定清空「" + (left ? "左边" : "右边") + "」表情包库？\n"
                        + "会删除磁盘上 " + lib.size() + " 张图片 + 所有标签。已发出的图气泡会变成 \"图片不存在\"。",
                ButtonType.OK, ButtonType.CANCEL);
        a.setHeaderText(null);
        themeDialog(a);
        if (a.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        int n = lib.clearAll();
        Log.w("IMG", "清空" + (left ? "左" : "右") + "表情包 删 " + n + " 张");
        status.setText("已清空" + (left ? "左边" : "右边") + " " + n + " 张表情");
    }

    /** 启动时一次性把旧版扁平布局的数据搬进 timelines/默认/。已迁移过就什么也不做。 */
    private void migrateLegacyData() {
        File timelinesDir = timelinesRoot();
        if (timelinesDir.exists()) return;   // 已是新布局
        File defaultDir = new File(timelinesDir, "默认");
        if (!defaultDir.mkdirs()) {
            Log.w("MIGRATE", "无法创建默认时间线目录");
            return;
        }
        String[] files = {"timeline.json", "corrections_plx.json", "corrections_ly.json",
                "style_prefer.txt", "style_avoid.txt"};
        for (String n : files) {
            File src = new File(dataRoot(), n);
            if (src.exists()) {
                try { Files.move(src.toPath(), new File(defaultDir, n).toPath()); }
                catch (Exception e) { Log.w("MIGRATE", n + " 迁移失败：" + e.getMessage()); }
            }
        }
        for (String d : new String[]{"personas", "rounds"}) {
            File src = new File(dataRoot(), d);
            if (src.exists() && src.isDirectory()) {
                try { Files.move(src.toPath(), new File(defaultDir, d).toPath()); }
                catch (Exception e) { Log.w("MIGRATE", d + "/ 迁移失败：" + e.getMessage()); }
            }
        }
        Log.w("MIGRATE", "已建 timelines/默认/ 并迁入旧数据");
    }

    /** 列出所有时间线名（按目录名）。没有就保证至少有「默认」。 */
    private List<String> listTimelines() {
        List<String> out = new ArrayList<>();
        File root = timelinesRoot();
        if (root.exists() && root.isDirectory()) {
            File[] subs = root.listFiles(File::isDirectory);
            if (subs != null) {
                for (File f : subs) out.add(f.getName());
            }
        }
        if (out.isEmpty()) {
            File def = new File(root, "默认");
            def.mkdirs();
            out.add("默认");
        }
        out.sort(String::compareTo);
        return out;
    }

    /** 名字合法性：去掉文件系统非法字符；空字符串、纯空白、点开头都拒。 */
    private String sanitizeTimelineName(String raw) {
        if (raw == null) return null;
        String s = raw.strip().replaceAll("[\\\\/:*?\"<>|]", "").strip();
        if (s.isEmpty() || s.startsWith(".") || s.length() > 60) return null;
        return s;
    }

    /** 切到 name 这个时间线：停运行 → 保存当前 → 切目录 → 清状态 → 重载所有。 */
    private void switchTimeline(String name) {
        if (name == null || name.equals(currentTimeline)) return;
        if (busy) {
            stopReq = true;
            stopRun("切换时间线，已停止运行。");
        }
        // 保存当前时间线状态
        saveTimeline();
        saveCorrections();
        saveStyle();
        saveMeta();

        currentTimeline = name;
        savePrefs();   // 把 currentTimeline 写进全局 config.json

        // 清空内存状态
        convo.clear();
        corrLeft.clear(); corrRight.clear();
        stylePrefer.clear(); styleAvoid.clear();
        pendingLeave = null;
        sinceEvent = 99;
        simClock = startMoment();
        lastFeedbackIdx = 0;
        messagesBox.getChildren().clear();
        relationField.setText("");

        // 重新加载新时间线
        loadMeta();
        loadCorrections();
        loadStyle();
        loadPersonas();
        loadImageLibs();
        loadTimeline();
        renderAll();
        renderClock();
        refreshTimelineCombo();
        status.setText("已切到时间线「" + currentTimeline + "」 · 共 " + convo.turns().size() + " 条历史");
        Log.w("TIMELINE", "切到「" + currentTimeline + "」 历史=" + convo.turns().size());
    }

    /** 新建时间线：默认从当前复制 personas 过去，聊天/纠正/风格全空。 */
    private void createTimelineDialog() {
        if (busy) { alert("先停止当前运行再新建时间线"); return; }
        Dialog<String[]> dlg = new Dialog<>();
        dlg.setTitle("新建时间线");
        dlg.setHeaderText("起个名字，并设定两人初始关系（之后由 AI 巡视对话自动演化）");
        TextField nameF = new TextField();
        nameF.setPromptText("例：默认 / 高中重逢 / what_if_no_breakup");
        nameF.getStyleClass().add("field");
        TextField relF = new TextField();
        relF.setPromptText("例：高中同学，刚互相表白第 1 天");
        relF.getStyleClass().add("field");
        GridPane g = new GridPane();
        g.setHgap(8); g.setVgap(8);
        g.add(new Label("时间线名字："), 0, 0); g.add(nameF, 1, 0);
        g.add(new Label("初始关系："), 0, 1); g.add(relF, 1, 1);
        GridPane.setHgrow(nameF, Priority.ALWAYS);
        GridPane.setHgrow(relF, Priority.ALWAYS);
        nameF.setMaxWidth(Double.MAX_VALUE);
        relF.setMaxWidth(Double.MAX_VALUE);
        g.setPrefWidth(480);
        dlg.getDialogPane().setContent(g);
        dlg.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dlg.setResultConverter(bt -> bt == ButtonType.OK ? new String[]{ nameF.getText(), relF.getText() } : null);
        themeDialog(dlg);
        Optional<String[]> r = dlg.showAndWait();
        if (r.isEmpty()) return;
        String name = sanitizeTimelineName(r.get()[0]);
        String relation = r.get()[1] == null ? "" : r.get()[1].trim();
        if (name == null) { alert("名字不合法（不能含 \\ / : * ? \" < > |，最长 60 字）"); return; }
        File dir = new File(timelinesRoot(), name);
        if (dir.exists()) { alert("已存在同名时间线：" + name); return; }
        if (!dir.mkdirs()) { alert("无法创建目录：" + dir.getAbsolutePath()); return; }

        // 从当前时间线复制 personas（保持人物一致，方便迭代）
        File srcPersonas = new File(timelineDir(), "personas");
        if (srcPersonas.exists() && srcPersonas.isDirectory()) {
            try {
                File dstPersonas = new File(dir, "personas");
                dstPersonas.mkdirs();
                for (File f : srcPersonas.listFiles()) {
                    Files.copy(f.toPath(), new File(dstPersonas, f.getName()).toPath());
                }
                Log.w("TIMELINE", "新「" + name + "」从「" + currentTimeline + "」复制了人设");
            } catch (Exception e) {
                Log.w("TIMELINE", "复制人设失败：" + e.getMessage());
            }
        }
        // 提前写一份 meta，让 switchTimeline → loadMeta 时把 relation 加载进来
        try {
            JSONObject o = new JSONObject().put("relationship", relation).put("startTime", fmtHHmm(startTimeOfDay));
            Files.writeString(new File(dir, "meta.json").toPath(), o.toString(2));
        } catch (Exception ignored) {}
        switchTimeline(name);
    }

    /** 重命名当前时间线（默认这个不让重命名以免迁移路径丢）。 */
    private void renameTimelineDialog() {
        if (busy) { alert("先停止当前运行再重命名"); return; }
        TextInputDialog d = new TextInputDialog(currentTimeline);
        d.setTitle("重命名时间线");
        d.setHeaderText("当前：" + currentTimeline);
        d.setContentText("新名字：");
        themeDialog(d);
        Optional<String> r = d.showAndWait();
        if (r.isEmpty()) return;
        String name = sanitizeTimelineName(r.get());
        if (name == null) { alert("名字不合法"); return; }
        if (name.equals(currentTimeline)) return;
        File src = timelineDir();
        File dst = new File(timelinesRoot(), name);
        if (dst.exists()) { alert("已存在同名时间线：" + name); return; }
        saveTimeline(); saveCorrections(); saveStyle(); saveMeta();
        try {
            Files.move(src.toPath(), dst.toPath());
            currentTimeline = name;
            savePrefs();
            refreshTimelineCombo();
            status.setText("已重命名为「" + currentTimeline + "」");
            Log.w("TIMELINE", "重命名 → " + currentTimeline);
        } catch (Exception e) {
            alert("重命名失败：" + e.getMessage());
        }
    }

    /** 删除一个时间线（不能删当前正在用的；至少保留一个）。 */
    private void deleteTimelineDialog() {
        if (busy) { alert("先停止当前运行"); return; }
        List<String> all = listTimelines();
        List<String> candidates = new ArrayList<>(all);
        candidates.remove(currentTimeline);
        if (candidates.isEmpty()) { alert("当前时间线不能删，也没有别的可删的"); return; }
        ChoiceDialog<String> d = new ChoiceDialog<>(candidates.get(0), candidates);
        d.setTitle("删除时间线");
        d.setHeaderText("选要删的时间线（当前的「" + currentTimeline + "」不在列表里）");
        d.setContentText("时间线：");
        themeDialog(d);
        Optional<String> r = d.showAndWait();
        if (r.isEmpty()) return;
        String name = r.get();
        Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                "确认删除时间线「" + name + "」？里面的聊天/纠正/风格/人设全部会消失，不可恢复。",
                ButtonType.OK, ButtonType.CANCEL);
        a.setHeaderText(null);
        themeDialog(a);
        if (a.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        File dir = new File(timelinesRoot(), name);
        if (!deleteRecursively(dir)) { alert("删除失败，可能有文件被占用"); return; }
        refreshTimelineCombo();
        Log.w("TIMELINE", "删除「" + name + "」");
        status.setText("已删除时间线「" + name + "」");
    }

    private boolean deleteRecursively(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) if (!deleteRecursively(k)) return false;
        }
        return f.delete();
    }

    private void refreshTimelineCombo() {
        List<String> names = listTimelines();
        timelineSwitching = true;
        timelineCombo.getItems().setAll(names);
        timelineCombo.setValue(currentTimeline);
        timelineSwitching = false;
    }

    /** meta.json：当前时间线的 relationship/起始时刻 等元信息（之前 relationship 在全局 config.json 里）。 */
    private void loadMeta() {
        File f = dataFile("meta.json");
        if (!f.exists()) {
            // 回退：第一次没有 meta，从全局 config.json 兜底读 relationship（兼容老版本）
            relationField.setText(legacyRelationship);
            startTimeOfDay = LocalTime.of(21, 0);
            startTimeField.setText(fmtHHmm(startTimeOfDay));
            return;
        }
        try {
            JSONObject o = new JSONObject(Files.readString(f.toPath()));
            relationField.setText(o.optString("relationship", ""));
            LocalTime t = parseHHmm(o.optString("startTime", ""));
            startTimeOfDay = (t != null) ? t : LocalTime.of(21, 0);
            startTimeField.setText(fmtHHmm(startTimeOfDay));
        } catch (Exception e) {
            relationField.setText("");
            startTimeOfDay = LocalTime.of(21, 0);
            startTimeField.setText(fmtHHmm(startTimeOfDay));
        }
    }

    private void saveMeta() {
        try {
            File f = dataFile("meta.json");
            f.getParentFile().mkdirs();
            JSONObject o = new JSONObject()
                    .put("relationship", relationField.getText().trim())
                    .put("startTime", fmtHHmm(startTimeOfDay));
            Files.writeString(f.toPath(), o.toString(2));
        } catch (Exception ignored) {
        }
    }

    // ===== 主题系统 =====

    /** 把主题类应用到根 + 给主 Scene。custom 用 inline style 注入 accent 三色。 */
    private void applyTheme() {
        if (rootBox == null) return;
        String effectiveMode = themeMode;
        if ("system".equals(effectiveMode)) effectiveMode = detectSystemMode();
        String modeCls = "night".equals(effectiveMode) ? "theme-night" : "theme-day";
        String accentCls;
        String inline = "";
        if ("custom".equals(themeAccent)) {
            accentCls = "accent-custom";
            String hex = themeCustomColor == null || themeCustomColor.isBlank() ? "#1C4FA0" : themeCustomColor;
            String dark = shiftHex(hex, -0.18);
            String bright = shiftHex(hex, 0.18);
            inline = "accent: " + hex + "; accent-dark: " + dark + "; accent-bright: " + bright + ";";
        } else {
            accentCls = "accent-" + themeAccent;
        }
        rootBox.getStyleClass().removeIf(c -> c.startsWith("theme-") || c.startsWith("accent-"));
        rootBox.getStyleClass().addAll(modeCls, accentCls);
        rootBox.setStyle(inline);
        Log.w("THEME", "应用：" + themeMode + (themeMode.equals("system") ? "(→" + effectiveMode + ")" : "") + " · " + themeAccent + (themeAccent.equals("custom") ? "(" + themeCustomColor + ")" : ""));
    }

    /** Windows: 读注册表 AppsUseLightTheme（0=深色，1=浅色）。读不到返回 day。 */
    private String detectSystemMode() {
        try {
            Process p = new ProcessBuilder("reg", "query",
                    "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                    "/v", "AppsUseLightTheme").redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes()).toLowerCase();
            p.waitFor();
            if (out.contains("0x0")) return "night";
            return "day";
        } catch (Exception e) {
            return "day";
        }
    }

    /** 颜色亮度偏移：amount>0 变亮，<0 变暗（按线性 RGB 拉/拽）。 */
    private static String shiftHex(String hex, double amount) {
        try {
            Color c = Color.web(hex);
            double r, g, b;
            if (amount >= 0) {
                r = c.getRed() + (1 - c.getRed()) * amount;
                g = c.getGreen() + (1 - c.getGreen()) * amount;
                b = c.getBlue() + (1 - c.getBlue()) * amount;
            } else {
                r = c.getRed() * (1 + amount);
                g = c.getGreen() * (1 + amount);
                b = c.getBlue() * (1 + amount);
            }
            return String.format("#%02X%02X%02X",
                    (int) Math.round(r * 255), (int) Math.round(g * 255), (int) Math.round(b * 255));
        } catch (Exception e) {
            return hex;
        }
    }

    /** 让任意 Dialog/Alert 用上当前主题：贴 stylesheet + 主题类 + themed-dialog。 */
    private void themeDialog(Dialog<?> d) {
        DialogPane pane = d.getDialogPane();
        pane.getStylesheets().add(getClass().getResource("/style.css").toExternalForm());
        String effectiveMode = "system".equals(themeMode) ? detectSystemMode() : themeMode;
        String modeCls = "night".equals(effectiveMode) ? "theme-night" : "theme-day";
        String accentCls = "custom".equals(themeAccent) ? "accent-custom" : ("accent-" + themeAccent);
        pane.getStyleClass().addAll("themed-dialog", modeCls, accentCls);
        if ("custom".equals(themeAccent)) {
            String hex = themeCustomColor == null || themeCustomColor.isBlank() ? "#1C4FA0" : themeCustomColor;
            pane.setStyle("accent: " + hex + "; accent-dark: " + shiftHex(hex, -0.18) + "; accent-bright: " + shiftHex(hex, 0.18) + ";");
        }
    }

    /** 主题选择对话框。 */
    // ===== 设置对话框（检查更新 / 重看引导 / 关于） =====
    private void showSettingsDialog() {
        Dialog<Void> d = new Dialog<>();
        d.setTitle("设置");
        d.setHeaderText(APP_NAME + " · v" + APP_VERSION);

        // ===== API 段（任意 OpenAI 兼容服务） =====
        ComboBox<String> provCombo = new ComboBox<>();
        for (Provider p : PROVIDERS) provCombo.getItems().add(p.name());
        provCombo.setValue(apiProvider);
        provCombo.getStyleClass().add("combo");
        TextField urlF = new TextField(apiUrl);
        urlF.getStyleClass().add("field");
        urlF.setPromptText("https://api.xxx.com/v1/chat/completions");
        TextField modelF = new TextField(modelField.getText());
        modelF.getStyleClass().add("field");
        modelF.setPromptText("deepseek-chat / gpt-4o-mini / …");
        PasswordField keyF = new PasswordField();
        keyF.setText(keyField.getText());
        keyF.getStyleClass().add("field");
        keyF.setPromptText("sk-…");
        provCombo.setOnAction(e -> {
            String name = provCombo.getValue();
            if (name == null) return;
            for (Provider p : PROVIDERS) {
                if (p.name().equals(name)) {
                    if (!"自定义".equals(name)) {
                        urlF.setText(p.url());
                        if (modelF.getText().isBlank()) modelF.setText(p.defaultModel());
                    }
                    break;
                }
            }
        });
        Button apiSaveBtn = new Button("应用 API 设置");
        apiSaveBtn.getStyleClass().add("btn");
        Label apiSaveHint = new Label("");
        apiSaveBtn.setOnAction(e -> {
            apiProvider = provCombo.getValue();
            apiUrl = urlF.getText().trim();
            modelField.setText(modelF.getText().trim());
            keyField.setText(keyF.getText().trim());
            savePrefs();
            apiSaveHint.setText("✅ 已保存");
        });
        GridPane apiGrid = new GridPane();
        apiGrid.setHgap(8); apiGrid.setVgap(6);
        apiGrid.add(new Label("服务商："), 0, 0); apiGrid.add(provCombo, 1, 0);
        apiGrid.add(new Label("接口地址："), 0, 1); apiGrid.add(urlF, 1, 1);
        apiGrid.add(new Label("模型："), 0, 2); apiGrid.add(modelF, 1, 2);
        apiGrid.add(new Label("API Key："), 0, 3); apiGrid.add(keyF, 1, 3);
        GridPane.setHgrow(urlF, Priority.ALWAYS);
        GridPane.setHgrow(modelF, Priority.ALWAYS);
        GridPane.setHgrow(keyF, Priority.ALWAYS);
        urlF.setMaxWidth(Double.MAX_VALUE);
        modelF.setMaxWidth(Double.MAX_VALUE);
        keyF.setMaxWidth(Double.MAX_VALUE);

        Label updHint = new Label("从 GitHub releases 拉取最新版本号比对");
        updHint.getStyleClass().add("bar-label");
        Button checkBtn = new Button("🔄 检查更新");
        checkBtn.getStyleClass().add("btn");
        Label updResult = new Label("");
        updResult.setWrapText(true);
        updResult.setMaxWidth(380);
        Button openReleasesBtn = new Button("前往下载页");
        openReleasesBtn.getStyleClass().add("btn-ghost");
        openReleasesBtn.setVisible(false);
        openReleasesBtn.setManaged(false);
        String[] latestUrl = { "https://github.com/lingyunalingyun/LanHing-VirtualChat-Program/releases" };
        openReleasesBtn.setOnAction(e -> openInBrowser(latestUrl[0]));
        checkBtn.setOnAction(e -> {
            checkBtn.setDisable(true);
            updResult.setText("检查中…");
            new Thread(() -> {
                try {
                    java.net.http.HttpClient c = java.net.http.HttpClient.newBuilder()
                            .connectTimeout(java.time.Duration.ofSeconds(8))
                            .followRedirects(java.net.http.HttpClient.Redirect.NORMAL).build();
                    java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(UPDATE_API))
                            .header("Accept", "application/vnd.github+json")
                            .header("User-Agent", "LanHing-VirtualChat-Updater")
                            .timeout(java.time.Duration.ofSeconds(10))
                            .GET().build();
                    java.net.http.HttpResponse<String> resp = c.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
                    JSONObject o = new JSONObject(resp.body());
                    String tag = o.optString("tag_name", "").replaceFirst("^v", "");
                    String name = o.optString("name", "v" + tag);
                    String html = o.optString("html_url", latestUrl[0]);
                    Platform.runLater(() -> {
                        checkBtn.setDisable(false);
                        if (tag.isEmpty()) { updResult.setText("❌ 解析失败：" + Log.cut(resp.body(), 100)); return; }
                        int cmp = compareVersion(tag, APP_VERSION);
                        if (cmp > 0) {
                            updResult.setText("✨ 发现新版本 v" + tag + "（" + name + "）");
                            latestUrl[0] = html;
                            openReleasesBtn.setVisible(true);
                            openReleasesBtn.setManaged(true);
                        } else if (cmp == 0) {
                            updResult.setText("✅ 已是最新版本（v" + APP_VERSION + "）");
                        } else {
                            updResult.setText("ℹ️ 远端是 v" + tag + "，本地 v" + APP_VERSION + "（更新）");
                        }
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        checkBtn.setDisable(false);
                        updResult.setText("❌ 检查失败：" + ex.getMessage());
                    });
                }
            }, "update-check").start();
        });

        Button guideBtn = new Button("📖 重看使用引导");
        guideBtn.getStyleClass().add("btn-ghost");
        guideBtn.setOnAction(e -> showFirstRunGuide(true));

        Button repoBtn = new Button("打开 GitHub 仓库");
        repoBtn.getStyleClass().add("btn-ghost");
        repoBtn.setOnAction(e -> openInBrowser("https://github.com/lingyunalingyun/LanHing-VirtualChat-Program"));

        // ===== 人设段 =====
        Label personasHint = new Label("当前时间线「" + currentTimeline + "」的双方人设档案");
        personasHint.getStyleClass().add("bar-label");
        Button impLeftBtn = new Button();
        impLeftBtn.getStyleClass().add("btn-ghost");
        Button impRightBtn = new Button();
        impRightBtn.getStyleClass().add("btn-ghost");
        Button viewLeftBtn = new Button("👀 查看左边档案内容");
        viewLeftBtn.getStyleClass().add("btn-ghost");
        viewLeftBtn.setOnAction(e -> showPersonaContent(true));
        Button viewRightBtn = new Button("👀 查看右边档案内容");
        viewRightBtn.getStyleClass().add("btn-ghost");
        viewRightBtn.setOnAction(e -> showPersonaContent(false));
        Runnable refreshPersonaBtns = () -> {
            impLeftBtn.setText("📁 左边：" + (dataFile("personas/left.md").exists() ? "「" + nameOther + "」（点击更换）" : "未导入，点击选择…"));
            impRightBtn.setText("📁 右边：" + (dataFile("personas/right.md").exists() ? "「" + nameSelf + "」（点击更换）" : "未导入，点击选择…"));
            viewLeftBtn.setDisable(!dataFile("personas/left.md").exists());
            viewRightBtn.setDisable(!dataFile("personas/right.md").exists());
        };
        refreshPersonaBtns.run();
        impLeftBtn.setOnAction(e -> { importPersona(true); refreshPersonaBtns.run(); });
        impRightBtn.setOnAction(e -> { importPersona(false); refreshPersonaBtns.run(); });
        HBox personaRow1 = new HBox(8, impLeftBtn, viewLeftBtn);
        HBox personaRow2 = new HBox(8, impRightBtn, viewRightBtn);

        // ===== 表情包库 =====
        Button impLeftImgBtn = new Button();
        impLeftImgBtn.getStyleClass().add("btn-ghost");
        Button impRightImgBtn = new Button();
        impRightImgBtn.getStyleClass().add("btn-ghost");
        Button clearLeftImgBtn = new Button("🗑️ 清空左");
        clearLeftImgBtn.getStyleClass().add("btn-ghost");
        Button clearRightImgBtn = new Button("🗑️ 清空右");
        clearRightImgBtn.getStyleClass().add("btn-ghost");
        Runnable refreshImgBtns = () -> {
            impLeftImgBtn.setText("📷 左边表情包：" + (imgLeft.isEmpty()
                    ? "未导入，点击选文件夹…"
                    : imgLeft.size() + " 张（点击追加/更换）"));
            impRightImgBtn.setText("📷 右边表情包：" + (imgRight.isEmpty()
                    ? "未导入，点击选文件夹…"
                    : imgRight.size() + " 张（点击追加/更换）"));
            clearLeftImgBtn.setDisable(imgLeft.isEmpty());
            clearRightImgBtn.setDisable(imgRight.isEmpty());
        };
        refreshImgBtns.run();
        impLeftImgBtn.setOnAction(e -> { importImageLibrary(true); refreshImgBtns.run(); });
        impRightImgBtn.setOnAction(e -> { importImageLibrary(false); refreshImgBtns.run(); });
        clearLeftImgBtn.setOnAction(e -> { clearImageLibrary(true); refreshImgBtns.run(); });
        clearRightImgBtn.setOnAction(e -> { clearImageLibrary(false); refreshImgBtns.run(); });
        HBox imgRow1 = new HBox(8, impLeftImgBtn, clearLeftImgBtn);
        HBox imgRow2 = new HBox(8, impRightImgBtn, clearRightImgBtn);

        // ===== AI 学到的（风格指引） =====
        Button viewStyleBtn = new Button("📋 查看风格总结（AI 累计学到的指引）");
        viewStyleBtn.getStyleClass().add("btn-ghost");
        viewStyleBtn.setOnAction(e -> showStyleSummary());

        // ===== 各分区内容 =====
        VBox apiSec = settingsSection("API · 任意 OpenAI 兼容服务",
                "粘 Key 即可。绝大多数厂家（DeepSeek/OpenAI/Moonshot/Zhipu/Qwen/Gemini/OpenRouter/SiliconFlow…）都用 OpenAI 协议。",
                apiGrid, new HBox(8, apiSaveBtn, apiSaveHint));
        VBox personaSec = settingsSection("人设档案",
                "当前时间线「" + currentTimeline + "」的双方人设档案", personaRow1, personaRow2);
        VBox imagesSec = settingsSection("表情包库",
                "每一侧独立。导入一个文件夹会把里面所有 png/jpg/gif/webp 复制进 personas/<侧>/images/。"
                + "AI 输出 [图:标签] 时按标签从库里选图；点气泡的 🏷️ 给图打标签。",
                imgRow1, imgRow2);
        VBox styleSec = settingsSection("AI 学到的风格指引",
                "👍 / 👎 整轮反馈后，AI 自动总结成「多保持/要规避」两组指引，下一轮生成时注入双方 system prompt。",
                viewStyleBtn);
        CheckBox autoUpdateBox = new CheckBox("自动检查并下载更新（关闭后小版本只通知，大版本仍强制）");
        autoUpdateBox.setSelected(autoUpdate);
        autoUpdateBox.setOnAction(e -> { autoUpdate = autoUpdateBox.isSelected(); savePrefs(); });
        VBox updateSec = settingsSection("更新", "启动时自动比对 GitHub releases；连接不到 GitHub 静默跳过",
                autoUpdateBox, new HBox(8, checkBtn, openReleasesBtn), updResult);
        VBox guideSec = settingsSection("使用引导", "回顾首启动的分步引导（不会重置已填资料）", guideBtn);
        VBox aboutSec = settingsSection("关于",
                APP_NAME + " · v" + APP_VERSION + "\nMIT License · © 2026 lingyunalingyun", repoBtn);

        // ===== 侧栏 + 内容区 =====
        ListView<String> sidebar = new ListView<>();
        sidebar.getItems().addAll("API", "人设档案", "表情包库", "风格指引", "更新", "引导", "关于");
        sidebar.getStyleClass().add("settings-sidebar");
        sidebar.setPrefWidth(140);
        sidebar.setMaxWidth(140);
        StackPane contentPane = new StackPane();
        contentPane.setPrefSize(480, 380);
        contentPane.setPadding(new Insets(0, 0, 0, 16));
        java.util.Map<String, Node> secMap = new java.util.LinkedHashMap<>();
        secMap.put("API", apiSec);
        secMap.put("人设档案", personaSec);
        secMap.put("表情包库", imagesSec);
        secMap.put("风格指引", styleSec);
        secMap.put("更新", updateSec);
        secMap.put("引导", guideSec);
        secMap.put("关于", aboutSec);
        sidebar.getSelectionModel().selectedItemProperty().addListener((o, was, is) -> {
            if (is == null) return;
            contentPane.getChildren().setAll(secMap.get(is));
        });
        sidebar.getSelectionModel().select(0);

        HBox root = new HBox(sidebar, contentPane);
        root.setPrefSize(640, 400);
        d.getDialogPane().setContent(root);
        d.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        themeDialog(d);
        d.showAndWait();
    }

    /** 设置对话框里每个分区：标题 + 可选说明 + 内容控件。 */
    private VBox settingsSection(String title, String desc, Node... contents) {
        Label t = new Label(title);
        t.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        VBox box = new VBox(10);
        box.getChildren().add(t);
        if (desc != null && !desc.isBlank()) {
            Label d = new Label(desc);
            d.setWrapText(true);
            d.getStyleClass().add("bar-label");
            d.setMaxWidth(460);
            box.getChildren().add(d);
        }
        for (Node n : contents) box.getChildren().add(n);
        return box;
    }

    /** 简化的版本号比较：x.y.z 按段比，非数字段当 0。 */
    private static int compareVersion(String a, String b) {
        String[] aa = a.split("\\."), bb = b.split("\\.");
        int n = Math.max(aa.length, bb.length);
        for (int i = 0; i < n; i++) {
            int va = 0, vb = 0;
            try { va = i < aa.length ? Integer.parseInt(aa[i].replaceAll("[^0-9]", "")) : 0; } catch (Exception ignored) {}
            try { vb = i < bb.length ? Integer.parseInt(bb[i].replaceAll("[^0-9]", "")) : 0; } catch (Exception ignored) {}
            if (va != vb) return Integer.compare(va, vb);
        }
        return 0;
    }

    /** 用系统默认浏览器打开 URL。 */
    private void openInBrowser(String url) {
        try {
            if (java.awt.Desktop.isDesktopSupported()
                    && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
            } else {
                new ProcessBuilder("cmd", "/c", "start", "", url).start();
            }
        } catch (Exception e) {
            alert("打不开浏览器，URL：\n" + url + "\n\n" + e.getMessage());
        }
    }

    /** 首次使用引导（forceShow=true 时强制显示，不写 seenGuide）。分步交互填资料。 */
    private void showFirstRunGuide(boolean forceShow) {
        Dialog<Void> d = new Dialog<>();
        d.setTitle(forceShow ? "使用引导" : "欢迎使用 " + APP_NAME);
        final int TOTAL = 5;

        Label stepLbl = new Label();
        stepLbl.getStyleClass().add("bar-label");
        Label hint = new Label();
        hint.setWrapText(true);
        hint.setMaxWidth(500);
        VBox inputArea = new VBox(8);
        inputArea.setMinHeight(120);

        Button back = new Button("← 上一步");
        back.getStyleClass().add("btn-ghost");
        Button next = new Button("下一步 →");
        next.getStyleClass().add("btn");
        Button skip = new Button("以后再说");
        skip.getStyleClass().add("btn-ghost");
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        HBox nav = new HBox(8, back, next, sp, skip);
        nav.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(12, stepLbl, hint, inputArea, new Separator(), nav);
        box.setPrefWidth(560);

        int[] s = { 1 };
        Runnable render = () -> {
            stepLbl.setText("步骤 " + s[0] + " / " + TOTAL);
            inputArea.getChildren().clear();
            switch (s[0]) {
                case 1 -> {
                    hint.setText("第一步 · 选服务商 + 填 Key\n\n支持市面上大多数模型服务（OpenAI 兼容协议）。挑一家、申请 Key（一般 platform.<服务商>.com）、粘进来即可。稍后从右上 ⚙ 设置里随时改。");
                    ComboBox<String> wProv = new ComboBox<>();
                    for (Provider p : PROVIDERS) wProv.getItems().add(p.name());
                    wProv.setValue(apiProvider);
                    wProv.getStyleClass().add("combo");
                    TextField wModel = new TextField(modelField.getText());
                    wModel.getStyleClass().add("field");
                    wModel.setPromptText("模型名（自动填）");
                    PasswordField wKey = new PasswordField();
                    wKey.setText(keyField.getText());
                    wKey.setPromptText("sk-…");
                    wKey.getStyleClass().add("field");
                    wProv.setOnAction(e -> {
                        String name = wProv.getValue();
                        if (name == null) return;
                        for (Provider p : PROVIDERS) {
                            if (p.name().equals(name)) {
                                apiProvider = name;
                                if (!"自定义".equals(name)) {
                                    apiUrl = p.url();
                                    if (wModel.getText().isBlank()) wModel.setText(p.defaultModel());
                                }
                                break;
                            }
                        }
                    });
                    wModel.textProperty().addListener((o, was, is) -> modelField.setText(is));
                    wKey.textProperty().addListener((o, was, is) -> keyField.setText(is));
                    GridPane g = new GridPane();
                    g.setHgap(8); g.setVgap(6);
                    g.add(new Label("服务商："), 0, 0); g.add(wProv, 1, 0);
                    g.add(new Label("模型："), 0, 1); g.add(wModel, 1, 1);
                    g.add(new Label("API Key："), 0, 2); g.add(wKey, 1, 2);
                    GridPane.setHgrow(wModel, Priority.ALWAYS);
                    GridPane.setHgrow(wKey, Priority.ALWAYS);
                    wModel.setMaxWidth(Double.MAX_VALUE);
                    wKey.setMaxWidth(Double.MAX_VALUE);
                    inputArea.getChildren().add(g);
                }
                case 2 -> {
                    hint.setText("第二步 · 导入左边人设（聊天窗左栏角色）\n\n选一份 .md 档案。推荐用配套蒸馏 Skill 从聊天记录自动生成，也可以手写一份（首行 # 标题 决定显示名）。");
                    Button pick = new Button("📁 选择左边人设档案…");
                    pick.getStyleClass().add("btn");
                    Label status = new Label();
                    Runnable refresh = () -> status.setText("当前：" + (dataFile("personas/left.md").exists() ? "「" + nameOther + "」✓" : "未导入"));
                    refresh.run();
                    pick.setOnAction(e -> { importPersona(true); refresh.run(); });
                    inputArea.getChildren().addAll(pick, status);
                }
                case 3 -> {
                    hint.setText("第三步 · 导入右边人设（聊天窗右栏角色）");
                    Button pick = new Button("📁 选择右边人设档案…");
                    pick.getStyleClass().add("btn");
                    Label status = new Label();
                    Runnable refresh = () -> status.setText("当前：" + (dataFile("personas/right.md").exists() ? "「" + nameSelf + "」✓" : "未导入"));
                    refresh.run();
                    pick.setOnAction(e -> { importPersona(false); refresh.run(); });
                    inputArea.getChildren().addAll(pick, status);
                }
                case 4 -> {
                    hint.setText("第四步 · 他俩当前是什么关系？\n\nAI 会按这个亲密度说话。例：\n• 高中同学，刚互相表白第 1 天\n• 在一起 3 年的老情侣\n• 刚分手两周还有点别扭");
                    TextField rf = new TextField(relationField.getText());
                    rf.getStyleClass().add("field");
                    rf.setPromptText("当前关系…");
                    rf.textProperty().addListener((o, was, is) -> relationField.setText(is));
                    inputArea.getChildren().addAll(new Label("当前关系："), rf);
                }
                case 5 -> {
                    hint.setText("全设好了 🎉\n\n点「完成」后：\n• 写开场场景 → 点「开始」或「AI 开场」让 AI 起头\n• 点「生成 10 轮」让两个 AI 自由聊\n• 看到很像/不像就 👍/👎，AI 会自己总结成指引\n\n顶栏「时间线」可建多档（同人设不同分支故事）。\n右上 ⚙ 设置 · 🎨 主题。\n\n所有数据只存在程序目录 data/，绝不上传。");
                    inputArea.getChildren().clear();
                }
            }
            back.setDisable(s[0] == 1);
            next.setText(s[0] == TOTAL ? "完成 ✓" : "下一步 →");
        };

        back.setOnAction(e -> { if (s[0] > 1) { s[0]--; render.run(); } });
        next.setOnAction(e -> {
            if (s[0] == TOTAL) {
                if (!forceShow) { seenGuide = true; }
                savePrefs();
                d.setResult(null);
                d.close();
            } else {
                s[0]++;
                render.run();
            }
        });
        skip.setOnAction(e -> {
            if (!forceShow) { seenGuide = true; }
            savePrefs();
            d.setResult(null);
            d.close();
        });

        render.run();
        d.getDialogPane().setContent(box);
        // 必须保留 CLOSE 才能按 ESC 关闭；但把它隐藏掉，避免和我们的 nav 重叠
        d.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        themeDialog(d);
        Node closeButton = d.getDialogPane().lookupButton(ButtonType.CLOSE);
        if (closeButton != null) { closeButton.setVisible(false); closeButton.setManaged(false); }
        d.showAndWait();
    }

    private void showThemeDialog() {
        Dialog<Void> d = new Dialog<>();
        d.setTitle("主题设置");
        d.setHeaderText("选模式 + 色调");

        ToggleGroup modeGroup = new ToggleGroup();
        RadioButton rDay = new RadioButton("白天");
        RadioButton rNight = new RadioButton("黑夜");
        RadioButton rSys = new RadioButton("跟随系统");
        rDay.setToggleGroup(modeGroup); rNight.setToggleGroup(modeGroup); rSys.setToggleGroup(modeGroup);
        switch (themeMode) {
            case "night" -> rNight.setSelected(true);
            case "system" -> rSys.setSelected(true);
            default -> rDay.setSelected(true);
        }
        HBox modeRow = new HBox(12, rDay, rNight, rSys);
        modeRow.setAlignment(Pos.CENTER_LEFT);

        // 色块
        String[] names = {"blue", "pink", "green", "yellow", "red", "purple"};
        String[] labels = {"蓝", "粉", "绿", "黄", "红", "紫"};
        Button[] swatches = new Button[names.length];
        String[] pickedAccent = { themeAccent };
        HBox swatchRow = new HBox(10);
        swatchRow.setAlignment(Pos.CENTER_LEFT);
        for (int i = 0; i < names.length; i++) {
            final String n = names[i];
            Button b = new Button();
            b.setTooltip(new Tooltip("白 + " + labels[i]));
            b.getStyleClass().addAll("swatch", "swatch-" + n);
            if (n.equals(themeAccent)) b.getStyleClass().add("selected");
            b.setOnAction(e -> {
                pickedAccent[0] = n;
                for (Button sb : swatches) sb.getStyleClass().remove("selected");
                b.getStyleClass().add("selected");
            });
            swatches[i] = b;
            swatchRow.getChildren().add(b);
        }

        // 自定义颜色
        ColorPicker cp = new ColorPicker(Color.web(themeCustomColor == null || themeCustomColor.isBlank() ? "#1C4FA0" : themeCustomColor));
        Button customBtn = new Button("自定义");
        customBtn.getStyleClass().add("btn-ghost");
        if ("custom".equals(themeAccent)) customBtn.setStyle("-fx-border-color: -fx-text-base-color;");
        customBtn.setOnAction(e -> {
            pickedAccent[0] = "custom";
            for (Button sb : swatches) sb.getStyleClass().remove("selected");
        });
        HBox customRow = new HBox(8, customBtn, cp);
        customRow.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(10,
                new Label("模式："), modeRow,
                new Label("色调："), swatchRow,
                customRow);
        box.setPrefWidth(420);
        d.getDialogPane().setContent(box);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        themeDialog(d);

        d.setResultConverter(bt -> {
            if (bt != ButtonType.OK) return null;
            String newMode = rNight.isSelected() ? "night" : rSys.isSelected() ? "system" : "day";
            String newAccent = pickedAccent[0];
            String newCustom = themeCustomColor;
            if ("custom".equals(newAccent)) {
                Color c = cp.getValue();
                newCustom = String.format("#%02X%02X%02X",
                        (int) Math.round(c.getRed() * 255),
                        (int) Math.round(c.getGreen() * 255),
                        (int) Math.round(c.getBlue() * 255));
            }
            themeMode = newMode;
            themeAccent = newAccent;
            themeCustomColor = newCustom;
            applyTheme();
            savePrefs();
            return null;
        });
        d.showAndWait();
    }

    private void loadConfig() {
        File f = dataFile("config.json");
        if (f.exists()) {
            try {
                JSONObject o = new JSONObject(Files.readString(f.toPath()));
                keyField.setText(o.optString("apiKey", ""));
                modelField.setText(o.optString("model", "deepseek-chat"));
                legacyRelationship = o.optString("relationship", "");   // 老字段，meta.json 没有时兜底
                corpusPath = o.optString("corpusPath", "");
                corpusLeft = o.optString("corpusLeft", "");
                corpusRight = o.optString("corpusRight", "");
                String tl = o.optString("currentTimeline", "").strip();
                if (!tl.isEmpty()) currentTimeline = tl;
                int sp = o.optInt("flowSpeed", flowSpeed);
                if (sp > 0) {
                    flowSpeed = sp;
                    speedCombo.setValue(flowSpeed + "×");
                }
                themeMode = o.optString("themeMode", themeMode);
                themeAccent = o.optString("themeAccent", themeAccent);
                themeCustomColor = o.optString("themeCustomColor", themeCustomColor);
                seenGuide = o.optBoolean("seenGuide", false);
                autoUpdate = o.optBoolean("autoUpdate", true);
                apiProvider = o.optString("apiProvider", apiProvider);
                apiUrl = o.optString("apiUrl", apiUrl);
                return;
            } catch (Exception ignored) {
            }
        }
        modelField.setText("deepseek-chat");
    }

    private void savePrefs() {
        try {
            File f = dataFile("config.json");
            f.getParentFile().mkdirs();
            JSONObject o = new JSONObject()
                    .put("apiKey", keyField.getText().trim())
                    .put("model", modelField.getText().trim())
                    .put("currentTimeline", currentTimeline)
                    .put("flowSpeed", flowSpeed)
                    .put("themeMode", themeMode)
                    .put("themeAccent", themeAccent)
                    .put("themeCustomColor", themeCustomColor)
                    .put("seenGuide", seenGuide)
                    .put("autoUpdate", autoUpdate)
                    .put("apiProvider", apiProvider)
                    .put("apiUrl", apiUrl)
                    .put("corpusPath", corpusPath)
                    .put("corpusLeft", corpusLeft)
                    .put("corpusRight", corpusRight);
            Files.writeString(f.toPath(), o.toString(2));
        } catch (Exception e) {
            Log.w("CONFIG", "保存失败：" + e.getMessage());
        }
        saveMeta();   // relationship 跟着时间线走，存到 meta.json
    }

    private void saveConfig() { savePrefs(); status.setText("已保存设置"); }

    private void loadCorrections() {
        // 新文件名优先；找不到才回退到老版本的 corrections_plx/ly.json
        if (dataFile("corrections_left.json").exists()) {
            loadCorrFile("corrections_left.json", corrLeft);
        } else {
            loadCorrFile("corrections_plx.json", corrLeft);
        }
        if (dataFile("corrections_right.json").exists()) {
            loadCorrFile("corrections_right.json", corrRight);
        } else {
            loadCorrFile("corrections_ly.json", corrRight);
        }
    }

    private void loadCorrFile(String name, List<String[]> into) {
        File f = dataFile(name);
        if (!f.exists()) return;
        try {
            JSONArray arr = new JSONArray(Files.readString(f.toPath()));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                into.add(new String[]{o.optString("msg", ""), o.optString("note", "")});
            }
        } catch (Exception ignored) {
        }
    }

    private void saveCorrections() {
        saveCorrFile("corrections_left.json", corrLeft);
        saveCorrFile("corrections_right.json", corrRight);
    }

    private void saveCorrFile(String name, List<String[]> from) {
        try {
            File f = dataFile(name);
            f.getParentFile().mkdirs();
            JSONArray arr = new JSONArray();
            for (String[] c : from) arr.put(new JSONObject().put("msg", c[0]).put("note", c[1]));
            Files.writeString(f.toPath(), arr.toString(2));
        } catch (Exception ignored) {
        }
    }

    private void loadTimeline() {
        File f = dataFile("timeline.json");
        if (!f.exists()) return;
        try {
            JSONArray arr = new JSONArray(Files.readString(f.toPath()));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                // 兼容老存档：旧版用 PLX/LY，新版用 L/R
                Conversation.Who w = switch (o.optString("who", "NARR")) {
                    case "L", "LEFT", "PLX" -> Conversation.Who.LEFT;
                    case "R", "RIGHT", "LY" -> Conversation.Who.RIGHT;
                    default -> Conversation.Who.NARRATION;
                };
                convo.add(w, o.optString("text", ""), o.optString("think", ""),
                        o.optString("time", ""), o.optString("imagePath", ""));
            }
            if (!convo.isEmpty()) {
                for (int i = convo.turns().size() - 1; i >= 0; i--) {
                    Conversation.Who w = convo.turns().get(i).who();
                    if (w != Conversation.Who.NARRATION) { current = opposite(w); break; }
                }
                // 恢复模拟时钟到最后一条之后
                for (int i = convo.turns().size() - 1; i >= 0; i--) {
                    String ti = convo.turns().get(i).time();
                    if (ti != null && !ti.isBlank()) {
                        try { simClock = LocalDateTime.parse(ti).plusMinutes(chatGap()); } catch (Exception ignore) {}
                        break;
                    }
                }
            }
            lastFeedbackIdx = convo.turns().size();   // 历史不重复评价
        } catch (Exception ignored) {
        }
    }

    private void saveTimeline() {
        try {
            File f = dataFile("timeline.json");
            f.getParentFile().mkdirs();
            JSONArray arr = new JSONArray();
            for (Conversation.Turn t : convo.turns()) {
                String w = switch (t.who()) {
                    case LEFT -> "L";
                    case RIGHT -> "R";
                    default -> "NARR";
                };
                JSONObject row = new JSONObject().put("who", w).put("text", t.text())
                        .put("think", t.think()).put("time", t.time());
                if (t.isImage()) row.put("imagePath", t.imagePath());
                arr.put(row);
            }
            Files.writeString(f.toPath(), arr.toString());
        } catch (Exception ignored) {
        }
    }

    private void alert(String msg) {
        Alert a = new Alert(Alert.AlertType.WARNING, msg, ButtonType.OK);
        a.setHeaderText(null);
        themeDialog(a);
        a.showAndWait();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
