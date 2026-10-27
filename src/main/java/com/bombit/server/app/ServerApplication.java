package com.bombit.server.app;

/** Điểm khởi tạo và liên kết các thành phần của server. Không đặt luật chơi trong lớp này. */
public final class ServerApplication {
    private ServerApplication() {
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8888;
        if (args.length < 2 || args[1].equals("--lobby")) {
            try(var lobby=new LobbyGameServer(port)) {
                lobby.setSeamlessRecovery(true);
                Thread hook=new Thread(lobby::close,"Lobby-Server-Shutdown");
                Runtime.getRuntime().addShutdownHook(hook);
                try { System.out.println("Lobby server: port="+lobby.port()); lobby.start(); }
                finally { try { Runtime.getRuntime().removeShutdownHook(hook); } catch(IllegalStateException stopping) { } }
            }
            return;
        }
        int players = args.length > 1 ? Integer.parseInt(args[1]) : 2;
        try (var server = new DemoGameServer(port, players)) {
            Thread hook = new Thread(server::close, "Demo-Server-Shutdown");
            Runtime.getRuntime().addShutdownHook(hook);
            try {
                System.out.println("Movement demo: port=" + server.port() + ", waiting for " + players + " clients");
                server.start();
            } finally {
                try { Runtime.getRuntime().removeShutdownHook(hook); } catch (IllegalStateException shuttingDown) { }
            }
        }
    }
}
