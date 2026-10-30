package com.bombit.client.ui.screen;

import com.bombit.client.ui.UiTheme;
import com.bombit.client.ui.component.MapSelectionPicker;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

public final class WaitingRoomPanel extends JPanel {
    public WaitingRoomPanel(JLabel serverInfo,JLabel roomTitle,JLabel roomMeta,MapSelectionPicker mapSelection,JButton leave,JPanel playerCards,JTextArea chat,JTextField message,JButton send,JButton end,JButton ready,JButton start){
        this(serverInfo,roomTitle,roomMeta,mapSelection,leave,playerCards,chat,message,send,end,ready,start,null);
    }
    public WaitingRoomPanel(JLabel serverInfo,JLabel roomTitle,JLabel roomMeta,MapSelectionPicker mapSelection,JButton leave,JPanel playerCards,JTextArea chat,JTextField message,JButton send,JButton end,JButton ready,JButton start,JComponent modes){
        super(new BorderLayout(0,14));setOpaque(false);setBorder(new EmptyBorder(16,20,16,20));add(ScreenUi.header("PHÒNG CHỜ BOMBER ARCADE","Chuẩn bị đội hình và trò chuyện trước trận đấu",serverInfo),BorderLayout.NORTH);
        JPanel card=UiTheme.card(new BorderLayout(0,10));card.setBorder(new EmptyBorder(16,16,16,16));JPanel top=UiTheme.panel(new BorderLayout(18,0));JPanel info=UiTheme.panel(new GridLayout(2,1));roomTitle.setFont(UiTheme.HEADING);roomTitle.setForeground(UiTheme.PRIMARY);roomMeta.setForeground(UiTheme.MUTED);info.add(roomTitle);info.add(roomMeta);top.add(info);JPanel mapBox=UiTheme.panel(new BorderLayout(10,0));JPanel pickerBox=UiTheme.panel(new BorderLayout(0,8));JLabel mapLabel=new JLabel("CHỌN BẢN ĐỒ");mapLabel.setBorder(new EmptyBorder(0,4,0,0));mapLabel.setForeground(UiTheme.ACCENT);mapLabel.setFont(UiTheme.BODY.deriveFont(Font.BOLD,10f));pickerBox.add(mapLabel,BorderLayout.NORTH);pickerBox.add(mapSelection);mapBox.add(pickerBox);mapBox.add(leave,BorderLayout.EAST);top.add(mapBox,BorderLayout.EAST);card.add(top,BorderLayout.NORTH);
        JPanel body=UiTheme.panel(new GridLayout(2,1,0,10));JPanel roster=UiTheme.panel(new BorderLayout(0,8));roster.add(ScreenUi.section("ĐỘI HÌNH TRONG PHÒNG","4 vị trí"),BorderLayout.NORTH);roster.add(playerCards);body.add(roster);
        JPanel chatPanel=UiTheme.panel(new BorderLayout(0,8));chatPanel.add(ScreenUi.section("KÊNH CHAT PHÒNG CHỜ","Thời gian thực"),BorderLayout.NORTH);chat.setEditable(false);chat.setLineWrap(true);chat.setWrapStyleWord(true);chat.setBackground(new Color(10,16,30));chat.setForeground(UiTheme.TEXT);chat.setBorder(new EmptyBorder(10,10,10,10));chatPanel.add(UiTheme.scroll(chat));UiTheme.styleInput(message);JPanel compose=UiTheme.panel(new BorderLayout(8,0));compose.add(message);compose.add(send,BorderLayout.EAST);chatPanel.add(compose,BorderLayout.SOUTH);body.add(chatPanel);card.add(body);
        JPanel actions=UiTheme.panel(new FlowLayout(FlowLayout.RIGHT,8,0));actions.add(end);actions.add(ready);actions.add(start);
        JPanel bottom=UiTheme.panel(new BorderLayout(0,8));if(modes!=null)bottom.add(modes);bottom.add(actions,BorderLayout.SOUTH);card.add(bottom,BorderLayout.SOUTH);add(card);
    }
}
