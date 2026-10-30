package com.bombit.client.ui.game;

import com.bombit.client.game.movement.MovementClientController;
import com.bombit.client.network.ClientNetwork;
import com.bombit.client.ui.UiTheme;
import com.bombit.common.dto.MapTheme;
import com.bombit.server.game.movement.EntityData;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;

/** Network gameplay window. Created and updated only on the Swing EDT. */
public final class MovementGameWindow extends JFrame {
    private final MovementClientController movement;
    private final com.bombit.client.ui.game.item.ItemRenderer itemRenderer=new com.bombit.client.ui.game.item.ItemRenderer();
    private final TileGridRenderer gridRenderer=new TileGridRenderer();
    private final int localId;
    private PlayerHudPanel playerHud;
    private ShadowHudPanel shadowHud;
    private ParadoxHudPanel paradoxHud;
    private boolean teamUiConfigured;
    private JPanel contentPanel;
    private final ShadowOverlayRenderer shadowRenderer=new ShadowOverlayRenderer();
    private BombInputBindings bombInput;
    private final JLabel controls=new JLabel("ĐIỀU KHIỂN   ↑ ↓ ← → Di chuyển · SPACE Đặt bom");
    private InputController input;
    private final Timer render;
    private String recoveryStatus;
    private final JLabel status=new JLabel("Đang đồng bộ bản đồ...",SwingConstants.CENTER);
    private final JLabel timeValue=value("--:--"), livesValue=value("—"), bombValue=value("—"), rangeValue=value("—"), speedValue=value("—"), shieldValue=value("TẮT");

    public MovementGameWindow(ClientNetwork network,int localId,Runnable resync,Runnable onClose){this(null,network,localId,MapTheme.CLASSIC,resync,onClose);}
    public MovementGameWindow(MovementClientController controller,int localId,Runnable onClose){this(controller,null,localId,MapTheme.CLASSIC,()->{},onClose);}
    public MovementGameWindow(MovementClientController controller,int localId,MapTheme theme,Runnable onClose){this(controller,null,localId,theme,()->{},onClose);}
    private MovementGameWindow(MovementClientController controller,ClientNetwork network,int localId,MapTheme theme,Runnable resync,Runnable onClose) {
        super("Bomb It Arena — Người chơi "+localId);this.localId=localId;UiTheme.install();
        movement=controller!=null?controller:new MovementClientController(network,localId,SwingUtilities::invokeLater,resync,error->{if(input!=null)input.close();showError("Lỗi: "+error.getMessage());});
        ArenaPanel board=new ArenaPanel(theme);setContentPane(buildLayout(board));
        render=new Timer(16,e->updateFrame(board));
        bombInput=new BombInputBindings(getRootPane(),this,movement::isInputReady,movement::plantBomb,movement::plantScan,movement::rewind,error->showError(error.getMessage()));
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("pressed F1"),"game-help");
        getRootPane().getActionMap().put("game-help",new AbstractAction(){@Override public void actionPerformed(ActionEvent e){
            JOptionPane.showMessageDialog(MovementGameWindow.this,MatchPresentation.rules(movement.metadata()),"Luật chơi",JOptionPane.INFORMATION_MESSAGE);
        }});
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);addWindowListener(new WindowAdapter(){@Override public void windowClosing(WindowEvent e){disposeGame();onClose.run();}});
        pack();setResizable(false);setLocationByPlatform(true);render.start();
    }

    private JComponent buildLayout(JComponent board) {
        JPanel root=new GradientGamePanel();root.setLayout(new BorderLayout(0,10));root.setBorder(new EmptyBorder(12,16,14,16));
        JPanel content=UiTheme.panel(new BorderLayout(12,0));contentPanel=content;playerHud=new PlayerHudPanel();playerHud.setPreferredSize(new Dimension(180,480));content.add(playerHud,BorderLayout.WEST);JPanel boardFrame=new UiTheme.RoundedPanel(new BorderLayout(),new Color(7,11,20),18);boardFrame.setBorder(new EmptyBorder(8,8,8,8));boardFrame.add(board);content.add(boardFrame);root.add(content);
        JPanel footer=UiTheme.card(new GridLayout(2,1,0,4));footer.setBorder(new EmptyBorder(8,14,8,14));controls.setForeground(UiTheme.MUTED);controls.setFont(UiTheme.BODY.deriveFont(Font.BOLD,11f));footer.add(controls);status.setForeground(UiTheme.ACCENT);footer.add(status);root.add(footer,BorderLayout.SOUTH);return root;
    }

    private JPanel stat(String label,JLabel value,Color color){JPanel p=new UiTheme.RoundedPanel(new GridLayout(2,1),new Color(12,20,36),12);p.setBorder(new EmptyBorder(5,10,5,10));JLabel l=new JLabel(label);l.setForeground(UiTheme.MUTED);l.setFont(UiTheme.BODY.deriveFont(Font.BOLD,9f));value.setForeground(color);value.setHorizontalAlignment(SwingConstants.LEFT);p.add(l);p.add(value);return p;}
    private static JLabel value(String text){JLabel label=new JLabel(text,SwingConstants.RIGHT);label.setFont(UiTheme.HEADING);return label;}

    private void updateFrame(JComponent board) {
        if(isParadox()){
            if(paradoxHud==null){paradoxHud=new ParadoxHudPanel();getContentPane().add(paradoxHud,BorderLayout.NORTH);pack();setTitle("Paradox — P"+localId);}
            paradoxHud.update(movement,localId);controls.setText("↑ ↓ ← → Di chuyển · SPACE Bom · R Đảo 2 giây · F1 Luật chơi");
            if(recoveryStatus==null&&!movement.isRecovering())status.setText("Paradox · Mỗi người 1 lượt rewind · Đồng hồ trận không quay lại");
        }
        if(movement.shadow()!=null){
            if(shadowHud==null){shadowHud=new ShadowHudPanel();contentPanel.remove(playerHud);contentPanel.add(shadowHud,BorderLayout.WEST);pack();setTitle("Shadow Relay — "+movement.metadata().players().stream().filter(p->p.playerId()==localId).findFirst().orElseThrow().team()+" — P"+localId);}
            shadowHud.update(movement,localId);controls.setText("↑ ↓ ← → Di chuyển · SPACE Bom · F Scan · F1 Luật chơi");
            if(recoveryStatus==null&&!movement.isRecovering())status.setText(movement.isInputReady()?"Chiếm trạm để ghi điểm · Có sát thương đồng đội":"Đang theo dõi tầm nhìn của đội — điều khiển bị khóa");
        }
        if(input==null&&movement.isInputReady()){input=new InputController(getRootPane(),this,movement.prediction(),movement::send,error->showError("Lỗi điều khiển: "+error.getMessage()),movement::isInputReady,movement::inputGeneration);input.start();status.setText("Sẵn sàng chiến đấu!  Dùng phím mũi tên và Space để đặt bom.");status.setForeground(UiTheme.ACCENT);}
        var local=movement.world().getPlayers().get(localId);var frame=movement.world().getMatchFrame();
        if(local!=null&&frame!=null){var stats=frame.players().stream().filter(p->p.playerId()==localId).findFirst().orElse(null);timeValue.setText(String.format("%02d:%02d",frame.remainingSeconds()/60,frame.remainingSeconds()%60));if(frame.remainingSeconds()<=30)timeValue.setForeground(UiTheme.DANGER);if(stats!=null){livesValue.setText("♥ "+stats.lives());bombValue.setText("● "+stats.bombLimit());rangeValue.setText("🔥 "+stats.range());speedValue.setText("» "+(int)local.getSpeed());shieldValue.setText(stats.shield()?"BẬT":"TẮT");shieldValue.setForeground(stats.shield()?new Color(102,158,255):UiTheme.MUTED);}}
        if(recoveryStatus!=null||movement.isRecovering()){
            status.setText(recoveryStatus!=null?recoveryStatus:isParadox()?"Đang đồng bộ timeline — chờ full state...":"Đang phục hồi phiên — chờ full state...");status.setForeground(UiTheme.PRIMARY);
        }
        if(movement.isClosed())showError("Mất kết nối. Hãy đóng cửa sổ để quay lại Lobby.");
        if(input!=null&&local!=null&&!local.isAlive()){input.close();input=null;status.setText("Bạn đã bị loại — đang theo dõi những người chơi còn lại.");status.setForeground(UiTheme.DANGER);}
        if(movement.metadata()!=null&&movement.metadata().configuration().isTeamMatch()&&movement.shadow()==null){
            var team=MatchPresentation.team(movement.metadata(),localId);
            if(!teamUiConfigured){setTitle((isParadox()?"Paradox":"Classic")+" — Đội "+team+" — P"+localId);teamUiConfigured=true;}
            controls.setText("Đội "+team+" · ↑ ↓ ← → Di chuyển · SPACE Bom"+(isParadox()?" · R Rewind":"")+" · F1 Luật chơi");
            if(recoveryStatus==null&&!movement.isRecovering()&&local!=null&&local.isAlive())status.setText("Đội "+team+" · Loại hết đội đối phương để thắng · Có sát thương đồng đội");
        }
        board.repaint();if(playerHud!=null)playerHud.repaint();
    }
    private boolean isParadox(){return movement.metadata()!=null&&movement.metadata().configuration().gameMode()==com.bombit.common.dto.GameMode.PARADOX;}

    public void disposeGame(){render.stop();if(input!=null)input.close();if(bombInput!=null)bombInput.close();movement.close();dispose();}
    /** EDT: chỉ đổi nhãn khi phục hồi, giữ nguyên world và controller đang chơi. */
    public void setRecoveryStatus(String message){recoveryStatus=message;status.setText(message!=null?message:"Đã đồng bộ — tiếp tục trận đấu.");status.setForeground(UiTheme.ACCENT);}
    public void showError(String message){if(input!=null)input.close();render.stop();status.setText(message);status.setForeground(UiTheme.DANGER);}

    private final class ArenaPanel extends JPanel {
        private final MapTheme theme;
        ArenaPanel(MapTheme theme){this.theme=java.util.Objects.requireNonNull(theme);setPreferredSize(new Dimension(544,480));setOpaque(false);setTitle("Bomb It Arena — "+theme.label()+" — P"+localId);}
        @Override protected void paintComponent(Graphics graphics){super.paintComponent(graphics);var world=movement.world();int size=world.getTileSize();if(size<=0)return;Graphics2D g=(Graphics2D)graphics.create();try{g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);gridRenderer.render(g,world.getWidth(),world.getHeight(),size,world.getTiles(),theme);if(movement.shadow()!=null)shadowRenderer.fog(g,movement.shadow(),world.getWidth(),world.getHeight(),size);for(var e:world.getEntities().values()){if(e.kind()!=EntityData.Kind.ITEM)continue;itemRenderer.render(g,new com.bombit.client.ui.game.item.ItemView(e.id(),com.bombit.server.game.item.ItemType.fromWireId(e.variant()),new com.bombit.server.game.model.TilePosition(e.tileX(),e.tileY())),size,0,0,System.currentTimeMillis());}if(movement.shadow()==null)movement.bombs().render(g,size,System.nanoTime());else{movement.bombs().renderExplosions(g,size,System.nanoTime());shadowRenderer.objects(g,movement,size);}for(var p:world.getPlayers().values()){double x=p.getX(),y=p.getY();if(p.getId()==localId&&movement.prediction()!=null){var pos=movement.prediction().position();x=pos.getX();y=pos.getY();}else{var pos=movement.interpolation().sample(p.getId(),movement.renderTick());if(pos!=null){x=pos.x();y=pos.y();}}var stats=world.getMatchFrame()==null?null:world.getMatchFrame().players().stream().filter(s->s.playerId()==p.getId()).findFirst().orElse(null);drawPlayer(g,p.getId(),p.isAlive(),x,y,p.getWidth(),p.getHeight(),stats!=null&&stats.shield());}}finally{g.dispose();}}

    }

    private Color playerColor(int id){
        return MatchPresentation.color(movement.metadata(),id);
    }
    private void drawPlayer(Graphics2D g,int id,boolean alive,double x,double y,double width,double height,boolean shield){Color body=playerColor(id);if(!alive)body=new Color(104,112,128);double bob=Math.sin(System.nanoTime()/120_000_000d+id)*1.2;y+=bob;g.setColor(new Color(0,0,0,80));g.fill(new Ellipse2D.Double(x+2,y+height*.78,width-4,height*.25));if(shield){g.setColor(new Color(88,172,255,50));g.fill(new Ellipse2D.Double(x-5,y-5,width+10,height+10));g.setColor(new Color(105,195,255));g.setStroke(new BasicStroke(2));g.draw(new Ellipse2D.Double(x-4,y-4,width+8,height+8));}g.setColor(body.darker());g.fill(new RoundRectangle2D.Double(x,y+height*.18,width,height*.78,width*.4,width*.4));g.setColor(body);g.fill(new Ellipse2D.Double(x+width*.08,y,width*.84,height*.7));g.setColor(Color.WHITE);g.fill(new Ellipse2D.Double(x+width*.24,y+height*.22,width*.18,height*.19));g.fill(new Ellipse2D.Double(x+width*.58,y+height*.22,width*.18,height*.19));g.setColor(new Color(25,32,47));g.fill(new Ellipse2D.Double(x+width*.3,y+height*.27,width*.08,height*.1));g.fill(new Ellipse2D.Double(x+width*.64,y+height*.27,width*.08,height*.1));g.setFont(UiTheme.BODY.deriveFont(Font.BOLD,10f));String tag=MatchPresentation.tag(movement.metadata(),id);FontMetrics fm=g.getFontMetrics();int tx=(int)(x+(width-fm.stringWidth(tag))/2);g.setColor(new Color(10,15,25,190));g.fillRoundRect(tx-3,(int)y-13,fm.stringWidth(tag)+6,13,6,6);g.setColor(Color.WHITE);g.drawString(tag,tx,(int)y-3);}

    private final class PlayerHudPanel extends JPanel {PlayerHudPanel(){setOpaque(false);} @Override protected void paintComponent(Graphics graphics){Graphics2D g=(Graphics2D)graphics.create();try{g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setColor(new Color(30,41,59,230));g.fillRoundRect(0,0,getWidth(),getHeight(),18,18);g.setColor(new Color(51,65,85));g.drawRoundRect(0,0,getWidth()-1,getHeight()-1,18,18);g.setFont(UiTheme.HEADING);g.setColor(UiTheme.PRIMARY);g.drawString("NGƯỜI CHƠI",14,28);var frame=movement.world().getMatchFrame();if(frame==null)return;var list=frame.players().stream().sorted(java.util.Comparator.comparingInt(com.bombit.common.dto.MatchFrame.Stats::playerId)).toList();int y=44;for(var stats:list){var state=movement.world().getPlayers().get(stats.playerId());boolean alive=state!=null&&state.isAlive();int speed=state==null?0:(int)state.getSpeed();drawSlot(g,stats.playerId(),stats.lives(),stats.bombLimit(),stats.range(),speed,alive,y);y+=76;}g.setColor(UiTheme.MUTED);g.setFont(UiTheme.BODY.deriveFont(Font.BOLD,10f));g.drawString("THỜI GIAN",14,getHeight()-50);g.setColor(frame.remainingSeconds()<=30?UiTheme.DANGER:UiTheme.PRIMARY);g.setFont(UiTheme.TITLE.deriveFont(24f));g.drawString(String.format("%02d:%02d",frame.remainingSeconds()/60,frame.remainingSeconds()%60),14,getHeight()-20);}finally{g.dispose();}}private void drawSlot(Graphics2D g,int id,int lives,int bombs,int range,int speed,boolean alive,int y){Color color=alive?playerColor(id):new Color(100,116,139);g.setColor(new Color(15,23,42));g.fillRoundRect(10,y,160,66,12,12);g.setColor(id==localId?color:new Color(51,65,85));g.setStroke(new BasicStroke(id==localId?2f:1f));g.drawRoundRect(10,y,160,66,12,12);g.setColor(color);g.fillRoundRect(20,y+13,34,34,12,12);g.setColor(Color.WHITE);g.fillOval(28,y+23,7,9);g.fillOval(39,y+23,7,9);g.setColor(new Color(15,23,42));g.fillOval(30,y+26,3,4);g.fillOval(41,y+26,3,4);g.setColor(UiTheme.TEXT);g.setFont(UiTheme.BODY.deriveFont(Font.BOLD,12f));g.drawString(MatchPresentation.tag(movement.metadata(),id)+(id==localId?"  BẠN":""),64,y+20);g.setColor(alive?UiTheme.MUTED:UiTheme.DANGER);g.setFont(UiTheme.BODY.deriveFont(10f));if(alive){g.drawString("♥ "+lives+"   ● "+bombs+"   🔥 "+range,64,y+39);g.drawString("» TỐC ĐỘ  "+speed,64,y+55);}else g.drawString("ĐÃ BỊ LOẠI",64,y+43);}}

    private static final class GradientGamePanel extends JPanel {
        @Override protected void paintComponent(Graphics graphics){Graphics2D g=(Graphics2D)graphics.create();try{g.setPaint(new GradientPaint(0,0,new Color(15,23,42),getWidth(),getHeight(),new Color(30,27,75)));g.fillRect(0,0,getWidth(),getHeight());g.setColor(new Color(56,189,248,12));for(int x=0;x<getWidth();x+=32)g.drawLine(x,0,x,getHeight());for(int y=0;y<getHeight();y+=32)g.drawLine(0,y,getWidth(),y);}finally{g.dispose();}}
    }
}
