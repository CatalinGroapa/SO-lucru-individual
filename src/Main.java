import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.VBox;
import javafx.scene.control.TabPane;
import javafx.scene.control.Tab;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

public class Main extends Application {
    private final ObservableList<BlockedApp> blockedObservable = FXCollections.observableArrayList();
    private final ObservableList<BlockedSite> blockedSites = FXCollections.observableArrayList();
    private final BlockedListStore store = new BlockedListStore();
    private final ParentalPasswordGuard passwordGuard = new ParentalPasswordGuard();
    private ProcessMonitor monitor;
    private final TextArea logArea = new TextArea();
    private final Button startStopBtn = new Button("Start Monitoring");
    private final Label totalBlockedLabel = new Label();
    private final Label totalSitesLabel = new Label();
    private WebsiteBlocker websiteBlocker;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        stage.setTitle("Control Parental");

        TableView<BlockedApp> table = new TableView<>();
        table.setEditable(true);
        TableView<BlockedSite> siteTable = buildSiteTable();
        siteTable.setItems(blockedSites);

        TableColumn<BlockedApp, String> nameCol = new TableColumn<>("Nume afisat");
        nameCol.setCellValueFactory(new PropertyValueFactory<>("displayName"));
        nameCol.setPrefWidth(180);

        TableColumn<BlockedApp, String> exeCol = new TableColumn<>("Executabil");
        exeCol.setCellValueFactory(new PropertyValueFactory<>("exeName"));
        exeCol.setPrefWidth(180);

        TableColumn<BlockedApp, Boolean> enabledCol = new TableColumn<>("Activ");
        enabledCol.setCellValueFactory(new PropertyValueFactory<>("enabled"));
        enabledCol.setCellFactory(CheckBoxTableCell.forTableColumn(enabledCol));
        enabledCol.setPrefWidth(80);

        TableColumn<BlockedApp, String> limitCol = new TableColumn<>("Limita/zi (min)");
        limitCol.setCellValueFactory(new PropertyValueFactory<>("usageSummary"));

        TableColumn<BlockedApp, String> scheduleCol = new TableColumn<>("Interval permis");
        scheduleCol.setCellValueFactory(new PropertyValueFactory<>("scheduleSummary"));

        table.getColumns().addAll(nameCol, exeCol, enabledCol, limitCol, scheduleCol);
        table.setItems(blockedObservable);

        Button addBtn = new Button("Adaugă");
        Button editBtn = new Button("Editează");
        Button removeBtn = new Button("Șterge");
        Button saveBtn = new Button("Salvează");
        Button pinBtn = new Button(passwordGuard.isPasswordSet() ? "Schimbă parola" : "Setează parolă");

        addBtn.setOnAction(e -> requirePasswordAndRun(ProtectedAction.CHANGE_SETTINGS, () -> onAddOrEdit(null)));
        editBtn.setOnAction(e -> requirePasswordAndRun(ProtectedAction.CHANGE_SETTINGS,
                () -> onAddOrEdit(table.getSelectionModel().getSelectedItem())));
        removeBtn.setOnAction(e -> requirePasswordAndRun(ProtectedAction.CHANGE_SETTINGS, () -> {
            BlockedApp sel = table.getSelectionModel().getSelectedItem();
            if (sel != null) blockedObservable.remove(sel);
        }));
        saveBtn.setOnAction(e -> requirePasswordAndRun(ProtectedAction.CHANGE_SETTINGS, this::onSave));
        pinBtn.setOnAction(e -> configurePassword(pinBtn));

        startStopBtn.setOnAction(e -> requirePasswordAndRun(ProtectedAction.DISABLE_RULES, this::onToggleMonitor));
        startStopBtn.setText("Porneste monitorizarea");

        HBox controls = new HBox(8, addBtn, editBtn, removeBtn, saveBtn, pinBtn, startStopBtn);
        controls.setPadding(new Insets(8));

        totalBlockedLabel.setPadding(new Insets(0, 8, 8, 8));

        logArea.setEditable(false);
        logArea.setPrefRowCount(8);
        logArea.setPromptText("Mesaje de jurnal...");

        VBox appsBox = new VBox(8, table, controls, totalBlockedLabel);
        appsBox.setPadding(new Insets(8));

        VBox sitesControls = buildSiteControls(siteTable);
        VBox sitesBox = new VBox(8, siteTable, sitesControls, totalSitesLabel);
        sitesBox.setPadding(new Insets(8));

        TabPane tabs = new TabPane();
        Tab appsTab = new Tab("Aplicații", appsBox);
        appsTab.setClosable(false);
        Tab sitesTab = new Tab("Site-uri", sitesBox);
        sitesTab.setClosable(false);
        tabs.getTabs().addAll(appsTab, sitesTab);

        BorderPane root = new BorderPane();
        root.setCenter(tabs);
        root.setBottom(logArea);

        Scene scene = new Scene(root, 860, 520);
        // load css if available
        try {
            String css = getClass().getResource("/style.css").toExternalForm();
            scene.getStylesheets().add(css);
        } catch (Exception ex) {
            // ignore if stylesheet not found
        }

        stage.setScene(scene);
        stage.show();

        // load persisted list
        try {
            List<BlockedApp> loadedApps = store.loadApps();
            List<BlockedSite> loadedSites = store.loadSites();
            blockedObservable.addAll(loadedApps);
            blockedSites.addAll(loadedSites);
            appendLog("Liste încărcate: " + loadedApps.size() + " aplicații, " + loadedSites.size() + " site-uri.");
        } catch (IOException ex) {
            appendLog("Nu pot încărca listele: " + ex.getMessage());
        }
        updateTotals();
        updateSiteTotals();
        blockedObservable.addListener((javafx.collections.ListChangeListener<? super BlockedApp>) change -> updateTotals());
        blockedSites.addListener((javafx.collections.ListChangeListener<? super BlockedSite>) change -> updateSiteTotals());

        // create monitor (uses a logger callback)
        Consumer<String> logger = this::appendLog;
        monitor = new ProcessMonitor(blockedObservable, logger);
        websiteBlocker = new WebsiteBlocker(logger);

        // ensure save on close
        stage.setOnCloseRequest(evt -> {
            boolean allowExit = confirmExit();
            if (!allowExit) {
                evt.consume();
                return;
            }
            if (monitor.isRunning()) monitor.stop();
            try {
                store.save(blockedObservable, blockedSites);
            } catch (IOException ex) {
                // best-effort
            }
            try {
                websiteBlocker.removeAll(blockedSites);
            } catch (IOException ex) {
                // ignore on close
            }
            Platform.exit();
        });
    }

    private TableView<BlockedSite> buildSiteTable() {
        TableView<BlockedSite> table = new TableView<>();
        TableColumn<BlockedSite, String> titleCol = new TableColumn<>("Descriere");
        titleCol.setCellValueFactory(new PropertyValueFactory<>("title"));
        TableColumn<BlockedSite, String> domainCol = new TableColumn<>("Domeniu");
        domainCol.setCellValueFactory(new PropertyValueFactory<>("urlPattern"));
        TableColumn<BlockedSite, Boolean> enabledCol = new TableColumn<>("Activ");
        enabledCol.setCellValueFactory(new PropertyValueFactory<>("enabled"));
        enabledCol.setCellFactory(CheckBoxTableCell.forTableColumn(enabledCol));
        table.getColumns().addAll(titleCol, domainCol, enabledCol);
        table.setEditable(true);
        return table;
    }

    private VBox buildSiteControls(TableView<BlockedSite> table) {
        Button addSite = new Button("Adaugă site");
        Button editSite = new Button("Editează site");
        Button removeSite = new Button("Șterge site");
        Button applySites = new Button("Aplică blocare");

        addSite.setOnAction(e -> requirePasswordAndRun(ProtectedAction.CHANGE_SETTINGS, () -> onAddOrEditSite(null)));
        editSite.setOnAction(e -> requirePasswordAndRun(ProtectedAction.CHANGE_SETTINGS,
                () -> onAddOrEditSite(table.getSelectionModel().getSelectedItem())));
        removeSite.setOnAction(e -> requirePasswordAndRun(ProtectedAction.CHANGE_SETTINGS, () -> {
            BlockedSite sel = table.getSelectionModel().getSelectedItem();
            if (sel != null) blockedSites.remove(sel);
        }));
        applySites.setOnAction(e -> requirePasswordAndRun(ProtectedAction.CHANGE_SETTINGS, this::applySiteBlocking));

        HBox line = new HBox(8, addSite, editSite, removeSite, applySites);
        line.setPadding(new Insets(0, 0, 0, 0));
        return new VBox(8, line);
    }

    private void onAddOrEdit(BlockedApp existing) {
        boolean editMode = existing != null;
        Dialog<BlockedApp> dialog = new Dialog<>();
        dialog.setTitle(editMode ? "Editează aplicație blocată" : "Adaugă aplicație blocată");

        ButtonType okType = new ButtonType(editMode ? "Salvează" : "Adaugă", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(okType, ButtonType.CANCEL);

        TextField nameField = new TextField();
        TextField exeField = new TextField();
        TextField pathField = new TextField();
        TextField limitField = new TextField();
        TextField scheduleField = new TextField();

        nameField.setPromptText("Nume ușor de recunoscut");
        exeField.setPromptText("ex: chrome.exe");
        pathField.setPromptText("Cale completă (opțional)");
        limitField.setPromptText("Minute permis/zi (0 nelimitat)");
        scheduleField.setPromptText("Intervale HH:mm-HH:mm separate prin virgule");

        if (editMode) {
            nameField.setText(existing.getDisplayName());
            exeField.setText(existing.getExeName());
            pathField.setText(existing.getExePath());
            limitField.setText(existing.getDailyLimitMinutes() == 0 ? "" : Integer.toString(existing.getDailyLimitMinutes()));
            scheduleField.setText(existing.getAllowedIntervals());
        }

        Button browseBtn = new Button("Răsfoiește...");
        browseBtn.setOnAction(evt -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Selectează executabilul");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Executabile", "*.exe", "*.bat"));
            java.io.File file = chooser.showOpenDialog(dialog.getDialogPane().getScene().getWindow());
            if (file != null) {
                pathField.setText(file.getAbsolutePath());
                exeField.setText(file.getName());
                if (nameField.getText().isBlank()) {
                    nameField.setText(file.getName());
                }
            }
        });

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.setPadding(new Insets(12));
        ColumnConstraints cc = new ColumnConstraints();
        cc.setPercentWidth(30);
        grid.getColumnConstraints().addAll(cc, new ColumnConstraints());

        grid.add(new Label("Nume"), 0, 0);
        grid.add(nameField, 1, 0);
        grid.add(new Label("Executabil"), 0, 1);
        grid.add(exeField, 1, 1);
        grid.add(new Label("Cale"), 0, 2);
        HBox pathBox = new HBox(8, pathField, browseBtn);
        grid.add(pathBox, 1, 2);
        grid.add(new Label("Limită zilnică"), 0, 3);
        grid.add(limitField, 1, 3);
        grid.add(new Label("Intervale permise"), 0, 4);
        grid.add(scheduleField, 1, 4);

        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(btn -> {
            if (btn == okType) {
                String name = nameField.getText().trim();
                String exe = exeField.getText().trim();
                String path = pathField.getText().trim();
                String limit = limitField.getText().trim();
                String schedule = scheduleField.getText().trim();
                if (exe.isEmpty() && path.isEmpty()) return null;
                BlockedApp target = editMode ? existing : new BlockedApp();
                target.setDisplayName(name.isEmpty() ? exe : name);
                target.setExeName(exe);
                target.setExePath(path.isEmpty() ? null : path);
                target.setDailyLimitMinutes(parseInt(limit));
                target.setAllowedIntervals(schedule.isEmpty() ? null : schedule);
                target.setEnabled(true);
                return target;
            }
            return null;
        });

        Optional<BlockedApp> res = dialog.showAndWait();
        res.ifPresent(app -> {
            if (!editMode) {
                blockedObservable.add(app);
            }
        });
    }

    private void onSave() {
        try {
            store.save(blockedObservable, blockedSites);
            appendLog("Salvate " + blockedObservable.size() + " aplicații și " + blockedSites.size() + " site-uri.");
        } catch (IOException ex) {
            appendLog("Eșec la salvare: " + ex.getMessage());
        }
    }

    private void onToggleMonitor() {
        if (monitor.isRunning()) {
            monitor.stop();
            startStopBtn.setText("Porneste monitorizarea");
            appendLog("Monitorizarea a fost oprită.");
        } else {
            monitor.start();
            startStopBtn.setText("Opreste monitorizarea");
            appendLog("Monitorizarea a început.");
        }
    }

    private void appendLog(String message) {
        String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        Platform.runLater(() -> logArea.appendText("[" + time + "] " + message + "\n"));
    }

    private void updateTotals() {
        totalBlockedLabel.setText("Aplicații blocate: " + blockedObservable.size());
    }

    private void updateSiteTotals() {
        totalSitesLabel.setText("Site-uri blocate: " + blockedSites.size());
    }

    private int parseInt(String value) {
        if (value == null || value.isBlank()) return 0;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private void configurePassword(Button trigger) {
        Dialog<char[]> dialog = new Dialog<>();
        dialog.setTitle(passwordGuard.isPasswordSet() ? "Schimbă parola parentală" : "Setează parola parentală");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        PasswordField pass1 = new PasswordField();
        pass1.setPromptText("Parolă nouă");
        PasswordField pass2 = new PasswordField();
        pass2.setPromptText("Confirmă parola");
        VBox content = new VBox(8, pass1, pass2);
        content.setPadding(new Insets(12));
        dialog.getDialogPane().setContent(content);
        dialog.setResultConverter(btn -> btn == ButtonType.OK ? pass1.getText().toCharArray() : null);

        dialog.showAndWait().ifPresent(chars -> {
            if (!Arrays.equals(pass1.getText().toCharArray(), pass2.getText().toCharArray())) {
                appendLog("Parolele nu coincid.");
                return;
            }
            if (pass1.getText().length() < 4) {
                appendLog("Parola trebuie să aibă minim 4 caractere.");
                return;
            }
            try {
                passwordGuard.setPassword(pass1.getText().toCharArray());
                appendLog("Parola parentală a fost actualizată.");
                trigger.setText("Schimbă parola");
            } catch (IOException ex) {
                appendLog("Nu pot salva parola: " + ex.getMessage());
            }
        });
    }

    private void requirePasswordAndRun(ProtectedAction action, Runnable task) {
        if (!passwordGuard.isPasswordSet()) {
            task.run();
            return;
        }
        PasswordField field = new PasswordField();
        field.setPromptText("Parola parentală");
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Confirmare securitate");
        dialog.setHeaderText("Introduceți parola pentru a continua");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.getDialogPane().setContent(field);
        Optional<ButtonType> res = dialog.showAndWait();
        if (res.isPresent() && res.get() == ButtonType.OK) {
            try {
                if (passwordGuard.verifyPassword(field.getText().toCharArray())) {
                    task.run();
                    return;
                }
                appendLog("Parola incorectă.");
            } catch (IOException ex) {
                appendLog("Nu pot verifica parola: " + ex.getMessage());
            }
        } else {
            appendLog("Acțiune anulată.");
        }
    }

    private boolean confirmExit() {
        if (!passwordGuard.isPasswordSet()) {
            return showExitDialog();
        }
        PasswordField field = new PasswordField();
        field.setPromptText("Parola parentală");
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Confirmare ieșire");
        dialog.setHeaderText("Aplicația necesită parola pentru a ieși");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.getDialogPane().setContent(field);
        Optional<ButtonType> res = dialog.showAndWait();
        if (res.isPresent() && res.get() == ButtonType.OK) {
            try {
                if (passwordGuard.verifyPassword(field.getText().toCharArray())) {
                    return showExitDialog();
                }
                appendLog("Parola incorectă.");
                return false;
            } catch (IOException ex) {
                appendLog("Nu pot verifica parola: " + ex.getMessage());
                return false;
            }
        }
        appendLog("Ieșirea a fost anulată.");
        return false;
    }

    private boolean showExitDialog() {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Confirmare ieșire");
        alert.setHeaderText("Sigur doriți să închideți aplicația?");
        alert.setContentText("Regulile vor fi dezactivate la ieșire.");
        Optional<ButtonType> res = alert.showAndWait();
        return res.isPresent() && res.get() == ButtonType.OK;
    }

    private void onAddOrEditSite(BlockedSite existing) {
        boolean editMode = existing != null;
        Dialog<BlockedSite> dialog = new Dialog<>();
        dialog.setTitle(editMode ? "Editează site blocat" : "Adaugă site blocat");
        ButtonType okType = new ButtonType(editMode ? "Salvează" : "Adaugă", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(okType, ButtonType.CANCEL);

        TextField titleField = new TextField();
        titleField.setPromptText("Descriere scurtă");
        TextField urlField = new TextField();
        urlField.setPromptText("ex: https://example.com");

        if (editMode) {
            titleField.setText(existing.getTitle());
            urlField.setText(existing.getUrlPattern());
        }

        VBox box = new VBox(8, new Label("Descriere"), titleField, new Label("Link / domeniu"), urlField);
        box.setPadding(new Insets(12));
        dialog.getDialogPane().setContent(box);

        dialog.setResultConverter(btn -> {
            if (btn == okType) {
                String url = urlField.getText().trim();
                if (url.isEmpty()) return null;
                BlockedSite target = editMode ? existing : new BlockedSite();
                target.setTitle(titleField.getText().trim().isEmpty() ? url : titleField.getText().trim());
                target.setUrlPattern(url);
                target.setEnabled(true);
                return target;
            }
            return null;
        });

        dialog.showAndWait().ifPresent(site -> {
            if (!editMode) {
                blockedSites.add(site);
            }
        });
    }

    private void applySiteBlocking() {
        try {
            websiteBlocker.apply(blockedSites);
            appendLog("Blocare site-uri aplicată pentru " + blockedSites.size() + " intrări.");
        } catch (IOException ex) {
            appendLog("Blocarea site-urilor a eșuat: " + ex.getMessage());
        }
    }
}
