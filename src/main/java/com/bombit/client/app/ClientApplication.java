package com.bombit.client.app;

import com.bombit.client.ui.game.MovementGameWindow;
import com.bombit.server.game.model.Direction;
import com.bombit.client.ui.UiTheme;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** M1 launcher: host port name [--headless [seconds]]. Network connect never runs on EDT. */
public final class ClientApplication {
    private ClientApplication() { }
    public static void main(String[] args) throws Exception {
        if(args.length==1&&args[0].equals("--check-runtime")){
            System.out.println("BombIt Classic + Shadow Relay + Paradox / Java "+Runtime.version().feature());return;
        }
        UiTheme.install();
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 8888;
        String name = args.length > 2 ? args[2] : "";
        if (args.length > 3 && args[3].equals("--headless")) {
            headless(host, port, name, args.length > 4 ? Integer.parseInt(args[4]) : 4);
            return;
        }
        if (GraphicsEnvironment.isHeadless()) throw new IllegalStateException("Use --headless for a console smoke check");
        if(args.length<4 || !args[3].equals("--demo")) {
            SwingUtilities.invokeLater(() -> new com.bombit.client.lobby.LobbyWindow(host,port,name).setVisible(true));
            return;
        }
        SwingUtilities.invokeLater(() -> {
            var waiting = new JFrame("Bomb It - " + name);
            var label = new JLabel("Đang kết nối đến máy chủ...", SwingConstants.CENTER);
            label.setFont(UiTheme.HEADING);label.setForeground(UiTheme.TEXT);
            var content=UiTheme.card(new BorderLayout());content.setBorder(BorderFactory.createEmptyBorder(24,24,24,24));
            content.add(label);waiting.setContentPane(content);waiting.setSize(440, 140); waiting.setLocationByPlatform(true);
            waiting.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            var gameWindow = new AtomicReference<MovementGameWindow>();
            var owner = new AtomicReference<GameClientConnection>();
            var client = new GameClientConnection(SwingUtilities::invokeLater, movement -> {
                waiting.dispose();
                var window = new MovementGameWindow(movement, owner.get().localId(), () -> owner.get().close());
                gameWindow.set(window); window.setVisible(true);
            }, label::setText, error -> {
                if (gameWindow.get() != null) gameWindow.get().showError(error.getMessage());
                else label.setText("Kết nối thất bại: " + error.getMessage());
            });
            // Server M1 không hỗ trợ lobby recovery; giữ lỗi ngắt kết nối theo profile demo.
            client.setAutoReconnect(false);
            owner.set(client);
            waiting.addWindowListener(new WindowAdapter() {
                @Override public void windowClosing(WindowEvent event) { client.close(); waiting.dispose(); }
            });
            waiting.setVisible(true);
            var connector = new Thread(() -> {
                try { client.connect(host, port, name); }
                catch (Exception error) { SwingUtilities.invokeLater(() -> label.setText("Kết nối thất bại: " + error.getMessage())); }
            }, "Demo-Client-Connect");
            connector.setDaemon(true); connector.start();
        });
    }
    /** Same connection/controller path as Swing, useful for separate-process smoke checks. */
    private static void headless(String host, int port, String name, int seconds) throws Exception {
        if (seconds < 1 || seconds > 60) throw new IllegalArgumentException("Smoke duration must be 1..60 seconds");
        var ui = Executors.newSingleThreadExecutor();
        var failure = new AtomicReference<Throwable>();
        try (var client = new GameClientConnection(ui, movement -> { }, message -> { }, failure::set)) {
            client.setAutoReconnect(false);
            client.connect(host, port, name);
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
            boolean ready = false;
            int step = 0;
            while (System.nanoTime() < end && failure.get() == null) {
                final int current = step++;
                Boolean applied = ui.submit(() -> {
                    var movement = client.movement();
                    if (movement == null || !movement.isInputReady()) return false;
                    movement.send(movement.prediction().predict(Direction.RIGHT, current < 20));
                    return true;
                }).get(2, TimeUnit.SECONDS);
                if (applied && !ready) {
                    ready = true;
                    System.out.println(ui.submit(() -> "READY local=" + client.localId() + " players="
                            + client.context().players().size() + " match=" + client.context().matchId()).get());
                }
                Thread.sleep(50);
            }
            if (failure.get() != null) throw new IllegalStateException("Client failed", failure.get());
            if (!ready) throw new IllegalStateException("No full snapshot before smoke deadline");
            System.out.println(ui.submit(() -> "FINAL ack=" + client.movement().world().getPlayers()
                    .get(client.localId()).getLastProcessedInput()).get());
        } finally { ui.shutdown(); ui.awaitTermination(2, TimeUnit.SECONDS); }
    }
}
