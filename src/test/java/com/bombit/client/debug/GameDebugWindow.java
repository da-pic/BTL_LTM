package com.bombit.client.debug;

import com.bombit.server.game.core.GameCommand;
import com.bombit.server.game.core.MoveCommand;
import com.bombit.server.game.core.StopMovingCommand;
import com.bombit.server.game.model.Direction;
import com.bombit.server.game.model.TileType;
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

/**
 * Giao diện Swing kiểm tra movement và full/delta trong cùng tiến trình.
 * GameLoop chạy nền; Swing Timer đọc queue không chặn và áp dụng update trên EDT.
 * Dữ liệu đi qua codec để kiểm tra encode/decode nhưng chưa truyền TCP.
 * Phím điều khiển Player 1; panel chỉ render bản sao client, không đọc Player mutable của server.
 */
public class GameDebugWindow extends JFrame {

    private final GameLoop gameLoop;
    private final DebugPanel debugPanel;
    private final Timer pollTimer;

    private Direction activeDirection;
    private boolean moving;

    // Mục đích: Khởi tạo GameDebugWindow và kiểm tra dữ liệu đầu vào; xem chú thích của lớp để biết đơn vị và trách nhiệm.
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
                if (debugPanel.replica.needsFullState()) gameLoop.requestFullState();
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

    // Mục đích: Đăng ký các hướng bằng Swing key bindings, tách thao tác bàn phím khỏi logic movement server.
    private void setupControls() {
        bindDirection("UP", Direction.UP);
        bindDirection("DOWN", Direction.DOWN);
        bindDirection("LEFT", Direction.LEFT);
        bindDirection("RIGHT", Direction.RIGHT);
    }

    // Mục đích: Ánh xạ sự kiện nhấn và nhả riêng cho một phím; WHEN_IN_FOCUSED_WINDOW nhận phím khi cửa sổ có focus.
    private void bindDirection(
            String keyName,
            Direction direction) {

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

    // Mục đích: Bỏ sự kiện lặp của cùng hướng và chỉ đổi trạng thái input local khi queue nhận command thành công.
    private void startMoving(Direction direction) {

        // Bo qua su kien lap khi phim van dang duoc nhan.
        if (moving && activeDirection == direction) {
            return;
        }

        GameCommand command = new MoveCommand(1, direction);

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

    // Mục đích: Bỏ sự kiện nhả phím hướng cũ sau khi đổi hướng. Nếu queue từ chối STOP, bản demo chỉ báo lỗi; tích hợp thật cần cơ chế xử lý tiếp.
    private void stopMoving(Direction direction) {

        // Neu da chuyen huong, bo qua su kien nha phim cu.
        if (!moving || activeDirection != direction) {
            return;
        }

        GameCommand command = new StopMovingCommand(1, direction, 0);

        if (gameLoop.submitCommand(command)) {
            moving = false;

            debugPanel.setInputMessage("Input: stopped");
        } else {
            debugPanel.setInputMessage(
                    "Input rejected: command queue unavailable");
        }
    }

    // Mục đích: Dừng timer trước, dừng/join loop rồi đóng cửa sổ để không còn callback đọc queue sau khi thoát.
    private void shutdown() {
        pollTimer.stop();
        gameLoop.stop();
        dispose();
    }

    // Mục đích: Khởi tạo demo Swing trên EDT; tạo GameState trước khi start loop. Đây là điểm chạy local để quan sát input và đồng bộ.
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

             Player p3 = new Player(
                    3, 32, 128, 24, 24, 150);

            state.addPlayer(p1);
            state.addPlayer(p2);
            state.addPlayer(p3);

            state.startMatch();

            GameLoop gameLoop = new GameLoop(state);
            gameLoop.start();

            GameDebugWindow window =
                    new GameDebugWindow(gameLoop);

            window.setVisible(true);
        });
    }

    // Mục đích: Tạo map cố định có tường biên, tường giữa và block để thử va chạm bằng bàn phím.
    private static GameMap createTestMap() {

        GameMap map = new GameMap(17, 15, 32);

        // Tuong bao quanh ban do.
        for (int x = 0; x < map.getWidthInTiles(); x++) {
            map.setTile(x, 0, TileType.HARD_WALL);

            map.setTile(
                    x,
                    map.getHeightInTiles() - 1,
                    TileType.HARD_WALL);
        }

        for (int y = 0; y < map.getHeightInTiles(); y++) {
            map.setTile(0, y, TileType.HARD_WALL);

            map.setTile(
                    map.getWidthInTiles() - 1,
                    y,
                    TileType.HARD_WALL);
        }

        // Mot block co the pha.
        map.setTile(4, 2, TileType.BREAKABLE_BLOCK);
        map.setTile(5, 3, TileType.BREAKABLE_BLOCK);
        map.setTile(9, 1, TileType.BREAKABLE_BLOCK);
        map.setTile(13, 5, TileType.BREAKABLE_BLOCK);

        // Tuong cung o giua ban do.
        for (int y = 2; y <= 5; y++) {
            map.setTile(6, y, TileType.HARD_WALL);
        }

        return map;
    }

    // Trang thai hien thi doc lap, chi duoc cap nhat tren EDT.
    private static class DebugPanel extends JPanel {

        private final com.bombit.client.game.movement.ClientWorldState replica =
                new com.bombit.client.game.movement.ClientWorldState();

        private int mapWidth;
        private int mapHeight;
        private int tileSize;

        private TileType[][] tiles;

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
            // Dùng chung codec và replica như client mạng sau này; ở đây chưa qua TCP.
            try {
                update = com.bombit.server.game.movement.SnapshotPacketCodec.decode(
                        com.bombit.server.game.movement.SnapshotPacketCodec.encode(update));
            } catch (java.io.IOException error) {
                syncMessage = "Snapshot decode failed: " + error.getMessage();
                return;
            }
            var result = replica.apply(update);
            syncMessage = result.name();
            if (result != com.bombit.client.game.movement.ClientWorldState.Result.APPLIED) return;
            mapWidth = replica.getWidth(); mapHeight = replica.getHeight(); tileSize = replica.getTileSize();
            tiles = new TileType[mapHeight][mapWidth];
            var flat = replica.getTiles();
            for (int y = 0; y < mapHeight; y++) {
                for (int x = 0; x < mapWidth; x++) tiles[y][x] = flat.get(y * mapWidth + x);
            }
            players.clear(); players.putAll(replica.getPlayers());
            matchState = replica.getMatchState().name(); latestTick = replica.getTick();
            if (update.isFullState()) fullStateCount++; else deltaCount++;
            lastTileChangeCount = update.getTileChanges().size();
            lastPlayerChangeCount = update.isFullState() ? update.getPlayers().size() : update.getPlayerChanges().size();
            setPreferredSize(new Dimension(Math.max(640, mapWidth * tileSize + 40),
                    mapHeight * tileSize + 150 + players.size() * 18));
            revalidate();
            if (getTopLevelAncestor() instanceof JFrame frame) frame.pack();
        }

        @Override
        // Mục đích: Vẽ từ bản sao client trên EDT; tạo Graphics2D riêng và dispose sau dùng để không rò tài nguyên hoặc làm đổi Graphics gốc.
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

                        TileType tile = tiles[y][x];

                        if (tile == TileType.HARD_WALL) {
                            g.setColor(new Color(75, 85, 99));

                        } else if (
                                tile == TileType.BREAKABLE_BLOCK) {

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
                                    + " dir=" + player.direction()
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
