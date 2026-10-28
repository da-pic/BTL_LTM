package com.bombit.client.ui.screen;

import com.bombit.client.ui.UiTheme;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

public final class ResultsScreenPanel extends JPanel {
    public ResultsScreenPanel(MatchResultsPanel results,Runnable lobbyAction,Runnable roomAction){
        super(new GridBagLayout());setOpaque(false);
        JPanel card=UiTheme.card(new BorderLayout(0,16));card.setBorder(new EmptyBorder(18,20,18,20));card.setPreferredSize(new Dimension(780,570));
        JPanel heading=UiTheme.panel(new BorderLayout());JPanel labels=UiTheme.panel(new GridLayout(2,1,0,3));JLabel overline=new JLabel("KẾT THÚC TRẬN ĐẤU");overline.setForeground(new Color(251,191,36));overline.setFont(UiTheme.BODY.deriveFont(Font.BOLD,11f));labels.add(overline);JLabel title=ScreenUi.title("BẢNG XẾP HẠNG CHUNG CUỘC",25,Color.WHITE);labels.add(title);heading.add(labels);JLabel server=new JLabel("KẾT QUẢ TỪ MÁY CHỦ  ●");server.setForeground(new Color(52,211,153));server.setFont(UiTheme.BODY.deriveFont(Font.BOLD,11f));heading.add(server,BorderLayout.EAST);card.add(heading,BorderLayout.NORTH);
        card.add(results);
        JButton lobby=UiTheme.arcadeButton("⌂  VỀ SẢNH CHÍNH",new Color(100,116,139));lobby.addActionListener(e->lobbyAction.run());JButton replay=UiTheme.arcadeButton("↻  CHƠI LẠI / VỀ PHÒNG CHỜ",new Color(0x22,0xC5,0x5E));replay.addActionListener(e->roomAction.run());
        JPanel bottom=UiTheme.panel(new BorderLayout());bottom.setBorder(new EmptyBorder(0,0,0,0));bottom.add(lobby,BorderLayout.WEST);bottom.add(replay,BorderLayout.EAST);card.add(bottom,BorderLayout.SOUTH);add(card);
    }
}
