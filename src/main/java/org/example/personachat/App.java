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
import java.util.List;
import java.util.Optional;
import java.util.Random;

public class App extends Application {

    private String nameOther = "对方";   // 左侧（聊天窗左栏）显示名，导入人设后从档案标题取
    private String nameSelf = "我方";    // 右侧（聊天窗右栏）显示名
    private boolean personasReady = false;
    private static final int WINDOW = 40;          // 只喂模型最近 N 条
    private static final int ROUNDS = 10;          // 一批 10 个来回
    private static final int LEAVE_DELAY = 1;      // 有人说要离开后，再过几条触发事件旁白

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
                           String leaveAfter) {}
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
    private final TextField relationField = new TextField();   // 当前双方关系（可手动改、注入）
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
        content.setTop(new VBox(buildSettingsBar(), buildTimelineBar(), buildScenarioBar(), buildRelationBar()));
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
        stage.setTitle("PersonaChat");
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
                fo.setOnFinished(ev -> { splash.close(); stage.show(); });
                fo.play();
            } else {
                stage.show();
            }
        });
        hold.play();
    }

    /** 启动 splash：无边框透明窗，居中 logo + 名字，渐显。 */
    private Stage showSplash() {
        try {
            Stage sp = new Stage(StageStyle.TRANSPARENT);
            ImageView logo = new ImageView(new Image(getClass().getResourceAsStream("/icon.png"), 180, 180, true, true));
            Label name = new Label("PersonaChat");
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
        Label title = new Label("PersonaChat");
        title.getStyleClass().add("title-text");
        titleSub = new Label("· 双 AI 对话模拟");
        titleSub.getStyleClass().add("title-sub");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button theme = new Button("🎨");
        theme.getStyleClass().add("win-btn");
        theme.setOnAction(e -> showThemeDialog());
        Button min = new Button("—");
        min.getStyleClass().add("win-btn");
        min.setOnAction(e -> stage.setIconified(true));
        Button close = new Button("✕");
        close.getStyleClass().addAll("win-btn", "win-close");
        close.setOnAction(e -> { saveTimeline(); saveMeta(); Platform.exit(); });
        HBox bar = new HBox(8, logo, title, titleSub, spacer, theme, min, close);
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

    private Node buildSettingsBar() {
        keyField.setPromptText("DeepSeek API Key");
        keyField.setPrefWidth(180);
        keyField.getStyleClass().add("field");
        modelField.setPrefWidth(100);
        modelField.getStyleClass().add("field");
        Button save = new Button("保存");
        save.getStyleClass().add("btn-ghost");
        save.setOnAction(e -> saveConfig());
        Button impLeft = new Button("导入左边人设");
        impLeft.getStyleClass().add("btn-ghost");
        impLeft.setOnAction(e -> importPersona(true));
        Button impRight = new Button("导入右边人设");
        impRight.getStyleClass().add("btn-ghost");
        impRight.setOnAction(e -> importPersona(false));
        corpusLabel.getStyleClass().add("corpus-label");
        HBox bar = new HBox(barLabel("Key"), keyField, barLabel("模型"), modelField, save, impLeft, impRight, corpusLabel);
        bar.getStyleClass().add("bar");
        return bar;
    }

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

    /** 当前双方关系栏（常驻、可手动改、生成时注入）。 */
    private Node buildRelationBar() {
        relationField.setPromptText("当前两人的关系/所处阶段，如：高中同学，刚表白在一起第1天…（开始前可改）");
        relationField.getStyleClass().add("field");
        HBox.setHgrow(relationField, Priority.ALWAYS);
        relationField.focusedProperty().addListener((o, was, is) -> { if (was && !is) savePrefs(); });
        HBox bar = new HBox(8, barLabel("当前关系"), relationField);
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
        Button viewStyleBtn = new Button("📋查看总结");
        viewStyleBtn.getStyleClass().add("btn-ghost");
        viewStyleBtn.setOnAction(e -> showStyleSummary());

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
        status.getStyleClass().add("status-label");

        HBox row1 = new HBox(8, genBtn, contBtn, stopBtn, clearBtn, negBtn, posBtn, viewStyleBtn);
        row1.setAlignment(Pos.CENTER_LEFT);
        HBox row2 = new HBox(8, interField, leftBtn, rightBtn, narrBtn);
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
                String raw = DeepSeekClient.chat(key, model, msgs);
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
        Conversation.Turn t = convo.add(p.who(), p.msg(), p.think(), p.due().toString());
        lastMsgTime = p.due();
        addBubble(t);
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
    private void enqueueGen(Gen g, Conversation.Who who) {
        String[] parts = splitBurst(g.msg());
        if (parts.length == 0) { stopRun("生成出来全是空气泡，已停。"); return; }
        LocalDateTime t = lastMsgTime.plusMinutes(g.gap());
        for (int i = 0; i < parts.length; i++) {
            boolean last = (i == parts.length - 1);
            String think = (i == 0) ? g.think() : "";
            String leave = last ? g.leave() : null;
            pendingQueue.add(new Pending(who, parts[i], think, t, last, last ? g.next() : null, leave));
            if (!last) t = t.plusSeconds(5 + rnd.nextInt(20));
        }
        pendingDue = pendingQueue.peek().due();   // 仅保留：兼容外部观测
        pendingGen = g;
        Log.w("RUN", "已备 " + name(who) + " 隔" + g.gap() + "分 共" + parts.length + "条 起 → " + fmtTimeSec(pendingQueue.peek().due()));
        // 时钟已越过队首，立即逐条揭示
        while (!pendingQueue.isEmpty() && !simClock.isBefore(pendingQueue.peek().due())) {
            revealOne(pendingQueue.poll());
            if (!busy) return;
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
            String raw = DeepSeekClient.chat(key, model, msgs);
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
        String system = side.buildSystem(correctionsFor(who), snippets)
                + "\n【上一条的时间】上一条消息发生在模拟时间 " + fmtTime(stampNow()) + "（" + period(simClock.getHour())
                + "）。你自己决定隔多久再发这条（写在【隔】里），按落到的新时间点该有的状态说话（深夜会困、早上刚醒、饭点提吃饭）。"
                + (runRelation.isEmpty() ? "" : "\n【当前你俩的关系】" + runRelation + "（严格按这个关系阶段和亲密度说话，别超前也别倒退）")
                + styleGuidance();
        Log.w("GEN", name(who) + " 上一条时间=" + fmtTime(stampNow()) + " query=" + Log.cut(query, 40) + " 检索片段=" + snippets.length() + "字");
        JSONArray msgs = convo.buildMessages(system, who, WINDOW);
        String raw = "";
        for (int attempt = 1; attempt <= 3 && !stopReq; attempt++) {
            raw = DeepSeekClient.chat(key, model, msgs);
            if (!raw.isBlank()) break;
            Log.w("GEN", name(who) + " 第" + attempt + "次空返回，重试");
        }
        if (raw.isBlank()) return null;   // 连续空返回，交给调用方暂停

        String gapStr = section(raw, "【隔】", "【心理】").replaceAll("[^0-9]", "");
        int gap;
        try { gap = gapStr.isEmpty() ? chatGap() : Math.min(2880, Integer.parseInt(gapStr)); }
        catch (Exception e) { gap = chatGap(); }
        String think = clean(section(raw, "【心理】", "【消息】"));
        String msg = clean(section(raw, "【消息】", "【离开】"));
        String leave = clean(section(raw, "【离开】", "【轮到】"));
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

    /** 标记某条说错了：记成该角色的纠正，并把这条从时间线删掉。 */
    private void flagWrong(Conversation.Turn t) {
        if (busy) return;
        TextInputDialog d = new TextInputDialog();
        d.setTitle("纠正 " + name(t.who()));
        d.setHeaderText("这句不对在哪？想让 " + name(t.who()) + " 以后怎么说？");
        d.setContentText("说明：");
        themeDialog(d);
        Optional<String> r = d.showAndWait();
        if (r.isEmpty() || r.get().isBlank()) return;
        correctionsFor(t.who()).add(new String[]{t.text(), r.get().trim()});
        Log.w("CORRECT", name(t.who()) + "：「" + Log.cut(t.text(), 40) + "」→ " + Log.cut(r.get().trim(), 60));
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
                String raw = DeepSeekClient.chat(key, model, msgs);
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
        Label msg = new Label(t.text());
        msg.setWrapText(true);
        msg.setMaxWidth(300);
        msg.getStyleClass().add(left ? "bubble-other" : "bubble-self");
        VBox msgV = new VBox(2, meta, msg);
        msgV.setAlignment(left ? Pos.CENTER_LEFT : Pos.CENTER_RIGHT);
        Button x = new Button("✗");
        x.getStyleClass().add("flag-btn");
        x.setOnAction(e -> flagWrong(t));
        HBox msgCell = new HBox(4);
        msgCell.setAlignment(left ? Pos.CENTER_LEFT : Pos.CENTER_RIGHT);
        if (left) msgCell.getChildren().addAll(msgV, x);
        else msgCell.getChildren().addAll(x, msgV);

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
            if (!msg.getStyleClass().contains("linked")) msg.getStyleClass().add("linked");
            if (!tht.getStyleClass().contains("linked")) tht.getStyleClass().add("linked");
        };
        Runnable off = () -> { msg.getStyleClass().remove("linked"); tht.getStyleClass().remove("linked"); };
        msg.setOnMouseEntered(e -> on.run());
        msg.setOnMouseExited(e -> off.run());
        tht.setOnMouseEntered(e -> on.run());
        tht.setOnMouseExited(e -> off.run());

        // 两栏各占一半
        msgCell.setPrefWidth(0);
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
        TextInputDialog d = new TextInputDialog();
        d.setTitle("新建时间线");
        d.setHeaderText("给新时间线起个名字（会自动建文件夹）");
        d.setContentText("名字：");
        themeDialog(d);
        Optional<String> r = d.showAndWait();
        if (r.isEmpty()) return;
        String name = sanitizeTimelineName(r.get());
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
                convo.add(w, o.optString("text", ""), o.optString("think", ""), o.optString("time", ""));
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
                arr.put(new JSONObject().put("who", w).put("text", t.text()).put("think", t.think()).put("time", t.time()));
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
