package com.bombit.client.ui.screen;

import com.bombit.client.ui.UiTheme;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

public final class LoginScreenPanel extends JPanel {
    public LoginScreenPanel(JTextField host,JTextField port,JTextField name,JButton connect,JLabel status) {
        super(new BorderLayout()); setOpaque(false);
        JPanel background=ScreenUi.background(new GridBagLayout());
        JPanel card=UiTheme.card(new GridBagLayout()); card.setBorder(new EmptyBorder(34,42,34,42)); card.setPreferredSize(new Dimension(480,440));
        GridBagConstraints c=new GridBagConstraints();c.gridx=0;c.fill=GridBagConstraints.HORIZONTAL;c.weightx=1;c.insets=new Insets(5,0,5,0);
        c.gridy=0;card.add(ScreenUi.title("BOMB IT",40,UiTheme.PRIMARY),c);
        JLabel sub=new JLabel("ONLINE BATTLE ARENA",SwingConstants.CENTER);sub.setForeground(UiTheme.ACCENT);sub.setFont(UiTheme.BODY.deriveFont(Font.BOLD));
        c.gridy=1;c.insets=new Insets(0,0,28,0);card.add(sub,c);c.insets=new Insets(5,0,4,0);c.gridy=2;card.add(ScreenUi.fieldLabel("TÊN NGƯỜI CHƠI"),c);
        UiTheme.styleInput(name);name.setFont(UiTheme.HEADING);c.gridy=3;card.add(name,c);
        JPanel address=UiTheme.panel(new GridLayout(1,2,10,0));address.add(ScreenUi.field("MÁY CHỦ",host));address.add(ScreenUi.field("CỔNG",port));
        c.gridy=4;c.insets=new Insets(14,0,16,0);card.add(address,c);connect.setPreferredSize(new Dimension(220,46));c.gridy=5;c.insets=new Insets(0,0,10,0);card.add(connect,c);
        status.setForeground(UiTheme.MUTED);c.gridy=6;card.add(status,c);background.add(card);add(background,BorderLayout.CENTER);
    }
}
