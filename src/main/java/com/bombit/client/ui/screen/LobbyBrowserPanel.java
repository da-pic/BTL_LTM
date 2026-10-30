package com.bombit.client.ui.screen;

import com.bombit.client.ui.UiTheme;
import com.bombit.client.ui.component.RoomTableStyle;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

public final class LobbyBrowserPanel extends JPanel {
    public LobbyBrowserPanel(JTextField host,JTextField port,JTextField roomName,JSpinner capacity,JTable roomTable,
                             JButton refresh,JButton create,JButton join,JLabel status,JLabel serverInfo,JLabel connectionDetails) {
        this(host,port,roomName,capacity,roomTable,refresh,create,join,status,serverInfo,connectionDetails,null);
    }
    public LobbyBrowserPanel(JTextField host,JTextField port,JTextField roomName,JSpinner capacity,JTable roomTable,
                             JButton refresh,JButton create,JButton join,JLabel status,JLabel serverInfo,JLabel connectionDetails,JComponent modes){
        super(new BorderLayout(0,14));setOpaque(false);setBorder(new EmptyBorder(12,20,14,20));
        JPanel north=UiTheme.panel(new BorderLayout(0,10));north.add(ScreenUi.header("BOMBER ARCADE","Sảnh chính • Danh sách phòng • Phòng chờ trực tuyến",serverInfo),BorderLayout.NORTH);
        north.add(connectionStrip(host,port,connectionDetails),BorderLayout.SOUTH);add(north,BorderLayout.NORTH);
        JPanel listCard=UiTheme.card(new BorderLayout(0,14));listCard.setBorder(new EmptyBorder(18,18,18,18));
        JPanel listHead=UiTheme.panel(new BorderLayout(12,0)),headingBlock=UiTheme.panel(new GridLayout(2,1,0,3));JLabel heading=UiTheme.heading("DANH SÁCH PHÒNG ĐẤU");heading.setForeground(UiTheme.ACCENT);headingBlock.add(heading);
        JLabel subheading=new JLabel("Chọn một phòng đang chờ hoặc tạo phòng mới của bạn");subheading.setForeground(UiTheme.MUTED);headingBlock.add(subheading);listHead.add(headingBlock);
        JLabel tip=new JLabel("↯  NHẤP ĐÚP ĐỂ VÀO NHANH");tip.setForeground(UiTheme.PRIMARY);tip.setFont(UiTheme.BODY.deriveFont(Font.BOLD,11f));listHead.add(tip,BorderLayout.EAST);
        JPanel headingGroup=UiTheme.panel(new BorderLayout(0,12));headingGroup.add(listHead,BorderLayout.NORTH);if(modes!=null)headingGroup.add(modes,BorderLayout.SOUTH);listCard.add(headingGroup,BorderLayout.NORTH);
        RoomTableStyle.apply(roomTable);roomTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);JScrollPane scroll=UiTheme.scroll(roomTable);scroll.setBorder(BorderFactory.createEmptyBorder());scroll.setViewportBorder(null);scroll.getViewport().setBorder(null);scroll.getViewport().setBackground(new Color(8,15,28));listCard.add(scroll);
        JPanel bottom=new UiTheme.RoundedPanel(new BorderLayout(16,0),new Color(12,21,37),14);bottom.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(42,63,88)),new EmptyBorder(11,14,11,14)));
        JPanel form=UiTheme.panel(new FlowLayout(FlowLayout.LEFT,9,0));JLabel formTitle=new JLabel("TẠO PHÒNG NHANH");formTitle.setForeground(UiTheme.ACCENT);formTitle.setFont(UiTheme.BODY.deriveFont(Font.BOLD,11f));UiTheme.styleInput(roomName);roomName.setPreferredSize(new Dimension(210,38));RoomTableStyle.styleSpinner(capacity);capacity.setPreferredSize(new Dimension(66,38));JLabel max=new JLabel("Tối đa");max.setForeground(UiTheme.MUTED);form.add(formTitle);form.add(roomName);form.add(max);form.add(capacity);bottom.add(form,BorderLayout.WEST);
        JPanel actions=UiTheme.panel(new FlowLayout(FlowLayout.RIGHT,9,0));actions.add(refresh);actions.add(create);actions.add(join);bottom.add(actions);listCard.add(bottom,BorderLayout.SOUTH);add(listCard);
        JPanel footer=UiTheme.card(new BorderLayout());footer.setBorder(new EmptyBorder(8,12,8,12));JLabel guide=new JLabel("Tạo phòng mới hoặc chọn một phòng đang chờ để tham gia");guide.setForeground(UiTheme.MUTED);footer.add(guide);status.setForeground(UiTheme.ACCENT);footer.add(status,BorderLayout.EAST);add(footer,BorderLayout.SOUTH);
    }
    private static JPanel connectionStrip(JTextField host,JTextField port,JLabel details){JPanel strip=UiTheme.card(new BorderLayout(12,0));strip.setBorder(new EmptyBorder(12,16,12,16));JPanel labels=UiTheme.panel(new GridLayout(2,1));JLabel cap=ScreenUi.fieldLabel("CẤU HÌNH KẾT NỐI & ĐỊNH DANH NHÂN VẬT");cap.setForeground(new Color(57,190,255));labels.add(cap);details.setText("Host: "+host.getText()+"     •     Port: "+port.getText()+"     •     Tên nhân vật: —");details.setForeground(UiTheme.TEXT);details.setFont(UiTheme.BODY.deriveFont(Font.BOLD));labels.add(details);strip.add(labels);JLabel online=new JLabel("●  ĐÃ KẾT NỐI MÁY CHỦ");online.setForeground(new Color(44,225,164));online.setFont(UiTheme.BODY.deriveFont(Font.BOLD));strip.add(online,BorderLayout.EAST);return strip;}
}
