package com.bombit.client.ui.component;

import com.bombit.common.dto.*;
import com.bombit.client.ui.UiTheme;
import java.awt.*;
import java.util.*;
import java.util.function.Consumer;
import javax.swing.*;

/** Selector theo availability server; mode chưa làm không thể chọn rồi chạy Classic giả. */
public final class ModeSelectionPanel extends JPanel {
    private final JComboBox<GameMode> choice=new JComboBox<>();
    private final JComboBox<MatchFormat> formatChoice=new JComboBox<>(MatchFormat.values());
    private final JLabel details=new JLabel("Đang tải chế độ chơi...");
    private java.util.List<ModeDescriptor> modes=java.util.List.of();
    private final Consumer<GameMode> changed;
    private final Consumer<MatchFormat> formatChanged;
    private GameMode selected=GameMode.CLASSIC;
    private MatchFormat selectedFormat=MatchFormat.INDIVIDUAL;
    private boolean updating,allowed;
    public ModeSelectionPanel(Consumer<GameMode> changed){
        this(changed,format->{});
    }
    public ModeSelectionPanel(Consumer<GameMode> changed,Consumer<MatchFormat> formatChanged){
        super(new BorderLayout(12,4));setOpaque(false);this.changed=Objects.requireNonNull(changed);this.formatChanged=Objects.requireNonNull(formatChanged);
        choice.setPreferredSize(new Dimension(270,34));choice.setFont(UiTheme.BODY);details.setForeground(UiTheme.MUTED);
        choice.setRenderer(new DefaultListCellRenderer(){@Override public Component getListCellRendererComponent(JList<?> list,Object value,int index,boolean select,boolean focus){
            super.getListCellRendererComponent(list,value,index,select,focus);if(value instanceof GameMode mode){boolean enabled=available(mode);setText(label(mode)+(enabled?"":" — Chưa khả dụng"));if(!enabled)setForeground(Color.GRAY);}return this;}});
        choice.addActionListener(e->{if(updating)return;var next=(GameMode)choice.getSelectedItem();if(next==null)return;
            if(!available(next)){select(selected);details.setText(label(next)+" chưa khả dụng. Đang chọn "+label(selected)+".");return;}
            if(next!=selected){selected=next;selectedFormat=MatchFormat.defaultFor(next);syncFormat();showDescription();changed.accept(next);}});
        formatChoice.setFont(UiTheme.BODY);formatChoice.setPreferredSize(new Dimension(190,34));
        formatChoice.setRenderer(new DefaultListCellRenderer(){@Override public Component getListCellRendererComponent(JList<?> list,Object value,int index,boolean select,boolean focus){
            super.getListCellRendererComponent(list,value,index,select,focus);if(value instanceof MatchFormat format)setText(formatLabel(format));return this;}});
        formatChoice.addActionListener(e->{if(updating)return;var next=(MatchFormat)formatChoice.getSelectedItem();
            if(!allowed||selected==GameMode.SHADOW_RELAY||!available(selected)){syncFormat();return;}
            if(next!=null&&next!=selectedFormat){selectedFormat=next;showDescription();formatChanged.accept(next);}});
        var selectors=new JPanel(new FlowLayout(FlowLayout.LEFT,8,0));selectors.setOpaque(false);selectors.add(choice);selectors.add(formatChoice);
        add(selectors,BorderLayout.NORTH);add(details,BorderLayout.CENTER);choice.setEnabled(false);formatChoice.setEnabled(false);
    }
    public static String label(GameMode mode){return switch(mode){case CLASSIC->"Classic";case SHADOW_RELAY->"Shadow Relay";case CIRCUIT->"Circuit";case PARADOX->"Paradox";};}
    public void setModes(java.util.List<ModeDescriptor> next){modes=java.util.List.copyOf(next);updating=true;try{choice.setModel(new DefaultComboBoxModel<>(modes.stream().map(ModeDescriptor::gameMode).toArray(GameMode[]::new)));choice.setSelectedItem(selected);}finally{updating=false;}choice.setEnabled(allowed&&!modes.isEmpty());showDescription();}
    public void select(GameMode mode){updating=true;try{if(selected!=mode)selectedFormat=MatchFormat.defaultFor(mode);selected=mode;choice.setSelectedItem(mode);}finally{updating=false;}syncFormat();showDescription();}
    public void setChangeAllowed(boolean allowed){this.allowed=allowed;choice.setEnabled(allowed&&!modes.isEmpty());syncFormat();}
    /** Đồng bộ từ server phải im lặng, không gửi ngược SET_MATCH_CONFIGURATION. */
    private void syncFormat(){updating=true;try{if(selected==GameMode.SHADOW_RELAY)selectedFormat=MatchFormat.TEAM_2V2;formatChoice.setSelectedItem(selectedFormat);}finally{updating=false;}
        formatChoice.setEnabled(allowed&&available(selected)&&selected!=GameMode.SHADOW_RELAY);}
    public static String formatLabel(MatchFormat format){return format==MatchFormat.TEAM_2V2?"Đội 2–2 (đủ 4 người)":"Cá nhân (2–4 người)";}
    public MatchFormat selectedFormat(){return selectedFormat;}
    public JComboBox<MatchFormat> formatSelector(){return formatChoice;}
    public boolean available(GameMode mode){return modes.stream().anyMatch(m->m.gameMode()==mode&&m.available()&&Set.of("CLASSIC_V1","SHADOW_RELAY_V1","PARADOX_V1").contains(m.requiredFeature()));}
    public GameMode selectedMode(){return selected;}
    public JComboBox<GameMode> selector(){return choice;}
    public String description(){return details.getText();}
    public void showConfiguration(MatchConfiguration c){select(c.gameMode());selectedFormat=c.matchFormat();syncFormat();var r=c.effectiveRules().common();details.setText(label(c.gameMode())+" • "+formatLabel(selectedFormat)+" • "+r.initialLives()+" mạng • "+(r.durationMillis()/1000)+" giây"+(c.gameMode()==GameMode.SHADOW_RELAY?" • F: scan":c.gameMode()==GameMode.PARADOX?" • R: đảo 2 giây • 1 lượt/người":"")+(c.isTeamMatch()?" • Có sát thương đồng đội":""));details.setToolTipText(details.getText());}
    private void showDescription(){details.setText(switch(selected){
        case CLASSIC->formatLabel(selectedFormat)+" • Bom, vật phẩm và hồi sinh"+(selectedFormat==MatchFormat.TEAM_2V2?" • Có sát thương đồng đội":"");
        case PARADOX->available(selected)?formatLabel(selectedFormat)+" • R: đảo toàn bản đồ 2 giây • 1 lượt/người":"Paradox chưa khả dụng";
        case SHADOW_RELAY->available(selected)?"Đội 2–2 • Chiếm trạm • 60 điểm thắng • F: bom quét":"Shadow Relay chưa khả dụng";
        case CIRCUIT->"Circuit chưa khả dụng";});details.setToolTipText(details.getText());}
}
