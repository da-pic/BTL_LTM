package com.bombit.client.ui.game;

import com.bombit.client.game.movement.MovementClientController;
import com.bombit.common.dto.*;
import com.bombit.client.ui.UiTheme;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.Locale;

/** HUD chỉ đọc replica đã lọc; đối thủ ẩn không được gán stats giả bằng 0. */
public final class ShadowHudPanel extends JPanel {
    public static final String RULES="<html><b>SHADOW RELAY — 2 đội, mỗi đội 2 người</b><br><br>"+
            "Mũi tên: di chuyển · Space: bom thường · F: bom quét.<br>"+
            "Đứng gần trạm một mình theo đội trong 40 tick để chiếm.<br>"+
            "Trạm có chủ sinh 1 điểm mỗi 20 tick; đối phương vào thì tạm dừng điểm.<br>"+
            "Đạt 60 điểm hoặc hết 3 phút: so điểm; loại hết đội đối phương thì thắng.<br><br>"+
            "Tầm nhìn được chia sẻ trong đội. Scan xuyên block nhưng không xuyên tường cứng.<br>"+
            "Scan chờ 1,5s, quét 3s, hồi 8s; dùng chung quota với bom thường.<br>"+
            "Có tự sát thương và sát thương đồng đội. Mất mạng không được miễn damage.<br>"+
            "Bom sát thương địch trong sương mù bị ẩn; vụ nổ vẫn hiện cho cả hai đội.<br>"+
            "Bom đồng đội, trạm, item và beacon scan vẫn nhìn thấy. Người bị loại quan sát theo đội.</html>";
    private final JLabel heading=label("SHADOW RELAY",17),score=label("A 0 : 0 B",22),time=label("03:00",22),scan=label("F · Chờ đồng bộ",12),quota=label("",12),feedback=label("",11);
    private final JLabel[] players=new JLabel[4];
    public ShadowHudPanel(){
        setLayout(new BoxLayout(this,BoxLayout.Y_AXIS));setBackground(new Color(15,23,42));setBorder(new EmptyBorder(12,12,12,12));setPreferredSize(new Dimension(232,480));
        for(var c:new JLabel[]{heading,score,time,scan,quota,feedback}){add(c);add(Box.createVerticalStrut(6));}
        for(int i=0;i<4;i++){players[i]=label("Đang chờ...",11);add(players[i]);add(Box.createVerticalStrut(9));}
        add(Box.createVerticalGlue());add(label("Chiếm trạm 2s · 60 điểm thắng",11));add(label("Bom/scan chung quota · Có friendly fire",10));
    }
    private static JLabel label(String text,int size){var l=new JLabel(text);l.setForeground(UiTheme.TEXT);l.setFont(UiTheme.BODY.deriveFont(Font.BOLD,(float)size));return l;}
    public static Color teamColor(TeamId team){return team==TeamId.A?new Color(56,189,248):new Color(251,113,133);}
    public void update(MovementClientController controller,int localId){
        var replica=controller.shadow();var metadata=controller.metadata();if(replica==null||metadata==null)return;
        var own=metadata.players().stream().filter(p->p.playerId()==localId).findFirst().orElseThrow();
        heading.setText("SHADOW · ĐỘI "+own.team());heading.setForeground(teamColor(own.team()));
        var frame=replica.objectives();if(frame!=null){score.setText("A "+frame.scoreA()+" : "+frame.scoreB()+" B");time.setText(String.format("%02d:%02d",frame.remainingSeconds()/60,frame.remainingSeconds()%60));}
        long remaining=Math.max(0,replica.scanReadyAtMillis()-controller.estimatedMatchMillis());
        scan.setText(!controller.isInputReady()?"F · Điều khiển đang khóa":remaining==0?"F · Scan sẵn sàng":"F · Scan hồi "+String.format(Locale.ROOT,"%.1f",remaining/1000d)+"s");
        var local=metadata.players().stream().filter(p->p.playerId()==localId).findFirst().orElseThrow();
        var stats=controller.world().getMatchFrame();
        var ownStats=stats==null?null:stats.players().stream().filter(p->p.playerId()==localId).findFirst().orElse(null);
        long used=controller.world().getEntities().values().stream().filter(e->e.ownerId().equals(local.sessionId())&&(e.kind()==com.bombit.server.game.movement.EntityData.Kind.BOMB||e.kind()==com.bombit.server.game.movement.EntityData.Kind.SCAN_PENDING)).count();
        quota.setText("Quota: "+used+" / "+(ownStats==null?"—":ownStats.bombLimit()));feedback.setText(controller.plantFeedback());
        for(var member:metadata.players()){
            var publicState=replica.publicPlayers().stream().filter(p->p.playerId()==member.playerId()).findFirst().orElse(null);
            var detail=stats==null?null:stats.players().stream().filter(p->p.playerId()==member.playerId()).findFirst().orElse(null);
            String name=member.username().length()>15?member.username().substring(0,15)+"…":member.username();
            String state=publicState==null?"Chờ đồng bộ":publicState.lifeState()==2?"Đã rời trận":publicState.lifeState()==1?"Đã bị loại":publicState.connectionState()==1?"Đang mất mạng":"Còn sống";
            String values=detail==null?"Chỉ số chưa quan sát":"Mạng "+detail.lives()+" · Bom "+detail.bombLimit()+" · Tầm "+detail.range();
            var row=players[member.slot()-1];row.setText("<html>"+member.team()+" · "+escape(name)+(member.playerId()==localId?" (Bạn)":"")+"<br>"+state+"<br>"+values+"</html>");row.setForeground(teamColor(member.team()));
        }
    }
    private static String escape(String text){return text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");}
    public String playerText(int slot){return players[slot-1].getText();}
    public String scanText(){return scan.getText();}
    public String scoreText(){return score.getText();}
}
