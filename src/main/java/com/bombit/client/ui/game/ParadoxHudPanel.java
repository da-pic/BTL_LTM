package com.bombit.client.ui.game;

import com.bombit.client.game.movement.MovementClientController;
import com.bombit.client.ui.UiTheme;
import javax.swing.*;
import java.awt.*;

/** HUD chỉ đọc state authoritative; không tự trừ lượt hoặc mở input khi nhấn R. */
public final class ParadoxHudPanel extends JPanel {
    public static final String RULES="<html><b>PARADOX — Đấu cá nhân 2–4 người</b><br>"
            +"Mũi tên: di chuyển · SPACE: đặt bom · R: rewind · F1: hướng dẫn.<br>"
            +"Mỗi người có 1 lượt đảo toàn bộ bản đồ về 40 tick trước (2 giây ở 20 Hz).<br>"
            +"Bom/fuse, block, item, mạng sống và chỉ số cùng quay lại; lượt rewind không được hoàn.<br>"
            +"Cần đủ 40 tick lịch sử. Chỉ người còn sống, không mất kết nối mới được kích hoạt.<br>"
            +"Toàn trận phải chờ 3 giây giữa hai lần rewind. Yêu cầu bị từ chối không tiêu lượt.<br>"
            +"Đồng hồ trận không quay lại; trận đã kết thúc không thể rewind.<br>"
            +"Khi đổi timeline, chờ đồng bộ xong và nhấn lại phím di chuyển.<br>"
            +"Người sống sót cuối cùng thắng; hết giờ nếu nhiều người còn sống thì hòa.</html>";
    private final JLabel timeline=new JLabel("PARADOX · Đang đồng bộ..."),charge=new JLabel("R · Lượt: —"),
            cooldown=new JLabel("Hồi toàn trận: —"),message=new JLabel("R: đảo toàn bản đồ 2 giây · F1: luật chơi");
    private long epoch=-1,changedAt;
    public ParadoxHudPanel(){
        super(new GridLayout(2,1,0,5));setOpaque(false);setBorder(BorderFactory.createEmptyBorder(2,4,4,4));
        var top=new JPanel(new FlowLayout(FlowLayout.LEFT,18,0));top.setOpaque(false);
        for(var label:new JLabel[]{timeline,charge,cooldown}){label.setForeground(UiTheme.PRIMARY);label.setFont(UiTheme.BODY.deriveFont(Font.BOLD,12f));top.add(label);}
        add(top);message.setForeground(UiTheme.MUTED);message.setFont(UiTheme.BODY.deriveFont(12f));add(message);
    }
    public void update(MovementClientController movement,int localId){
        if(movement.metadata()==null)return;
        long current=movement.metadata().stream().timelineEpoch();
        if(current!=epoch){if(epoch>=0)changedAt=System.nanoTime();epoch=current;}
        var frame=movement.paradox();
        timeline.setText("PARADOX · Timeline "+current+(frame==null?"":" · Tick mô phỏng "+frame.simulationTick()));
        charge.setText("R · Lượt: "+(frame==null?"—":frame.availableCharges().contains(localId)?"1 / 1":"ĐÃ DÙNG"));
        cooldown.setText(frame==null?"Hồi toàn trận: —":frame.cooldownRemainingMillis()==0?"Hồi toàn trận: SẴN SÀNG":
                String.format(java.util.Locale.ROOT,"Hồi toàn trận: %.1f giây",frame.cooldownRemainingMillis()/1000.0));
        if(!movement.isInputReady())message.setText(movement.isRecovering()?"Đang đồng bộ timeline — điều khiển đang khóa":"Bạn đã bị loại — theo dõi trận đấu");
        else if(changedAt!=0&&System.nanoTime()-changedAt<2_000_000_000L)message.setText("Đã chuyển sang timeline "+current+" — nhấn lại phím để tiếp tục");
        else message.setText(movement.rewindFeedback().isEmpty()?"R: đảo toàn bản đồ 2 giây · Mỗi người 1 lượt · F1: luật chơi":movement.rewindFeedback());
    }
    public String timelineText(){return timeline.getText();}
    public String chargeText(){return charge.getText();}
    public String cooldownText(){return cooldown.getText();}
    public String messageText(){return message.getText();}
}
