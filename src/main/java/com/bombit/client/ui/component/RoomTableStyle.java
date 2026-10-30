package com.bombit.client.ui.component;

import com.bombit.client.ui.UiTheme;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;

public final class RoomTableStyle {
    private RoomTableStyle() { }
    public static void apply(JTable table) {
        table.setRowHeight(56); table.setShowGrid(false); table.setIntercellSpacing(new Dimension(0,0));
        table.setBackground(new Color(8,15,28)); table.setForeground(UiTheme.TEXT);
        table.setSelectionBackground(new Color(18,68,96)); table.setSelectionForeground(Color.WHITE);
        table.setFillsViewportHeight(true); table.setFont(UiTheme.BODY.deriveFont(12f)); table.setBorder(null); table.setFocusable(false);
        table.getTableHeader().setBorder(null); table.getTableHeader().setPreferredSize(new Dimension(0,44));
        table.getTableHeader().setBackground(new Color(20,33,52)); table.getTableHeader().setReorderingAllowed(false);
        table.getTableHeader().setResizingAllowed(false); table.getTableHeader().setDefaultRenderer(new HeaderRenderer());
        int[] widths={100,235,170,90,120,120,150}; for(int i=0;i<Math.min(widths.length,table.getColumnCount());i++)table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        table.setDefaultRenderer(Object.class,new CellRenderer());
    }
    public static void styleSpinner(JSpinner spinner) {
        spinner.setBorder(BorderFactory.createLineBorder(new Color(51,65,85))); spinner.setBackground(new Color(15,23,42));
        if(spinner.getEditor() instanceof JSpinner.DefaultEditor editor){JTextField field=editor.getTextField();
            field.setBackground(new Color(15,23,42)); field.setForeground(UiTheme.TEXT); field.setCaretColor(Color.WHITE);
            field.setHorizontalAlignment(SwingConstants.CENTER); field.setBorder(new EmptyBorder(6,8,6,8)); field.setFont(UiTheme.BODY.deriveFont(Font.BOLD));}
    }
    private static final class HeaderRenderer extends DefaultTableCellRenderer {
        @Override public Component getTableCellRendererComponent(JTable table,Object value,boolean selected,boolean focus,int row,int column){
            super.getTableCellRendererComponent(table,value,selected,focus,row,column); setOpaque(true); setBackground(new Color(20,33,52));
            setForeground(new Color(125,211,252)); setHorizontalAlignment(SwingConstants.CENTER); setFont(UiTheme.BODY.deriveFont(Font.BOLD,11f));
            setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0,0,2,0,new Color(34,211,238)),new EmptyBorder(0,10,0,10))); return this;}
    }
    private static final class CellRenderer extends DefaultTableCellRenderer {
        @Override public Component getTableCellRendererComponent(JTable table,Object value,boolean selected,boolean focus,int row,int column){
            super.getTableCellRendererComponent(table,value,selected,focus,row,column); String original=String.valueOf(value);
            setText(column==5?"●  "+original:original); setHorizontalAlignment(SwingConstants.CENTER);
            setBackground(selected?new Color(18,68,96):new Color(13,24,41));
            setForeground(column==5?("Đang chờ".equals(original)?new Color(52,211,153):new Color(251,191,36)):column==4?UiTheme.ACCENT:UiTheme.TEXT);
            setFont(UiTheme.BODY.deriveFont(column>=4?Font.BOLD:Font.PLAIN,12f));
            setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0,0,8,0,new Color(8,15,28)),new EmptyBorder(0,14,0,14))); return this;}
    }
}
