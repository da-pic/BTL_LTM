package com.bombit.server.game.core;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class GameCommandQueueDemo {

    public static void main(String[] args)
            throws InterruptedException {

        // 1. Tao GameState va hai Player
        GameMap map = new GameMap(8, 6, 32);
        GameState state = new GameState(map);

        Player p1 = new Player(1, 32, 32, 24, 24, 150);
        Player p2 = new Player(2, 64, 32, 24, 24, 150);

        state.addPlayer(p1);
        state.addPlayer(p2);

        // 2. Tao hang doi
        BlockingQueue<GameCommand> commandQueue =
                new LinkedBlockingQueue<>();

        System.out.println("=== TEST 1: QUEUE CO BAN ===");

        double oldX = p1.getX();

        // 3. Tao va dua command vao hang doi
        GameCommand command = new GameCommand(
                1, Player.Direction.RIGHT, true);

        boolean added = commandQueue.offer(command);

        System.out.println("Them command: " + added);
        System.out.println("So command dang cho: "
                + commandQueue.size());
        System.out.println("P1 x truoc khi xu ly: "
                + p1.getX());

        // 4. Lay command ra khoi hang doi
        GameCommand received = commandQueue.poll();

        if (received != null) {
            System.out.println("Command nhan duoc: "
                    + received);
        }

        // Lay command ra KHONG tu dong lam Player di chuyen
        System.out.println("P1 x sau khi lay command: "
                + p1.getX());

        System.out.println("Vi tri khong doi: "
                + (oldX == p1.getX()));

        System.out.println("\n=== TEST 2: HAI THREAD GUI LENH ===");

        // 5. Hai thread mo phong hai Client gui command
        Thread client1 = new Thread(() -> {
            commandQueue.offer(
                    new GameCommand(
                            1, Player.Direction.RIGHT, true));
        }, "Client-1");

        Thread client2 = new Thread(() -> {
            commandQueue.offer(
                    new GameCommand(
                            2, Player.Direction.UP, true));
        }, "Client-2");

        // 6. Khoi dong hai thread
        client1.start();
        client2.start();

        // Cho ca hai thread gui xong
        client1.join();
        client2.join();

        System.out.println("So command trong queue: "
                + commandQueue.size());

        // 7. Lay tung command ra de kiem tra
        GameCommand cmd;

        while ((cmd = commandQueue.poll()) != null) {
            System.out.println("Da lay: " + cmd);
        }

        // 8. Kiem tra vi tri Player
        System.out.println("P1 x cuoi: " + p1.getX());
        System.out.println("P2 x cuoi: " + p2.getX());

        System.out.println("\n=== KET THUC KIEM THU ===");
    }
}