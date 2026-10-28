package com.bombit.client.ui.component;

import com.bombit.client.ui.UiTheme;
import javax.swing.*;
import java.awt.*;

public final class PlayerSlotPanel extends JPanel {
    private final String username,role; private final boolean ready; private final Color color;
    public PlayerSlotPanel(String username,String role,boolean ready,Color color){this.username=username;this.role=role;this.ready=ready;this.color=color;setOpaque(false);}
    @Override protected void paintComponent(Graphics graphics){Graphics2D g=(Graphics2D)graphics.create();try{
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setColor(new Color(15,23,42,220));g.fillRoundRect(0,0,getWidth(),getHeight(),14,14);
        g.setColor(ready?new Color(0x22,0xC5,0x5E):new Color(51,65,85));g.setStroke(new BasicStroke(1.5f));g.drawRoundRect(1,1,getWidth()-3,getHeight()-3,14,14);
        int s=Math.min(46,getHeight()-14),x=12,y=(getHeight()-s)/2;g.setColor(new Color(0,0,0,100));g.fillOval(x+6,y+s-8,s-12,7);
        g.setColor(color.darker());g.fillRoundRect(x+5,y+8,s-10,s-10,16,16);g.setColor(color);g.fillRoundRect(x+5,y+5,s-10,s-13,16,16);
        g.setColor(new Color(255,255,255,95));g.fillRoundRect(x+10,y+8,s-20,10,8,8);g.setColor(Color.WHITE);g.fillOval(x+12,y+18,8,10);g.fillOval(x+s-20,y+18,8,10);
        g.setColor(new Color(15,23,42));g.fillOval(x+15,y+21,4,5);g.fillOval(x+s-17,y+21,4,5);int tx=x+s+12;
        g.setColor(UiTheme.TEXT);g.setFont(UiTheme.BODY.deriveFont(Font.BOLD,13f));g.drawString(username,tx,getHeight()/2-3);
        g.setColor(ready?new Color(0x22,0xC5,0x5E):UiTheme.MUTED);g.setFont(UiTheme.BODY.deriveFont(11f));g.drawString(role+" • "+(ready?"Sẵn sàng":"Chưa sẵn sàng"),tx,getHeight()/2+15);
    }finally{g.dispose();}}
}
