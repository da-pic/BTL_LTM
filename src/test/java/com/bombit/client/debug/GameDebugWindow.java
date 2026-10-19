package com.bombit.client.debug;

import com.bombit.server.game.core.GameCommand;
import com.bombit.server.game.core.GameLoop;
import com.bombit.server.game.core.GameMap;
import com.bombit.server.game.core.GameState;
import com.bombit.server.game.core.Player;
import com.bombit.server.game.movement.GameStateUpdate;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.LinkedHashMap;
import java.util.Map;

public class GameDebugWindow extends JFrame {

    private final GameLoop gameLoop;
    private final DebugPanel debugPanel;
    private final Timer pollTimer;

    private Player.Direction activeDirection;
    private boolean moving;

    public GameDebugWindow(GameLoop gameLoop) {
        super("Bomb It - Local Debug UI");

        this.gameLoop = gameLoop;
        this.debugPanel = new DebugPanel();

        setDefaultCloseOperation(
                WindowConstants.DO_NOTHING_ON_CLOSE);

        setLayout(new BorderLayout());

        add(debugPanel, BorderLayout.CENTER);

        JLabel footer = new JLabel(
                "Arrow keys: move Player 1 | Close window to stop",
                SwingConstants.CENTER);

        footer.setBorder(
                BorderFactory.createEmptyBorder(6, 4, 6, 4));

        add(footer, BorderLayout.SOUTH);

        // Doc update tu GameLoop ma khong block giao dien.
        pollTimer = new Timer(16, e -> {
            GameStateUpdate update;

            while ((update = gameLoop.pollOutboundUpdate()) != null) {
                debugPanel.applyUpdate(update);
            }

            debugPanel.repaint();
        });

        setupControls();

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                pollTimer.start();
            }

            @Override
            public void windowClosing(WindowEvent e) {
                shutdown();
            }
        });

        pack();
        setLocationRelativeTo(null);
    }

    private void setupControls() {
        bindDirection("UP", Player.Direction.UP);
        bindDirection("DOWN", Player.Direction.DOWN);
        bindDirection("LEFT", Player.Direction.LEFT);
        bindDirection("RIGHT", Player.Direction.RIGHT);
    }

    private void bindDirection(
            String keyName,
            Player.Direction direction) {

        InputMap inputMap = getRootPane().getInputMap(
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        ActionMap actionMap = getRootPane().getActionMap();

        String pressKey = "move-press-" + keyName;
        String releaseKey = "move-release-" + keyName;

        inputMap.put(
                KeyStroke.getKeyStroke("pressed " + keyName),
                pressKey);

        actionMap.put(pressKey, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                startMoving(direction);
            }
        });

        inputMap.put(
                KeyStroke.getKeyStroke("released " + keyName),
                releaseKey);

        actionMap.put(releaseKey, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                stopMoving(direction);
            }
        });
    }

    private void startMoving(Player.Direction direction) {

        // Bo qua su kien lap khi phim van dang duoc nhan.
        if (moving && activeDirection == direction) {
            return;
        }

        GameCommand command = new GameCommand(
                1, direction, true);

        if (gameLoop.submitCommand(command)) {
            activeDirection = direction;
            moving = true;

            debugPanel.setInputMessage(
                    "Input: moving " + direction);
        } else {
            debugPanel.setInputMessage(
                    "Input rejected: command queue unavailable");
        }
    }

    private void stopMoving(Player.Direction direction) {

        // Neu da chuyen huong, bo qua su kien nha phim cu.
        if (!moving || activeDirection != direction) {
            return;
        }

        GameCommand command = new GameCommand(
                1, direction, false);

        if (gameLoop.submitCommand(command)) {
            moving = false;

            debugPanel.setInputMessage("Input: stopped");
        } else {
            debugPanel.setInputMessage(
                    "Input rejected: command queue unavailable");
        }
    }

    private void shutdown() {
        pollTimer.stop();
        gameLoop.stop();
        dispose();
    }

    public static void main(String[] args) {

        SwingUtilities.invokeLater(() -> {

            // Tao ban do va tuong co dinh.
            GameMap map = createTestMap();

            GameState state = new GameState(map);

            // Player 1 nam ben trai de kiem tra di chuyen.
            Player p1 = new Player(
                    1, 64, 64, 24, 24, 150);

            // Player 2 nam ben phai de kiem tra dong bo.
            Player p2 = new Player(
                    2, 256, 128, 24, 24, 150);

            state.addPlayer(p1);
            state.addPlayer(p2);

            state.startMatch();

            GameLoop gameLoop = new GameLoop(state);
            gameLoop.start();

            GameDebugWindow window =
                    new GameDebugWindow(gameLoop);

            window.setVisible(true);
        });
    }

    private static GameMap createTestMap() {

        GameMap map = new GameMap(17, 15, 32);

        // Tuong bao quanh ban do.
        for (int x = 0; x < map.getWidthInTiles(); x++) {
            map.setTile(x, 0, GameMap.TileType.HARD_WALL);

            map.setTile(
                    x,
                    map.getHeightInTiles() - 1,
                    GameMap.TileType.HARD_WALL);
        }

        for (int y = 0; y < map.getHeightInTiles(); y++) {
            map.setTile(0, y, GameMap.TileType.HARD_WALL);

            map.setTile(
                    map.getWidthInTiles() - 1,
                    y,
                    GameMap.TileType.HARD_WALL);
        }

        // Mot block co the pha.
        map.setTile(4, 2, GameMap.TileType.BREAKABLE_BLOCK);

        // Tuong cung o giua ban do.
        for (int y = 2; y <= 5; y++) {
            map.setTile(6, y, GameMap.TileType.HARD_WALL);
        }

        return map;
    }

    // Trang thai hien thi doc lap, chi duoc cap nhat tren EDT.
    private static class DebugPanel extends JPanel {

        private int mapWidth;
        private int mapHeight;
        private int tileSize;

        private GameMap.TileType[][] tiles;

        private final Map<Integer, GameStateUpdate.PlayerData> players =
                new LinkedHashMap<>();

        private long latestTick = -1;

        private int fullStateCount;
        private int deltaCount;

        private int lastTileChangeCount;
        private int lastPlayerChangeCount;

        private String matchState = "UNKNOWN";

        private String syncMessage =
                "Waiting for FULL_STATE";

        private String inputMessage =
                "Input: ready";

        DebugPanel() {
            setPreferredSize(new Dimension(640, 430));
            setBackground(Color.WHITE);
        }

        void setInputMessage(String message) {
            inputMessage = message;
            repaint();
        }

        void applyUpdate(GameStateUpdate update) {

            if (update.isFullState()) {
                applyFullState(update);
            } else {
                applyDelta(update);
            }
        }

        private void applyFullState(GameStateUpdate update) {

            int width = update.getMapWidth();
            int height = update.getMapHeight();

            if (width <= 0
                    || height <= 0
                    || update.getFullMap().size() != width * height) {

                syncMessage = "Invalid FULL_STATE";
                return;
            }

            mapWidth = width;
            mapHeight = height;
            tileSize = update.getTileSize();

            tiles = new GameMap.TileType[height][width];

            // Chuyen danh sach tile thanh mang hai chieu.
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {

                    int index = y * width + x;

                    tiles[y][x] =
                            update.getFullMap().get(index);
                }
            }

            players.clear();

            for (GameStateUpdate.PlayerData player
                    : update.getPlayers()) {

                players.put(player.getId(), player);
            }

            matchState = update.getMatchState().name();
            latestTick = update.getTick();

            fullStateCount++;

            lastTileChangeCount = 0;
            lastPlayerChangeCount = players.size();

            syncMessage = "FULL_STATE applied";
        }

        private void applyDelta(GameStateUpdate update) {

            if (tiles == null) {
                syncMessage = "Waiting for initial FULL_STATE";
                return;
            }

            // Chi ap dung delta neu Client co dung trang thai co so.
            if (update.getBaseTick() != latestTick) {

                syncMessage = "DESYNC: expected baseTick "
                        + latestTick
                        + ", received "
                        + update.getBaseTick();

                return;
            }

            // Kiem tra cac o truoc khi ap dung delta.
            for (GameStateUpdate.TileChange change
                    : update.getTileChanges()) {

                if (change.getX() < 0
                        || change.getX() >= mapWidth
                        || change.getY() < 0
                        || change.getY() >= mapHeight) {

                    syncMessage = "Invalid tile coordinate";
                    return;
                }
            }

            for (GameStateUpdate.TileChange change
                    : update.getTileChanges()) {

                tiles[change.getY()][change.getX()] =
                        change.getNewType();
            }

            for (GameStateUpdate.PlayerData player
                    : update.getPlayerChanges()) {

                players.put(player.getId(), player);
            }

            for (Integer id : update.getRemovedPlayerIds()) {
                players.remove(id);
            }

            if (update.isMatchStateChanged()) {
                matchState = update.getMatchState().name();
            }

            latestTick = update.getTick();

            deltaCount++;

            lastTileChangeCount =
                    update.getTileChanges().size();

            lastPlayerChangeCount =
                    update.getPlayerChanges().size();

            syncMessage = "DELTA applied";
        }

        @Override
        protected void paintComponent(Graphics graphics) {

            super.paintComponent(graphics);

            Graphics2D g = (Graphics2D) graphics.create();

            try {
                g.setRenderingHint(
                        RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);

                g.setColor(Color.BLACK);

                g.drawString(
                        "Sync: " + syncMessage,
                        20, 20);

                g.drawString(
                        "Match: " + matchState
                                + " | Tick: " + latestTick
                                + " | FULL: " + fullStateCount
                                + " | DELTA: " + deltaCount,
                        20, 42);

                g.drawString(
                        "Last changes: tiles="
                                + lastTileChangeCount
                                + ", players="
                                + lastPlayerChangeCount,
                        20, 64);

                g.drawString(inputMessage, 20, 84);

                if (tiles == null) {
                    g.drawString(
                            "Waiting for initial state...",
                            20, 120);

                    return;
                }

                int boardX = 20;
                int boardY = 100;

                // Ve ban do.
                for (int y = 0; y < mapHeight; y++) {
                    for (int x = 0; x < mapWidth; x++) {

                        int px = boardX + x * tileSize;
                        int py = boardY + y * tileSize;

                        GameMap.TileType tile = tiles[y][x];

                        if (tile == GameMap.TileType.HARD_WALL) {
                            g.setColor(new Color(75, 85, 99));

                        } else if (
                                tile == GameMap.TileType.BREAKABLE_BLOCK) {

                            g.setColor(new Color(217, 155, 75));

                        } else {
                            g.setColor(new Color(242, 246, 250));
                        }

                        g.fillRect(px, py, tileSize, tileSize);

                        g.setColor(new Color(195, 200, 208));

                        g.drawRect(px, py, tileSize, tileSize);
                    }
                }

                // Ve cac Player tu trang thai Client da ap dung.
                for (GameStateUpdate.PlayerData player
                        : players.values()) {

                    int px = boardX
                            + (int) Math.round(player.getX());

                    int py = boardY
                            + (int) Math.round(player.getY());

                    int width =
                            (int) Math.round(player.getWidth());

                    int height =
                            (int) Math.round(player.getHeight());

                    if (player.getId() == 1) {
                        g.setColor(new Color(37, 99, 235));
                    } else {
                        g.setColor(new Color(219, 39, 119));
                    }

                    g.fillOval(px, py, width, height);

                    g.setColor(Color.BLACK);
                    g.drawOval(px, py, width, height);

                    g.drawString(
                            "P" + player.getId(),
                            px, py - 3);
                }

                // Hien thi vi tri hien tai cua tung Player.
                int statusY =
                        boardY + mapHeight * tileSize + 22;

                for (GameStateUpdate.PlayerData player
                        : players.values()) {

                    g.setColor(Color.BLACK);

                    g.drawString(
                            "P" + player.getId()
                                    + " x=" + Math.round(player.getX())
                                    + " y=" + Math.round(player.getY())
                                    + " dir=" + player.getDirection()
                                    + " moving=" + player.isMoving()
                                    + " alive=" + player.isAlive(),
                            20, statusY);

                    statusY += 18;
                }

            } finally {
                g.dispose();
            }
        }
    }
}
