package com.bombit.client.ui.component;

import com.bombit.client.ui.UiTheme;
import com.bombit.common.dto.MapSelection;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ActionListener;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/** Compact arcade-style map selector; avoids the platform-dependent JComboBox appearance. */
public final class MapSelectionPicker extends JPanel {
    private static final Color PANEL = new Color(10,17,31,225);
    private final Map<MapSelection,MapButton> buttons=new EnumMap<>(MapSelection.class);
    private final ButtonGroup group=new ButtonGroup();
    private Consumer<MapSelection> listener=selection->{};
    private boolean updating;

    public MapSelectionPicker(){
        super(new FlowLayout(FlowLayout.LEFT,5,5));
        setOpaque(false);setBorder(new EmptyBorder(1,2,1,2));
        for(MapSelection selection:MapSelection.values()){
            MapButton button=new MapButton(selection,color(selection));
            button.setToolTipText(description(selection));
            button.addActionListener(e->{if(!updating)listener.accept(selection);});
            buttons.put(selection,button);group.add(button);add(button);
        }
        setSelection(MapSelection.RANDOM);
    }

    public void setSelection(MapSelection selection){
        updating=true;
        try{buttons.get(Objects.requireNonNull(selection)).setSelected(true);}
        finally{updating=false;}
    }

    public MapSelection getSelection(){
        return buttons.entrySet().stream().filter(e->e.getValue().isSelected()).map(Map.Entry::getKey).findFirst().orElse(MapSelection.RANDOM);
    }

    public void onSelectionChanged(Consumer<MapSelection> listener){this.listener=Objects.requireNonNull(listener);}

    @Override public void setEnabled(boolean enabled){
        super.setEnabled(enabled);buttons.values().forEach(button->button.setEnabled(enabled));repaint();
    }

    @Override protected void paintComponent(Graphics graphics){
        Graphics2D g=(Graphics2D)graphics.create();
        try{g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setColor(PANEL);g.fillRoundRect(0,0,getWidth(),getHeight(),16,16);g.setColor(new Color(52,75,112));g.drawRoundRect(0,0,getWidth()-1,getHeight()-1,16,16);}finally{g.dispose();}
        super.paintComponent(graphics);
    }

    private static Color color(MapSelection selection){return switch(selection){
        case RANDOM->new Color(0xA8,0x55,0xF7);case CLASSIC->new Color(0xF5,0x9E,0x0B);case FOREST->new Color(0x22,0xC5,0x5E);
        case OCEAN->new Color(0x38,0xBD,0xF8);case ICE->new Color(0xA5,0xF3,0xFC);case FIRE->new Color(0xF4,0x3F,0x5E);};}
    private static String description(MapSelection selection){return switch(selection){
        case RANDOM->"Máy chủ chọn ngẫu nhiên khi bắt đầu";case CLASSIC->"Sân đấu cổ điển";case FOREST->"Rừng cây cổ thụ";
        case OCEAN->"Đại dương và san hô";case ICE->"Băng tuyết";case FIRE->"Núi lửa và nham thạch";};}

    private static final class MapButton extends JToggleButton {
        private final Color accent;
        MapButton(MapSelection selection,Color accent){super(shortLabel(selection));this.accent=accent;setFont(UiTheme.BODY.deriveFont(Font.BOLD,11f));setForeground(UiTheme.MUTED);setFocusPainted(false);setContentAreaFilled(false);setBorderPainted(false);setOpaque(false);setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));setBorder(new EmptyBorder(8,28,8,13));}
        @Override protected void paintComponent(Graphics graphics){Graphics2D g=(Graphics2D)graphics.create();try{g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);boolean active=isSelected(),hover=getModel().isRollover();Color fill=active?new Color(accent.getRed(),accent.getGreen(),accent.getBlue(),45):hover?new Color(51,65,85):new Color(20,29,48);g.setColor(fill);g.fillRoundRect(0,0,getWidth(),getHeight(),12,12);g.setColor(active?accent:new Color(55,70,98));g.setStroke(new BasicStroke(active?1.8f:1f));g.drawRoundRect(1,1,getWidth()-3,getHeight()-3,12,12);g.setColor(isEnabled()?(active?UiTheme.TEXT:UiTheme.MUTED):new Color(100,116,139));g.fillOval(9,getHeight()/2-4,8,8);}finally{g.dispose();}setHorizontalAlignment(SwingConstants.RIGHT);super.paintComponent(graphics);}
        private static String shortLabel(MapSelection selection){return switch(selection){case RANDOM->"NGẪU NHIÊN";case CLASSIC->"CỔ ĐIỂN";case FOREST->"RỪNG";case OCEAN->"BIỂN";case ICE->"BĂNG";case FIRE->"LỬA";};}
    }
}
