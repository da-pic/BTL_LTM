package com.bombit.client.ui.screen;

import com.bombit.client.ui.UiTheme;
import javax.swing.*;
import java.awt.*;

final class ScreenUi {
    private ScreenUi() { }

    static JPanel background(LayoutManager layout) {
        JPanel panel=new GradientPanel(); panel.setLayout(layout); return panel;
    }
    static JLabel title(String text,float size,Color color) {
        JLabel label=new JLabel(text); label.setFont(UiTheme.TITLE.deriveFont(size)); label.setForeground(color); return label;
    }
    static JLabel fieldLabel(String text) {
        JLabel label=new JLabel(text); label.setForeground(UiTheme.MUTED);
        label.setFont(UiTheme.BODY.deriveFont(Font.BOLD,10f)); return label;
    }
    static JPanel field(String label,JTextField input) {
        JPanel panel=UiTheme.panel(new BorderLayout(0,4)); panel.add(fieldLabel(label),BorderLayout.NORTH);
        UiTheme.styleInput(input); panel.add(input); return panel;
    }
    static JPanel header(String heading,String subtitle,JLabel serverInfo) {
        JPanel panel=UiTheme.card(new BorderLayout(16,0));
        panel.setBorder(BorderFactory.createEmptyBorder(14,18,14,18));
        JPanel text=UiTheme.panel(new GridLayout(2,1)); text.add(title(heading,24,UiTheme.TEXT));
        JLabel line=new JLabel(subtitle); line.setForeground(UiTheme.MUTED); text.add(line); panel.add(text);
        serverInfo.setForeground(UiTheme.ACCENT); serverInfo.setHorizontalAlignment(SwingConstants.RIGHT);
        panel.add(serverInfo,BorderLayout.EAST); return panel;
    }
    static JPanel section(String heading,String hint) {
        JPanel panel=UiTheme.panel(new BorderLayout()); panel.add(UiTheme.heading(heading));
        JLabel label=new JLabel(hint); label.setForeground(UiTheme.MUTED); panel.add(label,BorderLayout.EAST); return panel;
    }

    private static final class GradientPanel extends JPanel {
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g=(Graphics2D)graphics.create();
            try {g.setPaint(new GradientPaint(0,0,new Color(8,15,29),getWidth(),getHeight(),new Color(29,25,59)));
                g.fillRect(0,0,getWidth(),getHeight()); g.setColor(new Color(43,181,255,14));
                for(int x=0;x<getWidth();x+=32)g.drawLine(x,0,x,getHeight());
                for(int y=0;y<getHeight();y+=32)g.drawLine(0,y,getWidth(),y);
            } finally {g.dispose();}
        }
    }
}
