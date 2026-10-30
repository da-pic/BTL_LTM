package com.bombit.server.app;

/** Điểm khởi tạo và liên kết các thành phần của server. Không đặt luật chơi trong lớp này. */
public final class ServerApplication {
    private ServerApplication() {
    }

    public static void main(String[] args) throws Exception {
        if(args.length==1&&args[0].equals("--check-runtime")){System.out.println("BombIt server / Java "+Runtime.version().feature());return;}
        try{run(args);}catch(java.net.BindException occupied){System.err.println("Khong mo duoc cong TCP. Dong server cu hoac chon cong khac: MAY_CHU.cmd 8889");System.exit(1);}
    }
    private static void run(String[] args)throws Exception{
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8888;
        if (args.length < 2 || args[1].equals("--lobby")) {
            try(var lobby=new LobbyGameServer(port)) {
                lobby.setSeamlessRecovery(true);
                Thread hook=new Thread(lobby::close,"Lobby-Server-Shutdown");
                Runtime.getRuntime().addShutdownHook(hook);
                try { printAddresses(lobby.port()); lobby.start(); }
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
    private static void printAddresses(int port){
        System.out.println("BombIt Classic + Shadow Relay + Paradox | TCP "+port);
        System.out.println("Cung may: 127.0.0.1 | May khac: nhap IPv4 LAN/Radmin cua server.");
        try{
            for(var network:java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())){
                if(!network.isUp()||network.isLoopback())continue;
                for(var address:java.util.Collections.list(network.getInetAddresses()))if(address instanceof java.net.Inet4Address)
                    System.out.println("IPv4 "+address.getHostAddress()+" ("+network.getDisplayName()+")");
            }
        }catch(java.net.SocketException unavailable){System.out.println("Xem dia chi server trong Radmin hoac ipconfig.");}
        System.out.println("Shadow: can 4 nguoi, 3 khach READY, host START. Giu cua so nay mo; Ctrl+C de dung.");
        System.out.println("Classic/Paradox: ca nhan 2-4 nguoi hoac doi 2-2 du 4 nguoi; doi kieu choi se huy READY.");
        System.out.println("Paradox: R dao 2 giay, moi nguoi 1 luot; F1 xem luat. Dau doi van co sat thuong dong doi.");
    }
}
