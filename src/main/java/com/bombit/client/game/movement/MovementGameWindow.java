package com.bombit.client.game.movement;

import com.bombit.client.network.ClientNetwork;
import com.bombit.server.game.movement.EntityData;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

/** Cửa sổ gameplay movement dùng mạng thật qua ClientNetwork đã kết nối.
 * Tầng lobby truyền playerId số do server cấp; cửa sổ không tự đăng nhập/tạo phòng.
 * Phải tạo trên EDT. Caller sở hữu ClientNetwork và quyết định đóng kết nối khi thoát.
 */
public final class MovementGameWindow extends JFrame {
    private final MovementClientController movement;
    private final com.bombit.client.game.item.ItemRenderer itemRenderer=new com.bombit.client.game.item.ItemRenderer();
    private final TileGridRenderer gridRenderer=new TileGridRenderer();
    private InputController input;
    private final Timer render;
    private final JLabel status=new JLabel("Đang chờ full snapshot...");
    private final JLabel cellStatus=new JLabel("Viền xanh: ô đang đứng — Space: đặt bom theo ô");
    public MovementGameWindow(ClientNetwork network,int localId,Runnable resync,Runnable onClose) {
        this(null,network,localId,resync,onClose);
    }
    public MovementGameWindow(MovementClientController controller,int localId,Runnable onClose) {
        this(controller,null,localId,()->{},onClose);
    }
    private MovementGameWindow(MovementClientController controller,ClientNetwork network,int localId,Runnable resync,Runnable onClose) {
        super("Bomb It — Player " + localId);
        movement=controller!=null?controller:new MovementClientController(network,localId,SwingUtilities::invokeLater,resync,error -> {
            if(input!=null) input.close(); status.setText("Lỗi: "+error.getMessage());
        });
        JPanel board=new JPanel() {
            @Override protected void paintComponent(Graphics graphics) {
                super.paintComponent(graphics);
                var world=movement.world(); int size=world.getTileSize();
                if(size<=0) return;
                var g=(Graphics2D)graphics.create();
                try {
                    gridRenderer.render(g,world.getWidth(),world.getHeight(),size,world.getTiles());
                    var localPlayer=world.getPlayers().get(localId);
                    if(localPlayer!=null&&localPlayer.isAlive())gridRenderer.markPlayerCell(g,
                        com.bombit.server.game.model.TilePosition.containing(localPlayer.getX()+localPlayer.getWidth()/2,
                            localPlayer.getY()+localPlayer.getHeight()/2,size),size);
                    // Items, bombs and flames use the integrated Java 2D renderers.
                    for(var e:world.getEntities().values()) {
                        if(e.kind()==EntityData.Kind.BOMB) continue;
                        itemRenderer.render(g,new com.bombit.client.game.item.ItemView(e.id(),
                                com.bombit.server.game.item.ItemType.fromWireId(e.variant()),
                                new com.bombit.server.game.model.TilePosition(e.tileX(),e.tileY())),size,0,0,System.currentTimeMillis());
                    }
                    movement.bombs().render(g,size,System.nanoTime());
                    for(var p:world.getPlayers().values()) {
                        double x=p.getX(),y=p.getY();
                        if(p.getId()==localId && movement.prediction()!=null) {
                            var local=movement.prediction().position(); x=local.getX(); y=local.getY();
                        } else {
                            var pos=movement.interpolation().sample(p.getId(),movement.renderTick());
                            if(pos!=null) { x=pos.x();y=pos.y(); }
                        }
                        g.setColor(!p.isAlive()?Color.GRAY:p.getId()==localId?Color.BLUE:Color.MAGENTA);
                        g.fillOval((int)x,(int)y,(int)p.getWidth(),(int)p.getHeight());
                        var stats=world.getMatchFrame()==null?null:world.getMatchFrame().players().stream()
                            .filter(s->s.playerId()==p.getId()).findFirst().orElse(null);
                        if(stats!=null&&stats.shield()){
                            g.setColor(new Color(75,160,255));g.drawOval((int)x-3,(int)y-3,(int)p.getWidth()+6,(int)p.getHeight()+6);
                        }
                        g.setColor(Color.BLACK);g.drawString("P"+p.getId()+(stats==null?"":" ["+stats.lives()+"]"),(int)x,(int)y+12);
                    }
                } finally { g.dispose(); }
            }
        };
        board.setPreferredSize(new Dimension(544,480)); add(board,BorderLayout.CENTER);add(status,BorderLayout.SOUTH);
        var mapHeader=new JPanel(new BorderLayout());mapHeader.add(cellStatus,BorderLayout.NORTH);
        mapHeader.add(new JLabel("Đá xám: không phá được | Thùng gỗ: phá được"),BorderLayout.SOUTH);
        add(mapHeader,BorderLayout.NORTH);
        status.setFont(status.getFont().deriveFont(11f));
        render=new Timer(16,e -> {
            if(input==null && movement.isInputReady()) {
                input=new InputController(getRootPane(),this,movement.prediction(),movement::send,
                        error -> status.setText("Input lỗi: "+error.getMessage()),movement::isInputReady); input.start();
                status.setText(movement.bombs().enabled()?"Phím mũi tên để di chuyển — Space để đặt bom":"Phím mũi tên để di chuyển");
            }
            // Chết thì không tiếp tục sinh input chưa ACK; renderer vẫn nhận snapshot của người còn lại.
            var local=movement.world().getPlayers().get(localId);
            if(local!=null&&movement.world().getTileSize()>0){
                var cell=com.bombit.server.game.model.TilePosition.containing(local.getX()+local.getWidth()/2,
                    local.getY()+local.getHeight()/2,movement.world().getTileSize());
                cellStatus.setText("Ô đang đứng: cột "+(cell.x()+1)+", hàng "+(cell.y()+1)+" | Viền xanh | Space: đặt bom theo ô");
            }
            var frame=movement.world().getMatchFrame();
            if(local!=null&&local.isAlive()&&frame!=null){
                var stats=frame.players().stream().filter(p->p.playerId()==localId).findFirst().orElse(null);
                if(stats!=null)status.setText("Time "+frame.remainingSeconds()+"s | Lives "+stats.lives()+" | Shield "+(stats.shield()?"ON":"OFF")+" | Bombs "+stats.bombLimit()+" | Range "+stats.range()+" | Speed "+(int)local.getSpeed()+" | Space: bomb");
            }
            if(movement.isClosed()) showError("Connection closed. Close this window to exit.");
            if(input!=null && local!=null && !local.isAlive()) {
                input.close(); status.setText("Bạn đã bị loại khỏi trận.");
            }
            board.repaint();
        });
        // Edge-trigger Space: holding the key cannot place another bomb after the first explodes.
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("pressed SPACE"),"plant-bomb");
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("released SPACE"),"release-bomb");
        var held=new boolean[1];
        getRootPane().getActionMap().put("plant-bomb",new AbstractAction(){
            public void actionPerformed(ActionEvent e){if(!held[0]) {
                held[0]=true;
                try { movement.plantBomb(); } catch(RuntimeException error) { showError(error.getMessage()); }
            }}
        });
        getRootPane().getActionMap().put("release-bomb",new AbstractAction(){
            public void actionPerformed(ActionEvent e){held[0]=false;}
        });
        addWindowFocusListener(new WindowAdapter(){@Override public void windowLostFocus(WindowEvent e){held[0]=false;}});
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                disposeGame();onClose.run();
            }
        });
        pack();setLocationByPlatform(true);render.start();
    }
    public void disposeGame() {
        render.stop(); if(input!=null) input.close(); movement.close(); dispose();
    }
    public void showError(String message) {
        if(input!=null) input.close();
        render.stop();
        status.setText(message);
    }
}
