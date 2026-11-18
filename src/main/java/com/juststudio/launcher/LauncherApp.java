package com.juststudio.launcher;

import javafx.animation.FadeTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.text.Font;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.awt.Desktop;
import java.awt.AWTException;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.prefs.Preferences;
import java.util.concurrent.TimeUnit;

public class LauncherApp extends Application {
    private final Path projectDir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    private Path gameDir = Path.of(System.getenv().getOrDefault("APPDATA", System.getProperty("user.home")), ".minecraft");
    private final Preferences prefs = Preferences.userNodeForPackage(LauncherApp.class);
    private final TextField nickname = new TextField();
    private final TextField javaPath = new TextField();
    private final Slider ramSlider = new Slider(2, 12, 4);
    private final Label ramValue = new Label("4 ГБ");
    private final Label status = new Label("Готов к запуску");
    private final Label versionCount = new Label("0 установлено");
    private final TextArea console = new TextArea();
    private final ProgressBar downloadBar = new ProgressBar(0);
    private final Label downloadInfo = new Label("Ожидание загрузки");
    private final Label downloadTitle = new Label("ЗАГРУЗКА MINECRAFT");
    private final Label selectedVersionLabel = new Label("Не выбрана");
    private final Button play = new Button("▶   СТАРТ");
    private final StackPane content = new StackPane();
    private final Button[] nav = new Button[3];
    private final String[] navIconFiles = {"/icons/home.png", "/icons/settings.png", "/icons/about.png"};
    private final List<String> installed = new ArrayList<>();
    private final List<String> available = new ArrayList<>();
    private final java.util.Map<String, Boolean> compatibleComponents = new java.util.LinkedHashMap<>();
    private final java.util.Map<String, CheckBox> componentChecks = new java.util.LinkedHashMap<>();
    private ListView<String> versionListView;
    private VBox componentPanel;
    private Stage stage;
    private boolean running;
    private boolean consoleVisible;
    private boolean installing;
    private boolean navExpanded;
    private long launchStartedAt;
    private final List<String> launchOutput = new ArrayList<>();
    private String selectedVersion = "";
    private String pendingVersion;
    private VBox selectorOverlay;
    private StackPane homeRoot;
    private VBox consoleDrawer;
    private TextField gamePathField;
    private Button versionPill;
    private Button clientPill;
    private VBox clientOverlay;
    private Region clientBackdrop;
    private String activeClientId = "default";
    private final List<String> clientIds = new ArrayList<>();
    private static final String CLIENTS_KEY = "clientIds";
    private Button navToggle;
    private VBox sidebar;
    private TrayIcon trayIcon;
    private final ToggleGroup launchWindowGroup = new ToggleGroup();

    @Override public void start(Stage stage) {
        this.stage = stage;
        Platform.setImplicitExit(false);
        stage.setTitle("Just Studio Launcher");
        stage.getIcons().add(new Image(getClass().getResourceAsStream("/juststudio-app-icon.png")));
        stage.setMinWidth(850);
        stage.setMinHeight(540);
        stage.setWidth(1050);
        stage.setHeight(680);
        stage.setResizable(true);

        try (var fontStream = getClass().getResourceAsStream("/fonts/minecraft.ttf")) {
            if (fontStream != null) {
                Font loaded = Font.loadFont(fontStream, 12);
                if (loaded != null) System.setProperty("juststudio.font.family", loaded.getFamily());
            }
        } catch (Exception ignored) {}
        nickname.setText(prefs.get("nickname", "Player"));
        selectedVersion = prefs.get("version", "");
        String savedPath = prefs.get("gameDir", "");
        if (!savedPath.isBlank()) {
            try { gameDir = Path.of(savedPath); } catch (Exception ignored) {}
        }
        javaPath.setText(prefs.get("javaPath", ""));
        loadClientProfiles();

        BorderPane root = new BorderPane();
        root.getStyleClass().add("root");
        root.setLeft(sidebar());
        root.setCenter(mainArea());

        Scene scene = new Scene(root, 1050, 680);
        scene.getStylesheets().add(getClass().getResource("/style.css").toExternalForm());
        stage.setScene(scene);
        stage.show();
        stage.centerOnScreen();
        stage.setOnCloseRequest(e -> {
            if (trayIcon != null && SystemTray.isSupported()) SystemTray.getSystemTray().remove(trayIcon);
            Platform.exit();
        });

        nickname.textProperty().addListener((o, a, b) -> { prefs.put("nickname", b); saveActiveClient(); });
        javaPath.textProperty().addListener((o, a, b) -> { prefs.put("javaPath", b); saveActiveClient(); });
        ramSlider.valueProperty().addListener((o, a, b) -> {
            int value = (int) Math.round(b.doubleValue());
            ramValue.setText(value + " ГБ");
            prefs.putInt("ram", value);
            saveActiveClient();
        });
        ramSlider.setValue(clientNode(activeClientId).getInt("ram", prefs.getInt("ram", 4)));

        append("Just Studio Launcher 1.0");
        append("Minecraft engine: minecraft-launcher-lib");
        showStartupScreen(scene);
    }

    private void showStartupScreen(Scene mainScene) {
        Label title = new Label("JUST STUDIO LAUNCHER");
        title.getStyleClass().add("startup-title");
        Label subtitle = new Label("ПОДГОТОВКА К ЗАПУСКУ");
        subtitle.getStyleClass().add("startup-subtitle");
        Label current = new Label("Проверка Java...");
        current.getStyleClass().add("startup-current");
        ProgressBar progress = new ProgressBar(0);
        progress.getStyleClass().add("startup-progress");
        progress.setMaxWidth(Double.MAX_VALUE);
        Label details = new Label("Пожалуйста, подождите");
        details.getStyleClass().add("startup-details");
        VBox panel = new VBox(15, title, subtitle, current, progress, details);
        panel.getStyleClass().add("startup-panel");
        panel.setMaxWidth(560);
        panel.setAlignment(Pos.CENTER_LEFT);
        StackPane splash = new StackPane(panel);
        splash.getStyleClass().add("startup-screen");
        Scene splashScene = new Scene(splash, 1050, 680);
        splashScene.getStylesheets().add(getClass().getResource("/style.css").toExternalForm());
        stage.setScene(splashScene);
        stage.show();

        Thread bootstrap = new Thread(() -> {
            boolean javaAvailable = false;
            String javaDescription = "Java не найдена";
            try {
                Platform.runLater(() -> current.setText("Проверка Java...") );
                String configured = javaPath.getText() == null ? "" : javaPath.getText().trim();
                List<String> javaCommand = new ArrayList<>();
                if (!configured.isBlank() && Files.isRegularFile(Path.of(configured))) {
                    String executable = configured;
                    if (executable.toLowerCase(Locale.ROOT).endsWith("javaw.exe"))
                        executable = executable.substring(0, executable.length() - "javaw.exe".length()) + "java.exe";
                    javaCommand.add(executable);
                } else javaCommand.add("java");
                javaCommand.add("-version");
                Process javaProcess = new ProcessBuilder(javaCommand).redirectErrorStream(true).start();
                StringBuilder javaOutput = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(javaProcess.getInputStream(), StandardCharsets.UTF_8))) {
                    String line; while ((line = reader.readLine()) != null) javaOutput.append(line).append(' ');
                }
                if (javaProcess.waitFor(8, TimeUnit.SECONDS) && javaProcess.exitValue() == 0) {
                    javaAvailable = true;
                    javaDescription = javaOutput.toString().trim();
                    if (javaDescription.isBlank()) javaDescription = "Java доступна";
                }
            } catch (Exception ignored) {}
            final boolean javaOk = javaAvailable;
            final String javaInfo = javaDescription;
            Platform.runLater(() -> { progress.setProgress(0.18); current.setText("Загрузка игровых клиентов..."); details.setText("Проверка сохранённых профилей"); });

            int validClients = 0;
            for (String id : new ArrayList<>(clientIds)) {
                try { clientNode(id).get("name", ""); validClients++; } catch (Exception ignored) {}
            }
            final int clientsLoaded = validClients;
            Platform.runLater(() -> { progress.setProgress(0.38); details.setText("Найдено клиентов: " + clientsLoaded); current.setText("Обновление установленных версий..."); });

            EngineResult installedResult = runStartupEngine("list");
            Platform.runLater(() -> { progress.setProgress(0.62); details.setText("Установленных версий: " + installedResult.versions.size()); current.setText("Обновление каталога версий..."); });
            EngineResult catalogResult = runStartupEngine("catalog");

            Platform.runLater(() -> {
                installed.clear(); installed.addAll(installedResult.versions);
                available.clear(); available.addAll(catalogResult.versions);
                versionCount.setText(installed.size() + " установлено");
                if (selectedVersion.isBlank() && !installed.isEmpty()) setSelectedVersion(installed.get(0));
                else updatePlayState();
                if (!javaOk) status.setText("Java не обнаружена — проверь настройки Java");
                else status.setText("Проверка Java завершена: " + javaInfo);
                append("Проверка Java: " + (javaOk ? javaInfo : "не удалось запустить Java"));
                append("Клиентов загружено: " + clientsLoaded);
                append("Установленных версий: " + installed.size());
                append("Версий в каталоге: " + available.size());
                progress.setProgress(1);
                current.setText("Подготовка завершена");
                details.setText("Запуск Just Studio Launcher...");

                if (catalogResult.exitCode != 0 || catalogResult.versions.isEmpty()) {
                    Alert offline = new Alert(Alert.AlertType.WARNING);
                    offline.initOwner(stage);
                    offline.setTitle("Нет подключения к интернету");
                    offline.setHeaderText("Не удалось обновить список версий Minecraft");
                    offline.setContentText("Лаунчер продолжит работу в автономном режиме. Установленные версии и клиенты останутся доступны.");
                    offline.getButtonTypes().setAll(ButtonType.OK);
                    offline.showAndWait();
                }
                stage.setScene(mainScene);
                stage.centerOnScreen();
                if (available.isEmpty()) append("Каталог версий недоступен. Проверь подключение к интернету.");
                if (installedResult.exitCode != 0) append("Не удалось прочитать список установленных версий.");
            });
        }, "launcher-startup-check");
        bootstrap.setDaemon(true);
        bootstrap.start();
    }

    private static class EngineResult {
        final List<String> versions = new ArrayList<>();
        int exitCode = -1;
    }

    private EngineResult runStartupEngine(String action) {
        EngineResult result = new EngineResult();
        final Path operationGameDir = gameDir.toAbsolutePath();
        try {
            List<String> command = pythonCommand();
            command.add(projectDir.resolve("engine/minecraft_engine.py").toString());
            command.add(action);
            command.add(operationGameDir.toString());
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(projectDir.toFile());
            builder.redirectErrorStream(true);
            builder.environment().put("PYTHONIOENCODING", "utf-8");
            Process process = builder.start();
            Thread outputReader = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("VERSION|")) {
                            String version = line.substring(8).trim();
                            if (!version.isBlank() && !result.versions.contains(version)) result.versions.add(version);
                        }
                    }
                } catch (Exception ignored) {}
            }, "launcher-startup-" + action + "-reader");
            outputReader.setDaemon(true);
            outputReader.start();
            if (process.waitFor(60, TimeUnit.SECONDS)) {
                result.exitCode = process.exitValue();
                outputReader.join(2000);
            } else {
                process.destroyForcibly();
                result.exitCode = -1;
                outputReader.join(1000);
            }
        } catch (Exception e) {
            result.exitCode = -1;
        }
        return result;
    }

    private VBox sidebar() {
        sidebar = new VBox(10);
        sidebar.getStyleClass().add("sidebar");
        sidebar.setPadding(new Insets(15, 9, 14, 9));
        sidebar.setPrefWidth(76);
        sidebar.setMinWidth(76);
        sidebar.setMaxWidth(205);

        ImageView logo = new ImageView(new Image(getClass().getResourceAsStream("/juststudio-logo.png")));
        logo.setFitWidth(54);
        logo.setFitHeight(54);
        logo.setPreserveRatio(true);
        javafx.scene.shape.Rectangle logoClip = new javafx.scene.shape.Rectangle(54, 54);
        logoClip.setArcWidth(18);
        logoClip.setArcHeight(18);
        logo.setClip(logoClip);
        logo.getStyleClass().add("brand-logo");
        navToggle = new Button("☰");
        navToggle.getStyleClass().add("nav-toggle");
        navToggle.setMaxWidth(Double.MAX_VALUE);
        navToggle.setTooltip(new Tooltip("Показать названия разделов"));
        navToggle.setOnAction(e -> toggleNavigation());

        VBox identity = new VBox(8, logo, styled("Just Studio", "brand-name"));
        identity.setAlignment(Pos.CENTER);
        identity.setPadding(new Insets(7, 0, 15, 0));

        nav[0] = navButton("", "Главная", 0);
        nav[1] = navButton("", "Настройки", 1);
        nav[2] = navButton("", "О лаунчере", 2);

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);
        Label footer = new Label("JS");
        footer.getStyleClass().add("sidebar-mini-footer");
        sidebar.getChildren().addAll(identity, navToggle, nav[0], nav[1], nav[2], spacer, footer);
        return sidebar;
    }

    private Button navButton(String icon, String title, int index) {
        Button b = new Button();
        ImageView image = new ImageView(new Image(getClass().getResourceAsStream(navIconFiles[index])));
        image.setFitWidth(30);
        image.setFitHeight(30);
        image.setPreserveRatio(true);
        image.setSmooth(true);
        b.setGraphic(image);
        b.setContentDisplay(ContentDisplay.LEFT);
        b.getStyleClass().add("nav-button");
        b.setMaxWidth(Double.MAX_VALUE);
        b.setAlignment(Pos.CENTER);
        b.setTooltip(new Tooltip(title));
        b.setOnAction(e -> showPage(index));
        b.setUserData(title);
        return b;
    }

    private void toggleNavigation() {
        navExpanded = !navExpanded;
        double target = navExpanded ? 205 : 76;
        sidebar.setMinWidth(76);
        sidebar.setMaxWidth(205);
        javafx.animation.Timeline resize = new javafx.animation.Timeline(
            new javafx.animation.KeyFrame(Duration.millis(220),
                new javafx.animation.KeyValue(sidebar.prefWidthProperty(), target, javafx.animation.Interpolator.EASE_BOTH))
        );
        resize.play();

        for (int i = 0; i < nav.length; i++) {
            Button button = nav[i];
            button.setText(navExpanded ? "    " + button.getUserData() : "");
            button.setAlignment(navExpanded ? Pos.CENTER_LEFT : Pos.CENTER);
            button.setTooltip(navExpanded ? null : new Tooltip(String.valueOf(button.getUserData())));
            button.setTranslateX(navExpanded ? -5 : 0);
            FadeTransition labelFade = new FadeTransition(Duration.millis(180), button);
            labelFade.setFromValue(0.72);
            labelFade.setToValue(1);
            labelFade.play();
        }
        navToggle.setText(navExpanded ? "‹" : "☰");
        navToggle.setTooltip(new Tooltip(navExpanded ? "Свернуть меню" : "Показать разделы"));
        FadeTransition fade = new FadeTransition(Duration.millis(180), sidebar);
        fade.setFromValue(0.88);
        fade.setToValue(1);
        fade.play();
    }

    private VBox mainArea() {
        VBox box = new VBox(9);
        box.getStyleClass().add("main-area");
        box.setPadding(new Insets(10));

        HBox top = new HBox(9);
        top.setAlignment(Pos.CENTER_LEFT);
        Button consoleButton = new Button();
        ImageView consoleImage = new ImageView(new Image(getClass().getResourceAsStream("/icons/console.png")));
        consoleImage.setFitWidth(28);
        consoleImage.setFitHeight(28);
        consoleImage.setPreserveRatio(true);
        consoleButton.setGraphic(consoleImage);
        consoleButton.getStyleClass().add("console-icon");
        consoleButton.setTooltip(new Tooltip("Консоль лаунчера"));
        consoleButton.setOnAction(e -> toggleConsole());
        ImageView wordmark = new ImageView(new Image(getClass().getResourceAsStream("/juststudio-wordmark.png")));
        wordmark.setFitWidth(132);
        wordmark.setFitHeight(42);
        wordmark.setPreserveRatio(true);
        wordmark.setSmooth(true);
        Region push = new Region();
        HBox.setHgrow(push, Priority.ALWAYS);
        Label build = new Label("RELEASE 1.0");
        build.getStyleClass().add("build-label");
        top.getChildren().addAll(consoleButton, wordmark, push, build);

        console.setEditable(false);
        console.setWrapText(true);
        console.getStyleClass().add("console");
        HBox consoleHead = new HBox(new Label("КОНСОЛЬ ЛАУНЧЕРА"), spacer(), closeConsoleButton());
        consoleHead.setAlignment(Pos.CENTER_LEFT);
        consoleDrawer = new VBox(7, consoleHead, console);
        consoleDrawer.getStyleClass().add("console-drawer");
        consoleDrawer.setPadding(new Insets(10));
        consoleDrawer.setMaxHeight(170);
        consoleDrawer.setVisible(false);
        consoleDrawer.setManaged(false);

        VBox.setVgrow(content, Priority.ALWAYS);
        HBox footer = new HBox(9, new Label("●"), status);
        footer.getStyleClass().add("status-bar");
        footer.setAlignment(Pos.CENTER_LEFT);

        StackPane work = new StackPane(content, consoleDrawer);
        work.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_CLICKED, e -> {
            if (consoleVisible && !isInside(consoleDrawer, e.getTarget())) toggleConsole();
        });
        StackPane.setAlignment(consoleDrawer, Pos.TOP_LEFT);
        StackPane.setMargin(consoleDrawer, new Insets(2));
        VBox.setVgrow(work, Priority.ALWAYS);
        box.getChildren().addAll(top, work, footer);
        showPage(0);
        return box;
    }

    private Region spacer() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    private Button closeConsoleButton() {
        Button b = new Button("×");
        b.getStyleClass().add("tiny-button");
        b.setOnAction(e -> toggleConsole());
        return b;
    }

    private void toggleConsole() {
        consoleVisible = !consoleVisible;
        consoleDrawer.setVisible(consoleVisible);
        consoleDrawer.setManaged(consoleVisible);
        if (consoleVisible) {
            FadeTransition fade = new FadeTransition(Duration.millis(150), consoleDrawer);
            fade.setFromValue(0);
            fade.setToValue(1);
            fade.play();
        }
    }

    private void showPage(int index) {
        for (int i = 0; i < nav.length; i++) nav[i].getStyleClass().remove("active");
        nav[index].getStyleClass().add("active");

        Node page = switch (index) {
            case 1 -> settingsPage();
            case 2 -> aboutPage();
            default -> homePage();
        };
        content.getChildren().setAll(page);
        page.setOpacity(0);
        page.setTranslateY(12);
        javafx.animation.FadeTransition fade = new FadeTransition(Duration.millis(220), page);
        fade.setFromValue(0);
        fade.setToValue(1);
        javafx.animation.TranslateTransition slide = new javafx.animation.TranslateTransition(Duration.millis(220), page);
        slide.setFromY(12);
        slide.setToY(0);
        slide.setInterpolator(javafx.animation.Interpolator.EASE_OUT);
        new javafx.animation.ParallelTransition(fade, slide).play();
    }

    private Node homePage() {
        homeRoot = new StackPane();
        VBox page = new VBox(11);
        page.getStyleClass().add("home-page");

        StackPane hero = new StackPane();
        hero.getStyleClass().add("hero");
        javafx.scene.shape.Rectangle heroClip = new javafx.scene.shape.Rectangle();
        heroClip.widthProperty().bind(hero.widthProperty());
        heroClip.heightProperty().bind(hero.heightProperty());
        heroClip.setArcWidth(30);
        heroClip.setArcHeight(30);
        hero.setClip(heroClip);
        VBox heroCopy = new VBox(7,
            styled("MINECRAFT", "hero-title"),
            styled("ТВОЙ МИР. ТВОИ ПРАВИЛА.", "hero-subtitle"),
            styled("ИГРАЙ ВМЕСТЕ С JUST STUDIO", "hero-caption"));
        Region shade = new Region();
        shade.getStyleClass().add("hero-shade");
        hero.getChildren().addAll(shade, heroCopy);
        StackPane.setAlignment(shade, Pos.CENTER);
        StackPane.setAlignment(heroCopy, Pos.TOP_LEFT);
        StackPane.setMargin(heroCopy, new Insets(24, 24, 0, 25));
        VBox.setVgrow(hero, Priority.ALWAYS);

        VBox controls = new VBox(9);
        controls.getStyleClass().add("quick-panel");

        HBox actionRow = new HBox(9);
        actionRow.setAlignment(Pos.CENTER_LEFT);
        clientPill = new Button(clientName(activeClientId) + "  ▾");
        clientPill.getStyleClass().add("version-pill");
        clientPill.setTooltip(new Tooltip("Выбрать или настроить игровой клиент"));
        clientPill.setOnAction(e -> openClientManager());
        Label versionCaption = styled("ВЕРСИЯ", "eyebrow");
        versionPill = new Button(selectedVersion.isBlank() ? "ВЫБРАТЬ ВЕРСИЮ  ▾" : selectedVersion + "  ▾");
        versionPill.getStyleClass().add("version-pill");
        versionPill.setOnAction(e -> openVersionSelector());

        nickname.setPromptText("Игровой ник");
        nickname.getStyleClass().add("home-nickname");
        nickname.setPrefWidth(155);
        nickname.setMinWidth(120);
        nickname.setMaxWidth(190);

        Button gameFolder = quickButton("/icons/folder.png", "Открыть папку игры");
        gameFolder.setOnAction(e -> openFolder(gameDir));
        Button modsFolder = quickButton("/icons/mods.png", "Открыть папку модов");
        modsFolder.setOnAction(e -> openFolder(gameDir.resolve("mods")));
        Button packsFolder = quickButton("/icons/resourcepacks.png", "Открыть папку ресурспаков");
        packsFolder.setOnAction(e -> openFolder(gameDir.resolve("resourcepacks")));

        actionRow.getChildren().addAll(clientPill, versionCaption, versionPill, nickname, gameFolder, modsFolder, packsFolder);
        controls.getChildren().add(actionRow);

        downloadBar.setMaxWidth(Double.MAX_VALUE);
        downloadBar.getStyleClass().add("download-bar");
        VBox downloadPanel = new VBox(6, downloadTitle, downloadBar, downloadInfo);
        downloadPanel.getStyleClass().add("download-panel");
        downloadPanel.setVisible(installing);
        downloadPanel.setManaged(installing);

        HBox launchRow = new HBox(12);
        launchRow.setAlignment(Pos.CENTER_RIGHT);
        selectedVersionLabel.getStyleClass().setAll("launch-version");
        selectedVersionLabel.setText(selectedVersion.isBlank() ? "СНАЧАЛА ВЫБЕРИ ВЕРСИЮ" : "ВЫБРАНА ВЕРСИЯ: " + selectedVersion);
        play.getStyleClass().setAll("play-button");
        play.setPrefWidth(250);
        play.setPrefHeight(58);
        play.setOnAction(e -> launchGame());
        updatePlayState();
        launchRow.getChildren().addAll(selectedVersionLabel, play);

        page.getChildren().addAll(hero, controls, downloadPanel, launchRow);
        homeRoot.getChildren().add(page);
        return homeRoot;
    }

    private Button quickButton(String iconResource, String title) {
        Button b = new Button();
        ImageView icon = new ImageView(new Image(getClass().getResourceAsStream(iconResource)));
        icon.setFitWidth(23);
        icon.setFitHeight(23);
        icon.setPreserveRatio(true);
        b.setGraphic(icon);
        b.getStyleClass().add("quick-button");
        b.setTooltip(new Tooltip(title));
        b.setPrefSize(50, 44);
        b.setMinSize(50, 44);
        b.setMaxSize(50, 44);
        b.setAlignment(Pos.CENTER);
        return b;
    }

    private Node folderPage(String title, String folder) {
        VBox page = new VBox(14);
        VBox panel = panel(title.toUpperCase(Locale.ROOT));
        Path dir = gameDir.resolve(folder);
        panel.getChildren().addAll(styled(dir.toString(), "muted"),
            styled("Папка Minecraft для пользовательских файлов.", "muted"));
        Button open = new Button("ОТКРЫТЬ ПАПКУ");
        open.getStyleClass().add("primary-small");
        open.setOnAction(e -> openFolder(dir));
        panel.getChildren().add(open);
        page.getChildren().addAll(heading(title, "Быстрый доступ к файлам Minecraft"), panel);
        return page;
    }

    private Node aboutPage() {
        VBox page = new VBox(14);
        VBox info = panel("JUST STUDIO LAUNCHER");
        info.getChildren().addAll(
            styled("Версия 1.0 Release", "card-title"),
            styled("Лаунчер для установки и запуска Minecraft с удобным управлением игровыми версиями.", "muted"),
            styled("ВОЗМОЖНОСТИ", "eyebrow"),
            styled("• Установка и запуск Minecraft\n• Управление установленными версиями\n• Поддержка Forge, Fabric, OptiFine и NeoForge (если доступно для выбранной версии)\n• Настройка Java, оперативной памяти и папки игры", "muted"),
            styled("Разработчик: Just Studio", "muted"),
            styled("© Just Studio", "muted")
        );
        page.getChildren().addAll(heading("О лаунчере", "Информация о Just Studio Launcher"), info);
        return page;
    }

    private Node settingsPage() {
        VBox page = new VBox(14);
        VBox settings = panel("ПАРАМЕТРЫ ЗАПУСКА");

        Label ramTitle = field("Оперативная память");
        ramSlider.setShowTickLabels(true);
        ramSlider.setShowTickMarks(true);
        ramSlider.setMajorTickUnit(2);
        ramSlider.setMinorTickCount(1);
        ramSlider.setSnapToTicks(true);
        ramSlider.getStyleClass().add("ram-slider");

        HBox ramHeader = new HBox(ramTitle, spacer(), ramValue);
        ramHeader.setAlignment(Pos.CENTER_LEFT);

        javaPath.setPromptText("Автоматически (Java из PATH)");
        javaPath.getStyleClass().add("path-field");
        Button javaBrowse = new Button("ОБЗОР");
        javaBrowse.getStyleClass().add("secondary-button");
        javaBrowse.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Выберите java.exe или javaw.exe");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Java executable", "java.exe", "javaw.exe"));
            var selected = chooser.showOpenDialog(stage);
            if (selected != null) javaPath.setText(selected.getAbsolutePath());
        });
        HBox javaRow = new HBox(8, javaPath, javaBrowse);
        HBox.setHgrow(javaPath, Priority.ALWAYS);

        launchWindowGroup.getToggles().clear();
        Label launchBehaviorTitle = field("После запуска Minecraft");
        RadioButton minimizeOption = new RadioButton("Сворачивать лаунчер");
        RadioButton closeOption = new RadioButton("Закрывать лаунчер");
        RadioButton hideOption = new RadioButton("Скрывать в системный трей");
        for (RadioButton option : List.of(minimizeOption, closeOption, hideOption)) {
            option.setToggleGroup(launchWindowGroup);
            option.getStyleClass().add("launch-option");
        }
        String savedBehavior = prefs.get("launchWindowBehavior", "minimize");
        switch (savedBehavior) {
            case "close" -> closeOption.setSelected(true);
            case "hide" -> hideOption.setSelected(true);
            default -> minimizeOption.setSelected(true);
        }
        minimizeOption.setOnAction(e -> prefs.put("launchWindowBehavior", "minimize"));
        closeOption.setOnAction(e -> prefs.put("launchWindowBehavior", "close"));
        hideOption.setOnAction(e -> prefs.put("launchWindowBehavior", "hide"));
        VBox launchBehavior = new VBox(8, launchBehaviorTitle, minimizeOption, closeOption, hideOption);
        launchBehavior.getStyleClass().add("launch-behavior");

        settings.getChildren().addAll(ramHeader, ramSlider, field("Java (необязательно)"), javaRow,
            field("Папка Minecraft"), directoryPicker(), launchBehavior,
            styled("Ник задаётся на главном экране. RAM, Java, папка и поведение окна сохраняются автоматически.", "muted"));
        page.getChildren().addAll(heading("Настройки", "Параметры запуска Minecraft"), settings);
        return page;
    }

    private VBox directoryPicker() {
        gamePathField = new TextField(gameDir.toString());
        gamePathField.getStyleClass().add("path-field");
        gamePathField.textProperty().addListener((o, a, b) -> {
            try {
                gameDir = Path.of(b);
                prefs.put("gameDir", b);
                saveActiveClient();
            } catch (Exception ignored) {}
        });
        Button browse = new Button("ОБЗОР");
        browse.getStyleClass().add("secondary-button");
        browse.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Выберите папку Minecraft");
            if (Files.isDirectory(gameDir)) chooser.setInitialDirectory(gameDir.toFile());
            var selected = chooser.showDialog(stage);
            if (selected != null) gamePathField.setText(selected.getAbsolutePath());
        });
        HBox row = new HBox(8, gamePathField, browse);
        HBox.setHgrow(gamePathField, Priority.ALWAYS);
        return new VBox(row);
    }

    private void openFolder(Path folder) {
        try {
            Files.createDirectories(folder);
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(folder.toFile());
        } catch (Exception e) {
            status.setText("Не удалось открыть папку");
            append("Ошибка открытия папки: " + e.getMessage());
        }
    }

    private void loadClientProfiles() {
        clientIds.clear();
        String raw = prefs.get(CLIENTS_KEY, "default");
        for (String id : raw.split(",")) if (!id.isBlank() && !clientIds.contains(id)) clientIds.add(id);
        if (clientIds.isEmpty()) clientIds.add("default");
        activeClientId = prefs.get("activeClient", "default");
        if (!clientIds.contains(activeClientId)) activeClientId = clientIds.get(0);
        Preferences node = clientNode(activeClientId);
        if (activeClientId.equals("default") && node.get("gameDir", "").isBlank()) {
            node.put("name", "JustLauncher");
            node.put("gameDir", gameDir.toString());
            node.put("nickname", prefs.get("nickname", "Player"));
            node.put("version", prefs.get("version", ""));
            node.putInt("ram", prefs.getInt("ram", 4));
            node.put("javaPath", prefs.get("javaPath", ""));
        }
        applyClient(activeClientId, false);
        persistClientIds();
    }

    private Preferences clientNode(String id) { return prefs.node("clients").node(id); }
    private String clientName(String id) { return clientNode(id).get("name", id.equals("default") ? "JustLauncher" : "Новый клиент"); }
    private void persistClientIds() { prefs.put(CLIENTS_KEY, String.join(",", clientIds)); prefs.put("activeClient", activeClientId); }

    private void saveActiveClient() {
        if (activeClientId == null) return;
        Preferences node = clientNode(activeClientId);
        node.put("name", clientName(activeClientId));
        node.put("nickname", nickname.getText());
        node.put("version", selectedVersion == null ? "" : selectedVersion);
        node.putInt("ram", (int)Math.round(ramSlider.getValue()));
        node.put("javaPath", javaPath.getText());
        node.put("gameDir", gameDir.toString());
        prefs.put("nickname", nickname.getText()); prefs.put("version", selectedVersion == null ? "" : selectedVersion);
        prefs.putInt("ram", (int)Math.round(ramSlider.getValue())); prefs.put("javaPath", javaPath.getText()); prefs.put("gameDir", gameDir.toString());
    }

    private void applyClient(String id, boolean refresh) {
        activeClientId = id;
        Preferences node = clientNode(id);
        String fallback = id.equals("default") ? prefs.get("gameDir", gameDir.toString()) :
            Path.of(System.getProperty("user.home"), ".juststudio", "clients", id, ".minecraft").toString();
        try { gameDir = Path.of(node.get("gameDir", fallback)); } catch (Exception ignored) {}
        nickname.setText(node.get("nickname", prefs.get("nickname", "Player")));
        selectedVersion = node.get("version", prefs.get("version", ""));
        javaPath.setText(node.get("javaPath", prefs.get("javaPath", "")));
        ramSlider.setValue(node.getInt("ram", prefs.getInt("ram", 4)));
        if (versionPill != null) versionPill.setText(selectedVersion.isBlank() ? "ВЫБРАТЬ ВЕРСИЮ  ▾" : selectedVersion + "  ▾");
        if (clientPill != null) clientPill.setText(clientName(id) + "  ▾");
        persistClientIds();
        saveActiveClient();
        if (refresh) {
            installed.clear();
            available.clear();
            versionCount.setText("Загрузка версий...");
            status.setText("Обновление данных клиента...");
            refreshClientData();
        }
        updatePlayState();
    }

    private void openClientManager() {
        if (clientOverlay != null) return;
        if (homeRoot == null || !content.getChildren().contains(homeRoot)) showPage(0);
        VBox panel = new VBox(12); panel.getStyleClass().add("selector-overlay"); panel.setMaxWidth(570); panel.setMaxHeight(510);
        HBox header = new HBox(styled("ИГРОВЫЕ КЛИЕНТЫ", "section-title"), spacer());
        Button close = new Button("×"); close.getStyleClass().add("tiny-button"); close.setOnAction(e -> closeClientManager()); header.getChildren().add(close);
        VBox rows = new VBox(8); rows.setFillWidth(true);
        for (String id : new ArrayList<>(clientIds)) rows.getChildren().add(clientRow(id));
        ScrollPane scroll = new ScrollPane(rows); scroll.setFitToWidth(true); scroll.getStyleClass().add("client-scroll"); scroll.setPrefHeight(320);
        Button add = iconButton("add", "Создать новый клиент"); add.getStyleClass().add("client-add"); add.setMaxWidth(Double.MAX_VALUE); add.setPrefHeight(44);
        add.setOnAction(e -> createClient());
        panel.getChildren().addAll(header, styled("Выбери отдельную сборку Minecraft или создай новую", "muted"), scroll, add);
        clientBackdrop = new Region();
        clientBackdrop.setStyle("-fx-background-color: rgba(0,0,0,0.22);");
        clientBackdrop.setOnMouseClicked(e -> { closeClientManager(); e.consume(); });
        homeRoot.getChildren().add(clientBackdrop);
        clientOverlay = panel; homeRoot.getChildren().add(panel); StackPane.setAlignment(panel, Pos.CENTER);
        panel.setOpacity(0); panel.setScaleX(.96); panel.setScaleY(.96);
        FadeTransition f = new FadeTransition(Duration.millis(180), panel); f.setToValue(1); f.play();
        ScaleTransition sc = new ScaleTransition(Duration.millis(180), panel); sc.setToX(1); sc.setToY(1); sc.play();
    }

    private HBox clientRow(String id) {
        HBox row = new HBox(8); row.getStyleClass().add("client-row"); row.setAlignment(Pos.CENTER_LEFT);
        Label name = new Label(clientName(id)); name.getStyleClass().add("client-row-name");
        Node active = id.equals(activeClientId) ? activeCheckIcon() : new Region();
        active.setManaged(true);
        HBox identity = new HBox(9, active, name); identity.setAlignment(Pos.CENTER_LEFT);
        Button choose = new Button(); choose.setGraphic(identity); choose.getStyleClass().add("client-select"); HBox.setHgrow(choose, Priority.ALWAYS);
        choose.setOnAction(e -> { closeClientManager(); applyClient(id, true); });
        Button edit = iconButton("edit", "Редактировать клиента"); edit.setOnAction(e -> editClient(id));
        Button delete = iconButton("delete", "Удалить клиента"); delete.setOnAction(e -> deleteClient(id)); delete.setDisable(id.equals("default"));
        row.getChildren().addAll(choose, delete, edit); return row;
    }

    private Node activeCheckIcon() {
        Canvas canvas = new Canvas(18, 18);
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.setFill(Color.web("#123e27")); g.fillOval(1, 1, 16, 16);
        g.setStroke(Color.web("#48e58a")); g.setLineWidth(2.0);
        g.setLineCap(javafx.scene.shape.StrokeLineCap.ROUND); g.setLineJoin(javafx.scene.shape.StrokeLineJoin.ROUND);
        g.strokeLine(4.5, 9.2, 7.5, 12.2); g.strokeLine(7.5, 12.2, 13.5, 5.8);
        return canvas;
    }

    private Button iconButton(String kind, String tip) {
        String resource = switch (kind) {
            case "delete" -> "/icons/delete.png";
            case "edit" -> "/icons/edit.png";
            default -> null;
        };
        Button b = new Button();
        if (resource != null) {
            ImageView image = new ImageView(new Image(getClass().getResourceAsStream(resource)));
            image.setFitWidth(27);
            image.setFitHeight(27);
            image.setPreserveRatio(true);
            image.setSmooth(true);
            b.setGraphic(image);
        } else {
            Canvas canvas = new Canvas(18,18);
            GraphicsContext g = canvas.getGraphicsContext2D();
            g.setStroke(Color.web("#d6a4ff"));
            g.setLineWidth(2.4);
            g.setLineCap(javafx.scene.shape.StrokeLineCap.ROUND);
            g.strokeLine(9,3,9,15);
            g.strokeLine(3,9,15,9);
            b.setGraphic(canvas);
        }
        b.getStyleClass().addAll("client-icon-button", kind.equals("delete") ? "danger-icon" : "edit-icon");
        b.setTooltip(new Tooltip(tip));
        b.setMinSize(36,36);
        b.setPrefSize(36,36);
        return b;
    }

    private void createClient() {
        String id = "client-" + java.util.UUID.randomUUID().toString().substring(0,8);
        clientIds.add(id); Preferences n = clientNode(id); n.put("name", "Новый клиент"); n.put("nickname", nickname.getText()); n.put("version", ""); n.putInt("ram", (int)Math.round(ramSlider.getValue())); n.put("javaPath", javaPath.getText());
        n.put("gameDir", Path.of(System.getProperty("user.home"), ".juststudio", "clients", id, ".minecraft").toString());
        persistClientIds(); closeClientManager(); applyClient(id, true); editClient(id);
    }

    private void editClient(String id) {
        if (clientOverlay != null) closeClientManager();
        if (homeRoot == null || !content.getChildren().contains(homeRoot)) showPage(0);
        Preferences node = clientNode(id);

        VBox panel = new VBox(14);
        panel.getStyleClass().add("selector-overlay");
        panel.setMaxWidth(570);
        panel.setMaxHeight(270);

        Button close = new Button("×");
        close.getStyleClass().add("tiny-button");
        close.setOnAction(e -> { closeClientManager(); openClientManager(); });
        HBox header = new HBox(styled("РЕДАКТИРОВАНИЕ КЛИЕНТА", "section-title"), spacer(), close);
        header.setAlignment(Pos.CENTER_LEFT);

        Label nameLabel = field("НАЗВАНИЕ КЛИЕНТА");
        TextField name = new TextField(clientName(id));
        name.setPromptText("Название клиента");
        name.setMaxWidth(Double.MAX_VALUE);

        Button save = new Button("СОХРАНИТЬ");
        save.getStyleClass().add("primary-small");
        Button cancel = new Button("ОТМЕНА");
        cancel.getStyleClass().add("secondary-button");
        HBox actions = new HBox(9, spacer(), cancel, save);
        actions.setAlignment(Pos.CENTER_RIGHT);
        cancel.setOnAction(e -> { closeClientManager(); openClientManager(); });
        save.setOnAction(e -> {
            if (name.getText() == null || name.getText().isBlank()) {
                name.setPromptText("Введите название клиента");
                return;
            }
            node.put("name", name.getText().trim());
            if (id.equals(activeClientId) && clientPill != null)
                clientPill.setText(clientName(id) + "  ▾");
            closeClientManager();
            openClientManager();
        });

        panel.getChildren().addAll(header, styled("Измени название игровой сборки", "muted"), nameLabel, name, actions);
        clientBackdrop = new Region();
        clientBackdrop.setStyle("-fx-background-color: rgba(0,0,0,0.22);");
        clientBackdrop.setOnMouseClicked(e -> { closeClientManager(); e.consume(); });
        homeRoot.getChildren().add(clientBackdrop);
        clientOverlay = panel;
        homeRoot.getChildren().add(panel);
        StackPane.setAlignment(panel, Pos.CENTER);
        panel.setOpacity(0);
        panel.setScaleX(.96);
        panel.setScaleY(.96);
        FadeTransition fade = new FadeTransition(Duration.millis(180), panel);
        fade.setToValue(1);
        ScaleTransition scale = new ScaleTransition(Duration.millis(180), panel);
        scale.setToX(1);
        scale.setToY(1);
        new javafx.animation.ParallelTransition(fade, scale).play();
    }

    private void deleteClient(String id) {
        if (id.equals("default")) return;
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, "Удалить клиент «" + clientName(id) + "»? Игровые файлы останутся на диске.", ButtonType.CANCEL, ButtonType.OK);
        confirm.showAndWait().ifPresent(answer -> { if (answer == ButtonType.OK) { clientIds.remove(id); try { clientNode(id).removeNode(); } catch (Exception ignored) {} persistClientIds(); if (id.equals(activeClientId)) applyClient("default", true); closeClientManager(); openClientManager(); } });
    }

    private void closeClientManager() {
        if (clientOverlay == null) return;
        VBox old = clientOverlay;
        Region backdrop = clientBackdrop;
        clientOverlay = null;
        clientBackdrop = null;
        FadeTransition f = new FadeTransition(Duration.millis(130), old);
        f.setToValue(0);
        f.setOnFinished(e -> {
            if (homeRoot != null) {
                homeRoot.getChildren().remove(old);
                homeRoot.getChildren().remove(backdrop);
            }
        });
        f.play();
    }

    private boolean isInside(Node parent, Object target) {
        if (!(target instanceof Node node)) return false;
        while (node != null) {
            if (node == parent) return true;
            node = node.getParent();
        }
        return false;
    }

    private void openVersionSelector() {
        if (selectorOverlay != null) return;
        if (homeRoot == null || !content.getChildren().contains(homeRoot)) showPage(0);
        pendingVersion = selectedVersion;

        VBox overlay = new VBox(10);
        overlay.getStyleClass().add("selector-overlay");
        overlay.setMaxWidth(570);
        overlay.setMaxHeight(530);

        Button close = new Button("×");
        close.getStyleClass().add("tiny-button");
        close.setOnAction(e -> closeSelector());
        HBox title = new HBox(styled("ВЫБРАТЬ ВЕРСИЮ MINECRAFT", "section-title"), spacer(), close);
        title.setAlignment(Pos.CENTER_LEFT);

        ListView<String> versions = new ListView<>(FXCollections.observableArrayList(available));
        versionListView = versions;
        versions.getStyleClass().add("version-list");
        versions.setPrefHeight(220);
        versions.setCellFactory(v -> versionCell());
        ToggleButton allTab = new ToggleButton("Все версии");
        ToggleButton modTab = new ToggleButton("Установленные сборки");
        ToggleGroup versionTabs = new ToggleGroup();
        allTab.setToggleGroup(versionTabs); modTab.setToggleGroup(versionTabs); allTab.setSelected(true);
        HBox tabRow = new HBox(8, allTab, modTab); tabRow.getStyleClass().add("version-tabs");
        Runnable showAll = () -> versions.setItems(FXCollections.observableArrayList(available));
        Runnable showModded = () -> versions.setItems(FXCollections.observableArrayList(installed.stream().filter(this::isModdedVersion).toList()));
        allTab.setOnAction(e -> showAll.run()); modTab.setOnAction(e -> showModded.run());
        if (pendingVersion != null && !pendingVersion.isBlank()) versions.getSelectionModel().select(pendingVersion);

        Label state = styled("Выберите версию из списка", "muted");
        state.setId("version-state");
        versions.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            pendingVersion = b;
            updateVersionStatus();
            updateActionButton();
            if (b != null && !b.isBlank()) loadCompatibleComponents(b, componentPanel);
            else hideComponentPanel(componentPanel);
        });

        Label loaders = styled("ДОПОЛНИТЕЛЬНЫЕ КОМПОНЕНТЫ", "eyebrow");
        Label componentHint = styled("Сначала выбери версию — покажу только совместимые компоненты.", "muted");
        componentPanel = new VBox(8, loaders, componentHint);
        componentPanel.getStyleClass().add("component-panel");
        componentPanel.setVisible(false);
        componentPanel.setManaged(false);
        componentChecks.clear();
        compatibleComponents.clear();

        Button action = new Button("ВЫБРАТЬ ВЕРСИЮ");
        action.getStyleClass().add("primary-small");
        action.setMaxWidth(Double.MAX_VALUE);
        action.setOnAction(e -> {
            if (pendingVersion == null || pendingVersion.isBlank()) return;
            String loader = "vanilla";
            for (String id : List.of("forge", "fabric", "neoforge", "quilt")) {
                CheckBox check = componentChecks.get(id);
                if (check != null && check.isSelected()) { loader = id; break; }
            }
            CheckBox optiCheck = componentChecks.get("optifine");
            if (optiCheck != null && optiCheck.isSelected()) {
                if (!loader.equals("forge")) {
                    status.setText("Для OptiFine сначала выбери Forge.");
                    return;
                }
                loader = "forge+optifine";
            }
            String version = pendingVersion;
            closeSelector();
            if (installed.contains(version) && loader.equals("vanilla")) setSelectedVersion(version);
            else beginInstall(version, loader);
        });

        overlay.getChildren().addAll(title, tabRow, versions, state, componentPanel, action);
        selectorOverlay = overlay;
        homeRoot.getChildren().add(overlay);
        StackPane.setAlignment(overlay, Pos.CENTER);

        overlay.setOpacity(0);
        overlay.setScaleX(.96);
        overlay.setScaleY(.96);
        FadeTransition fade = new FadeTransition(Duration.millis(170), overlay);
        fade.setToValue(1);
        fade.play();
        ScaleTransition scale = new ScaleTransition(Duration.millis(170), overlay);
        scale.setToX(1);
        scale.setToY(1);
        scale.play();
        updateVersionStatus();
        updateActionButton();
        if (pendingVersion != null && !pendingVersion.isBlank())
            loadCompatibleComponents(pendingVersion, componentPanel);
        if (available.isEmpty()) {
            versions.setPlaceholder(styled("Загружаю список версий...", "muted"));
            loadCatalog();
        }
    }

    private void hideComponentPanel(VBox panel) {
        if (panel == null) return;
        panel.setVisible(false);
        panel.setManaged(false);
        panel.getChildren().removeIf(node -> node instanceof HBox);
        componentChecks.clear();
    }

    private void loadCompatibleComponents(String version, VBox panel) {
        if (panel == null) return;
        panel.setVisible(true);
        panel.setManaged(true);
        panel.getChildren().removeIf(node -> node instanceof HBox);
        panel.getChildren().setAll(styled("ДОПОЛНИТЕЛЬНЫЕ КОМПОНЕНТЫ", "eyebrow"), styled("Проверяю совместимость с Minecraft " + version + "...", "muted"));
        componentChecks.clear();
        compatibleComponents.clear();
        if (running) {
            panel.getChildren().setAll(styled("ДОПОЛНИТЕЛЬНЫЕ КОМПОНЕНТЫ", "eyebrow"), styled("Проверка начнётся после текущей операции...", "muted"));
            PauseTransition wait = new PauseTransition(Duration.millis(250));
            wait.setOnFinished(e -> {
                if (panel == componentPanel && version.equals(pendingVersion)) loadCompatibleComponents(version, panel);
            });
            wait.play();
            return;
        }
        runEngine("components", List.of(version), false, () -> {
            if (panel != componentPanel || !version.equals(pendingVersion)) return;
            panel.getChildren().clear();
            panel.getChildren().add(styled("ДОПОЛНИТЕЛЬНЫЕ КОМПОНЕНТЫ", "eyebrow"));
            HBox choices = new HBox(14);
            choices.setAlignment(Pos.CENTER_LEFT);
            for (String id : List.of("forge", "fabric", "neoforge", "quilt")) {
                boolean ok = compatibleComponents.getOrDefault(id, false);
                CheckBox check = new CheckBox(id.equals("neoforge") ? "NeoForge" : id.equals("quilt") ? "Quilt" : id.equals("forge") ? "Forge" : "Fabric");
                check.getStyleClass().add("loader-check");
                check.setDisable(!ok);
                if (!ok) check.setTooltip(new Tooltip("Для Minecraft " + version + " нет доступной стабильной версии"));
                check.selectedProperty().addListener((obs, was, selected) -> {
                    if (selected) {
                        for (String otherId : List.of("forge", "fabric", "neoforge", "quilt")) {
                            if (!otherId.equals(id) && componentChecks.containsKey(otherId)) componentChecks.get(otherId).setSelected(false);
                        }
                    }
                    CheckBox optiCheck = componentChecks.get("optifine");
                    if (optiCheck != null) {
                        boolean canUseOpti = selected && id.equals("forge") && compatibleComponents.getOrDefault("optifine", false);
                        optiCheck.setDisable(!canUseOpti);
                        if (!canUseOpti) optiCheck.setSelected(false);
                        optiCheck.setTooltip(new Tooltip(canUseOpti
                            ? "Установится вместе с Forge в папку mods выбранного клиента"
                            : "OptiFine доступен только вместе с совместимой версией Forge"));
                    }
                    updateActionButton();
                });
                componentChecks.put(id, check);
                choices.getChildren().add(check);
            }
            CheckBox opti = new CheckBox("OptiFine");
            opti.getStyleClass().add("loader-check");
            boolean optiAvailable = compatibleComponents.getOrDefault("optifine", false);
            opti.setDisable(true);
            opti.setTooltip(new Tooltip(optiAvailable
                ? "Сначала выбери Forge. OptiFine установится как мод в выбранный клиент."
                : "Для этой версии нет стабильной Forge-совместимой сборки OptiFine."));
            opti.selectedProperty().addListener((obs, was, selected) -> updateActionButton());
            componentChecks.put("optifine", opti);
            choices.getChildren().add(opti);
            String hint = optiAvailable
                ? "Можно выбрать Forge и OptiFine одновременно. OptiFine загрузится с официального сайта и попадёт в папку mods этого клиента."
                : "Показываются только стабильные загрузчики и официальные сборки OptiFine, совместимые с выбранной версией.";
            panel.getChildren().addAll(choices, styled(hint, "muted"));
            updateActionButton();
            FadeTransition fade = new FadeTransition(Duration.millis(220), panel);
            fade.setFromValue(0.25); fade.setToValue(1); fade.play();
        });
    }

    private ListCell<String> versionCell() {
        return new ListCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().remove("installed-version");
                if (empty || item == null) {
                    setGraphic(null); setText(null);
                } else {
                    boolean done = installed.contains(item);
                    if (done) {
                        Node mark = activeCheckIcon();
                        Label name = new Label(item); name.getStyleClass().add("installed-name");
                        HBox row = new HBox(9, mark, name); row.setAlignment(Pos.CENTER_LEFT);
                        setGraphic(row); setText(null); getStyleClass().add("installed-version");
                    } else { setGraphic(null); setText(item); }
                }
            }
        };
    }

    private boolean isModdedVersion(String id) {
        String lower = id.toLowerCase(Locale.ROOT);
        return lower.contains("forge") || lower.contains("fabric") || lower.contains("neoforge") || lower.contains("quilt") || lower.contains("optifine");
    }

    private void updateActionButton() {
        if (selectorOverlay == null) return;
        for (Node node : selectorOverlay.getChildren()) {
            if (node instanceof Button button && button.getStyleClass().contains("primary-small")) {
                boolean loaderSelected = componentChecks.values().stream().anyMatch(CheckBox::isSelected);
                button.setText(pendingVersion != null && installed.contains(pendingVersion) && !loaderSelected
                    ? "ВЫБРАТЬ ВЕРСИЮ" : "УСТАНОВИТЬ");
            }
        }
    }

    private void updateVersionStatus() {
        if (selectorOverlay == null) return;
        Label label = (Label) selectorOverlay.lookup("#version-state");
        if (label == null) return;
        if (pendingVersion == null) label.setText("Выберите версию из списка");
        else label.setText(installed.contains(pendingVersion) ? "✓  Версия готова к запуску" : "Новая версия — будет загружена при установке");
    }

    private void closeSelector() {
        if (selectorOverlay == null) return;
        VBox old = selectorOverlay;
        selectorOverlay = null;
        versionListView = null;
        componentPanel = null;
        componentChecks.clear();
        FadeTransition fade = new FadeTransition(Duration.millis(130), old);
        fade.setToValue(0);
        fade.setOnFinished(e -> {
            if (homeRoot != null) homeRoot.getChildren().remove(old);
        });
        fade.play();
    }

    private void beginInstall(String version, String loader) {
        if (running) return;
        selectedVersion = version;
        prefs.put("version", version);
        saveActiveClient();
        installing = true;
        showPage(0);
        play.setDisable(true);
        downloadBar.setProgress(0);
        downloadTitle.setText("УСТАНОВКА MINECRAFT " + version);
        downloadInfo.setText("Подготовка загрузки...");
        runEngine("install", List.of(version, loader), true, () -> {
            downloadInfo.setText("Установка завершена");
            downloadBar.setProgress(1);
            refreshInstalled();
        });
    }

    private void refreshClientData() {
        if (running) {
            PauseTransition wait = new PauseTransition(Duration.millis(250));
            wait.setOnFinished(e -> refreshClientData());
            wait.play();
            return;
        }
        installed.clear();
        runEngine("list", List.of(), false, () -> {
            versionCount.setText(installed.size() + " установлено");
            if (versionListView != null && selectorOverlay != null && !modTabSelected())
                versionListView.setItems(FXCollections.observableArrayList(available));
            // The public catalogue is global, while installed profiles are per-client.
            // Keep the already loaded catalogue so a new client can choose a version immediately.
            if (available.isEmpty()) loadCatalog();
        });
    }

    private boolean modTabSelected() {
        if (selectorOverlay == null) return false;
        for (Node node : selectorOverlay.lookupAll(".toggle-button"))
            if (node instanceof ToggleButton button && button.isSelected() && button.getText().equals("Установленные сборки")) return true;
        return false;
    }

    private void refreshInstalled() {
        installed.clear();
        runEngine("list", List.of(), false, () -> {
            if (selectedVersion.isBlank() && !installed.isEmpty()) setSelectedVersion(installed.get(0));
            else updatePlayState();
        });
    }

    private void loadCatalog() {
        if (running) {
            PauseTransition wait = new PauseTransition(Duration.millis(250));
            wait.setOnFinished(e -> loadCatalog());
            wait.play();
            return;
        }
        runEngine("catalog", List.of(), false, () -> {
            if (available.isEmpty()) append("Каталог версий не получен. Проверь подключение к интернету.");
            if (versionListView != null && selectorOverlay != null && !modTabSelected())
                versionListView.setItems(FXCollections.observableArrayList(available));
            if (versionCount.getText().contains("Загрузка")) versionCount.setText(installed.size() + " установлено");
        });
    }

    private void setSelectedVersion(String version) {
        selectedVersion = version;
        prefs.put("version", version);
        saveActiveClient();
        selectedVersionLabel.setText(version);
        if (versionPill != null) versionPill.setText(version + "  ▾");
        updatePlayState();
        status.setText("Выбрана версия " + version);
    }

    private void updatePlayState() {
        boolean ready = selectedVersion != null && !selectedVersion.isBlank()
            && installed.contains(selectedVersion) && !running;
        play.setDisable(!ready);
        play.setText("▶   СТАРТ");
        selectedVersionLabel.setText(selectedVersion == null || selectedVersion.isBlank()
            ? "СНАЧАЛА ВЫБЕРИ ВЕРСИЮ" : "ВЫБРАНА ВЕРСИЯ: " + selectedVersion);
    }

    private void launchGame() {
        String nick = nickname.getText().trim();
        if (!nick.matches("[A-Za-z0-9_]{1,16}")) {
            status.setText("Ник: 1–16 латинских букв, цифр или _");
            nickname.requestFocus();
            return;
        }
        if (selectedVersion.isBlank() || !installed.contains(selectedVersion)) {
            openVersionSelector();
            return;
        }
        int memory = (int) Math.round(ramSlider.getValue());
        prefs.put("nickname", nick);
        runEngine("launch", List.of(nick, selectedVersion, String.valueOf(memory), javaPath.getText().trim()), true, () -> {});
    }

    private VBox heading(String title, String subtitle) {
        return new VBox(5, styled(title, "page-title"), styled(subtitle, "muted"));
    }

    private VBox panel(String title) {
        VBox panel = new VBox(12);
        panel.getStyleClass().add("panel");
        panel.setPadding(new Insets(17));
        panel.getChildren().add(styled(title, "section-title"));
        return panel;
    }

    private Label field(String text) {
        return styled(text, "field-label");
    }

    private Label styled(String text, String style) {
        Label label = new Label(text);
        label.getStyleClass().add(style);
        label.setWrapText(true);
        return label;
    }

    private List<String> pythonCommand() {
        List<String> command = new ArrayList<>();
        if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) {
            command.add("py");
            command.add("-3");
        } else command.add("python3");
        return command;
    }

    private void runEngine(String action, List<String> args, boolean lock, Runnable done) {
        if (running) return;
        running = true;
        if (action.equals("launch")) {
            launchStartedAt = System.currentTimeMillis();
            launchOutput.clear();
        }
        if (lock) play.setDisable(true);
        status.setText(switch (action) {
            case "install" -> "Установка Minecraft...";
            case "launch" -> "Запуск Minecraft...";
            case "catalog" -> "Загрузка списка версий...";
            default -> "Обновление списка версий...";
        });

        final Path operationGameDir = gameDir.toAbsolutePath();
        Thread worker = new Thread(() -> {
            int code = -1;
            try {
                List<String> command = pythonCommand();
                command.add(projectDir.resolve("engine/minecraft_engine.py").toString());
                command.add(action);
                command.addAll(args);
                command.add(operationGameDir.toString());

                ProcessBuilder builder = new ProcessBuilder(command);
                builder.directory(projectDir.toFile());
                builder.redirectErrorStream(true);
                builder.environment().put("PYTHONIOENCODING", "utf-8");
                Process process = builder.start();
                if (action.equals("launch")) Platform.runLater(this::applyLaunchWindowBehavior);

                try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String output = line;
                        Platform.runLater(() -> handleEngineLine(action, output));
                    }
                }
                code = process.waitFor();
            } catch (Exception e) {
                String message = e.getMessage() == null ? e.toString() : e.getMessage();
                Platform.runLater(() -> {
                    append("Ошибка движка (" + action + "): " + message);
                    status.setText("Не удалось выполнить операцию: " + message);
                });
            }

            int result = code;
            Platform.runLater(() -> {
                running = false;
                updatePlayState();
                if (action.equals("launch") && (result != 0 || !latestCrashReportSummary().isBlank())) {
                    status.setText("Minecraft завершилась с ошибкой");
                    showLaunchFailure(result);
                } else if (action.equals("list")) {
                    versionCount.setText(installed.size() + " установлено");
                    status.setText("Список установленных версий обновлён");
                } else if (result == 0) {
                    if (action.equals("install") || action.equals("reinstall")) {
                        status.setText("Установка завершена");
                        downloadInfo.setText("Установка завершена");
                        downloadBar.setProgress(1);
                    } else if (action.equals("launch")) status.setText("Игра закрыта");
                    else status.setText("Список версий загружен");
                } else {
                    status.setText("Ошибка операции (код " + result + "). Открой консоль.");
                }
                if (action.equals("launch") && !"close".equals(prefs.get("launchWindowBehavior", "minimize"))) {
                    restoreLauncherWindow();
                }
                done.run();
            });
        }, "launcher-engine");
        worker.setDaemon(true);
        worker.start();
    }

    private void applyLaunchWindowBehavior() {
        String behavior = prefs.get("launchWindowBehavior", "minimize");
        if ("close".equals(behavior)) {
            if (trayIcon != null && SystemTray.isSupported()) SystemTray.getSystemTray().remove(trayIcon);
            stage.close();
            Platform.exit();
        } else if ("hide".equals(behavior)) {
            if (installTrayIcon()) stage.hide();
            else {
                status.setText("Системный трей недоступен — лаунчер свёрнут");
                stage.setIconified(true);
            }
        } else {
            stage.setIconified(true);
        }
    }

    private boolean installTrayIcon() {
        if (!SystemTray.isSupported()) return false;
        if (trayIcon != null) return true;
        try {
            BufferedImage image = ImageIO.read(getClass().getResourceAsStream("/juststudio-logo.png"));
            PopupMenu menu = new PopupMenu();
            MenuItem open = new MenuItem("Открыть Just Studio Launcher");
            open.addActionListener(e -> Platform.runLater(() -> {
                stage.show();
                stage.setIconified(false);
                stage.toFront();
            }));
            MenuItem exit = new MenuItem("Выход");
            exit.addActionListener(e -> Platform.runLater(() -> {
                SystemTray.getSystemTray().remove(trayIcon);
                trayIcon = null;
                Platform.exit();
            }));
            menu.add(open);
            menu.addSeparator();
            menu.add(exit);
            trayIcon = new TrayIcon(image, "Just Studio Launcher", menu);
            trayIcon.setImageAutoSize(true);
            trayIcon.addActionListener(e -> Platform.runLater(this::restoreLauncherWindow));
            trayIcon.addMouseListener(new java.awt.event.MouseAdapter() {
                @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                    if (e.getButton() == java.awt.event.MouseEvent.BUTTON1) {
                        Platform.runLater(LauncherApp.this::restoreLauncherWindow);
                    }
                }
            });
            SystemTray.getSystemTray().add(trayIcon);
            return true;
        } catch (Exception e) {
            append("Не удалось создать значок в системном трее: " + e.getMessage());
            trayIcon = null;
            return false;
        }
    }

    private void restoreLauncherWindow() {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(this::restoreLauncherWindow);
            return;
        }
        stage.show();
        stage.setIconified(false);
        stage.toFront();
        stage.requestFocus();
    }

    private void handleEngineLine(String action, String output) {
        if (action.equals("launch")) launchOutput.add(output);
        if (output.startsWith("SELECTED|")) {
            String version = output.substring(9).trim();
            if (!version.isEmpty()) {
                selectedVersion = version;
                prefs.put("version", version);
                saveActiveClient();
                selectedVersionLabel.setText(version);
                if (versionPill != null) versionPill.setText(version + "  ▾");
            }
            return;
        }
        if (output.startsWith("VERSION|")) {
            String version = output.substring(8).trim();
            if (!version.isEmpty()) {
                if (action.equals("list") && !installed.contains(version)) installed.add(version);
                if (action.equals("catalog") && !available.contains(version)) available.add(version);
            }
            return;
        }
        if (output.startsWith("COMPONENT|")) {
            String[] parts = output.split("\\|", 4);
            if (parts.length >= 3) compatibleComponents.put(parts[1], Boolean.parseBoolean(parts[2]));
            return;
        }
        if (output.startsWith("PROGRESS|")) {
            String[] parts = output.split("\\|");
            if (parts.length >= 5) {
                try {
                    double percent = Double.parseDouble(parts[1]);
                    downloadBar.setProgress(Math.max(0, Math.min(1, percent / 100)));
                    String eta = parts.length >= 6 ? parts[5] : "—";
                    downloadInfo.setText(String.format(Locale.US,
                        "%.1f%%  •  %s / ~%s  •  %s/s  •  осталось %s",
                        percent, parts[2], parts[3], parts[4], eta));
                } catch (Exception ignored) {}
            }
            return;
        }
        if (output.startsWith("STATUS|")) {
            String message = output.substring(7);
            status.setText(message);
            downloadInfo.setText(message);
            append(message);
            return;
        }
        append(output);
        status.setText(output);
    }

    private void showLaunchFailure(int exitCode) {
        String diagnosis = diagnoseLaunchFailure();
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Minecraft не запустился");
        alert.setHeaderText("Не удалось запустить " + selectedVersion);
        alert.setContentText(diagnosis + "\n\nКод завершения: " + exitCode
            + "\n\nМожно попробовать переустановить выбранную версию. Миры и папки mods/resourcepacks при этом не удаляются.");
        ButtonType reinstall = new ButtonType("Переустановить версию", ButtonBar.ButtonData.OK_DONE);
        ButtonType close = new ButtonType("Закрыть", ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(reinstall, close);
        alert.showAndWait().ifPresent(choice -> {
            if (choice == reinstall && selectedVersion != null && !selectedVersion.isBlank()) {
                installing = true;
                downloadBar.setProgress(0);
                downloadTitle.setText("ПЕРЕУСТАНОВКА MINECRAFT " + selectedVersion);
                downloadInfo.setText("Подготовка переустановки...");
                runEngine("reinstall", List.of(selectedVersion, "vanilla"), true, this::refreshInstalled);
            }
        });
    }

    private String diagnoseLaunchFailure() {
        String reportSummary = latestCrashReportSummary();
        if (!reportSummary.isBlank()) return "Причина из crash-report:\n" + reportSummary
            + "\n\nЧто сделать: проверь совместимость модов с версией Minecraft и загрузчиком; временно убери недавно добавленные моды и попробуй запустить игру снова.";

        String log = (String.join("\n", launchOutput) + "\n" + latestGameLog()).toLowerCase(Locale.ROOT);
        if (log.contains("outofmemoryerror") || log.contains("not enough memory"))
            return "Похоже, игре не хватило оперативной памяти. Уменьши выделенную RAM или закрой другие программы.";
        if (log.contains("unsupportedclassversionerror") || log.contains("class file version"))
            return "Вероятно, выбрана неподходящая версия Java. Для новых версий Minecraft обычно нужна более новая Java; укажи совместимую java.exe в настройках.";
        if (log.contains("could not create the java virtual machine") || log.contains("invalid maximum heap size"))
            return "Java не смогла выделить указанную память. Уменьши ползунок RAM и проверь, что выбранная Java 64-битная.";
        if (log.contains("modresolutionexception") || log.contains("failed to load mod") || log.contains("mixin"))
            return "Похоже, конфликтуют моды или их версии. Убери последний добавленный мод и проверь, совпадают ли версии модов и Forge/Fabric/NeoForge.";
        if (log.contains("glfw") || log.contains("opengl") || log.contains("pixel format"))
            return "Похоже, возникла проблема с графикой. Обнови драйвер видеокарты и закрой программы, которые перехватывают изображение.";
        return "Лаунчер не получил подробный crash-report. Открой консоль лаунчера и файл logs/latest.log в папке игры. Проверь Java, RAM и совместимость модов. Если ошибка повторится, пришли latest.log или файл из crash-reports.";
    }

    private String latestGameLog() {
        Path latestLog = gameDir.resolve("logs").resolve("latest.log");
        if (!Files.isRegularFile(latestLog) || latestLog.toFile().lastModified() < launchStartedAt - 5000) return "";
        try {
            List<String> lines = Files.readAllLines(latestLog, StandardCharsets.UTF_8);
            int start = Math.max(0, lines.size() - 500);
            return String.join("\n", lines.subList(start, lines.size()));
        } catch (Exception ignored) {
            return "";
        }
    }

    private String latestCrashReportSummary() {
        Path reports = gameDir.resolve("crash-reports");
        if (!Files.isDirectory(reports)) return "";
        try (var files = Files.list(reports)) {
            Path latest = files.filter(p -> p.getFileName().toString().endsWith(".txt"))
                .max((a, b) -> Long.compare(a.toFile().lastModified(), b.toFile().lastModified())).orElse(null);
            if (latest == null || latest.toFile().lastModified() < launchStartedAt - 5000) return "";
            List<String> lines = Files.readAllLines(latest, StandardCharsets.UTF_8);
            String description = lines.stream().filter(line -> line.startsWith("Description:")).findFirst().orElse("");
            String cause = lines.stream().filter(line -> line.contains("Caused by:")).findFirst().orElse("");
            String summary = (description + "\n" + cause).trim();
            if (summary.isBlank()) summary = String.join("\n", lines.stream().limit(18).toList());
            return summary.length() > 900 ? summary.substring(0, 900) + "…" : summary;
        } catch (Exception ignored) {
            return "";
        }
    }

    private void append(String text) {
        console.appendText(text + "\n");
        console.positionCaret(console.getLength());
    }

    public static void main(String[] args) {
        launch(args);
    }
}
