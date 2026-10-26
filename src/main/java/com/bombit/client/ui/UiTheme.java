package com.bombit.client.ui;

import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;

/** Shared arcade theme for the Swing client. */
public final class UiTheme {
    public static final Color BACKGROUND = new Color(0x0F,0x17,0x2A);
    public static final Color SURFACE = new Color(30,41,59,220);
    public static final Color SURFACE_LIGHT = new Color(0x33,0x41,0x55);
    public static final Color PRIMARY = new Color(0xF5,0x9E,0x0B);
    public static final Color PRIMARY_DARK = new Color(218, 126, 27);
    public static final Color ACCENT = new Color(0x38,0xBD,0xF8);
    public static final Color DANGER = new Color(0xF4,0x3F,0x5E);
    public static final Color TEXT = new Color(0xF8,0xFA,0xFC);
    public static final Color MUTED = new Color(0x94,0xA3,0xB8);
    public static final Font TITLE = new Font(Font.SANS_SERIF, Font.BOLD, 28);
    public static final Font HEADING = new Font(Font.SANS_SERIF, Font.BOLD, 17);
    public static final Font BODY = new Font(Font.SANS_SERIF, Font.PLAIN, 13);

    private UiTheme() { }

    public static void install() {
        UIManager.put("Panel.background", BACKGROUND);
        UIManager.put("Label.foreground", TEXT);
        UIManager.put("Label.font", BODY);
        UIManager.put("Button.font", BODY.deriveFont(Font.BOLD));
        UIManager.put("TextField.font", BODY);
        UIManager.put("TextArea.font", BODY);
        UIManager.put("List.font", BODY);
        UIManager.put("Table.font", BODY);
        UIManager.put("TableHeader.font", BODY.deriveFont(Font.BOLD));
        UIManager.put("TabbedPane.font", BODY.deriveFont(Font.BOLD));
        UIManager.put("ToolTip.background", SURFACE_LIGHT);
        UIManager.put("ToolTip.foreground", TEXT);
    }

    public static JPanel panel(LayoutManager layout) {
        JPanel panel = new JPanel(layout);
        panel.setOpaque(false);
        return panel;
    }

    public static JPanel card(LayoutManager layout) {
        return new RoundedPanel(layout, SURFACE, 20);
    }

    public static void styleButton(AbstractButton button, boolean primary) {
        button.setForeground(primary ? new Color(41, 28, 8) : TEXT);
        button.setBackground(primary ? PRIMARY : SURFACE_LIGHT);
        button.setFocusPainted(false);
        button.setBorder(new CompoundBorder(new LineBorder(primary ? PRIMARY_DARK : new Color(62, 78, 108), 1, true),
                new EmptyBorder(8, 15, 8, 15)));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }

    public static JButton arcadeButton(String text, boolean primary) {
        return new ArcadeButton(text, primary ? ACCENT : new Color(100,116,139));
    }

    public static JButton arcadeButton(String text, Color color) {
        return new ArcadeButton(text, color);
    }

    public static void styleInput(JComponent component) {
        component.setForeground(TEXT);
        component.setBackground(new Color(17, 24, 39));
        component.setBorder(new CompoundBorder(new LineBorder(new Color(61, 78, 108), 1, true),
                new EmptyBorder(7, 9, 7, 9)));
    }

    public static JScrollPane scroll(Component component) {
        JScrollPane pane = new JScrollPane(component);
        pane.setBorder(new LineBorder(new Color(54, 69, 96), 1, true));
        pane.getViewport().setBackground(new Color(17, 24, 39));
        return pane;
    }

    public static JLabel heading(String text) {
        JLabel label = new JLabel(text);
        label.setFont(HEADING);
        return label;
    }

    public static final class RoundedPanel extends JPanel {
        private final Color fill;
        private final int radius;
        public RoundedPanel(LayoutManager layout, Color fill, int radius) {
            super(layout); this.fill=fill; this.radius=radius; setOpaque(false);
        }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g=(Graphics2D)graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(fill);g.fillRoundRect(0,0,getWidth(),getHeight(),radius,radius);
                g.setColor(new Color(68,91,130,150));g.setStroke(new BasicStroke(1.2f));
                g.drawRoundRect(1,1,Math.max(0,getWidth()-3),Math.max(0,getHeight()-3),radius,radius);
            } finally { g.dispose(); }
            super.paintComponent(graphics);
        }
    }

    private static final class ArcadeButton extends JButton {
        private final Color base;
        ArcadeButton(String text,Color base){super(text);this.base=base;setForeground(new Color(15,23,42));setFont(BODY.deriveFont(Font.BOLD));setFocusPainted(false);setContentAreaFilled(false);setBorderPainted(false);setOpaque(false);setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));setBorder(new EmptyBorder(10,18,10,18));}
        @Override protected void paintComponent(Graphics graphics){Graphics2D g=(Graphics2D)graphics.create();try{g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);Color fill=!isEnabled()?new Color(71,85,105):getModel().isPressed()?base.darker():getModel().isRollover()?base.brighter():base;g.setColor(fill.darker().darker());g.fillRoundRect(0,3,getWidth(),Math.max(0,getHeight()-3),12,12);int offset=getModel().isPressed()?2:0;g.setColor(fill);g.fillRoundRect(0,offset,getWidth(),Math.max(0,getHeight()-3),12,12);}finally{g.dispose();}super.paintComponent(graphics);}
    }
}
