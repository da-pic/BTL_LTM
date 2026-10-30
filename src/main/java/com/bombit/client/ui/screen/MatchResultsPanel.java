package com.bombit.client.ui.screen;

import com.bombit.client.ui.UiTheme;
import com.bombit.common.dto.MatchResult;
import com.bombit.common.dto.ShadowFrame;
import com.bombit.common.dto.MatchStartMetadata;
import com.bombit.common.dto.TeamId;
import com.bombit.common.dto.TeamMatchResult;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.Map;

/** Arcade result hero and server-authoritative ranking table. */
public final class MatchResultsPanel extends JPanel {
    private final JLabel trophy=new JLabel("★",SwingConstants.CENTER);
    private final JLabel headline=new JLabel("KẾT QUẢ TRẬN ĐẤU");
    private final JLabel summary=new JLabel("Chưa có kết quả.");
    private final DefaultTableModel rows=new DefaultTableModel(new Object[]{"HẠNG","NGƯỜI CHƠI","KẾT QUẢ"},0){@Override public boolean isCellEditable(int row,int column){return false;}};
    private final JTable table=new JTable(rows);

    public MatchResultsPanel(){
        super(new BorderLayout(0,16));setOpaque(false);setBorder(new EmptyBorder(0,0,0,0));
        JPanel hero=new UiTheme.RoundedPanel(new BorderLayout(18,0),new Color(42,31,28),18);hero.setBorder(new EmptyBorder(18,20,18,20));
        trophy.setPreferredSize(new Dimension(78,78));trophy.setOpaque(true);trophy.setBackground(new Color(245,158,11));trophy.setForeground(Color.WHITE);trophy.setFont(UiTheme.TITLE.deriveFont(38f));
        JPanel message=UiTheme.panel(new GridLayout(2,1,0,5));headline.setFont(UiTheme.TITLE.deriveFont(25f));headline.setForeground(Color.WHITE);summary.setFont(UiTheme.BODY.deriveFont(Font.BOLD,13f));summary.setForeground(new Color(203,213,225));message.add(headline);message.add(summary);hero.add(trophy,BorderLayout.WEST);hero.add(message);add(hero,BorderLayout.NORTH);

        styleTable();JScrollPane scroll=new JScrollPane(table);scroll.setBorder(BorderFactory.createEmptyBorder());scroll.setViewportBorder(null);scroll.getViewport().setBackground(new Color(8,15,28));add(scroll);
    }

    private void styleTable(){
        table.setRowHeight(48);table.setShowGrid(false);table.setIntercellSpacing(new Dimension(0,0));table.setFillsViewportHeight(true);table.setFocusable(false);table.setBackground(new Color(8,15,28));table.setForeground(UiTheme.TEXT);table.setSelectionBackground(new Color(22,78,108));table.setSelectionForeground(Color.WHITE);
        table.getTableHeader().setBorder(null);table.getTableHeader().setPreferredSize(new Dimension(0,40));table.getTableHeader().setReorderingAllowed(false);table.getTableHeader().setResizingAllowed(false);table.getTableHeader().setDefaultRenderer(new HeaderRenderer());
        table.getColumnModel().getColumn(0).setPreferredWidth(95);table.getColumnModel().getColumn(0).setMaxWidth(115);table.getColumnModel().getColumn(1).setPreferredWidth(360);table.getColumnModel().getColumn(2).setPreferredWidth(190);table.setDefaultRenderer(Object.class,new ResultRenderer());
    }
    public void showResult(MatchResult result,Map<Integer,String> names){showResult(result,names,-1);}
    public void showResult(MatchResult result,Map<Integer,String> names,int localId){
        rows.setColumnIdentifiers(new Object[]{"HẠNG","NGƯỜI CHƠI","KẾT QUẢ"});styleTable();
        rows.setRowCount(0);
        switch(result.reason()){
            case LAST_ALIVE->{boolean won=result.winnerPlayerId()!=null&&result.winnerPlayerId()==localId;headline.setText(won?"CHIẾN THẮNG TUYỆT ĐỐI!":"BẠN ĐÃ THUA");summary.setText("Người sống sót cuối cùng: "+names.getOrDefault(result.winnerPlayerId(),"P"+result.winnerPlayerId()));trophy.setText(won?"★":"×");trophy.setBackground(won?new Color(245,158,11):new Color(190,45,68));}
            case ALL_DEAD->{headline.setText("HÒA TRẬN");summary.setText("Tất cả người chơi đã bị loại cùng lúc.");trophy.setText("=");trophy.setBackground(new Color(14,165,233));}
            case TIMEOUT->{headline.setText("HÒA TRẬN");summary.setText("Trận đấu kết thúc do hết thời gian.");trophy.setText("=");trophy.setBackground(new Color(14,165,233));}
            case ABORTED->{headline.setText("TRẬN ĐẤU BỊ HỦY");summary.setText("Một người chơi đã rời trận hoặc mất kết nối.");trophy.setText("!");trophy.setBackground(new Color(190,45,68));}
        }
        for(var rank:result.rankings()){
            String player=names.getOrDefault(rank.playerId(),"P"+rank.playerId())+(rank.playerId()==localId?"  (Bạn)":"");
            String outcome=result.winnerPlayerId()!=null&&rank.playerId()==result.winnerPlayerId()?"SỐNG SÓT":"BỊ LOẠI";
            rows.addRow(new Object[]{"#"+rank.rank(),player,outcome});
        }
    }
    /** Kết quả đội không dựng hạng cá nhân hoặc vị trí cuối của đối thủ ẩn. */
    public void showTeamResult(ShadowFrame.Result result,java.util.List<MatchStartMetadata.Member> players,int localId){
        rows.setColumnIdentifiers(new Object[]{"ĐỘI","NGƯỜI CHƠI","KẾT QUẢ"});styleTable();rows.setRowCount(0);
        TeamId own=players.stream().filter(p->p.playerId()==localId).map(MatchStartMetadata.Member::team).findFirst().orElse(null);
        boolean aborted=result.reason()==ShadowFrame.Reason.ABORTED,draw=result.winningTeam()==null;
        headline.setText(aborted?"TRẬN ĐẤU BỊ HỦY":draw?"HAI ĐỘI HÒA NHAU":own==result.winningTeam()?"ĐỘI BẠN CHIẾN THẮNG!":"ĐỘI BẠN ĐÃ THUA");
        String reason=switch(result.reason()){case TEAM_ELIMINATED->"Loại hết đối phương";case BOTH_TEAMS_ELIMINATED->"Hai đội cùng bị loại";
            case TARGET_SCORE->"Đạt ngưỡng điểm";case TIMEOUT->"Hết thời gian";case ABORTED->"Hủy hành chính";};
        summary.setText("Đội A "+result.scoreA()+" : "+result.scoreB()+" Đội B · "+reason);
        trophy.setText(aborted?"!":draw?"=":own==result.winningTeam()?"★":"×");trophy.setBackground(draw?new Color(14,165,233):own==result.winningTeam()?new Color(245,158,11):new Color(190,45,68));
        players.stream().sorted(java.util.Comparator.comparing(MatchStartMetadata.Member::team).thenComparingInt(MatchStartMetadata.Member::playerId)).forEach(p->
            rows.addRow(new Object[]{p.team().name(),p.username()+(p.playerId()==localId?" (Bạn)":""),aborted?"HỦY":draw?"HÒA":p.team()==result.winningTeam()?"THẮNG":"THUA"}));
    }
    public void clear(){rows.setRowCount(0);trophy.setText("•");trophy.setBackground(new Color(14,165,233));headline.setText("TRẬN ĐẤU ĐANG DIỄN RA");summary.setText("Đang chờ kết quả từ máy chủ.");}
    /** Classic/Paradox không có điểm trạm; chỉ hiển thị quyết định đội authoritative. */
    public void showTeamResult(TeamMatchResult result,java.util.List<MatchStartMetadata.Member> players,int localId){
        rows.setColumnIdentifiers(new Object[]{"ĐỘI","NGƯỜI CHƠI","KẾT QUẢ"});styleTable();rows.setRowCount(0);
        TeamId own=players.stream().filter(p->p.playerId()==localId).map(MatchStartMetadata.Member::team).findFirst().orElse(null);
        boolean aborted=result.reason()==TeamMatchResult.Reason.ABORTED,draw=result.winningTeam()==null,won=own==result.winningTeam();
        headline.setText(aborted?"TRẬN ĐẤU BỊ HỦY":draw?"HAI ĐỘI HÒA NHAU":won?"ĐỘI BẠN CHIẾN THẮNG!":"ĐỘI BẠN ĐÃ THUA");
        summary.setText(switch(result.reason()){
            case TEAM_ELIMINATED->"Đội "+result.winningTeam()+" thắng — đội đối phương đã bị loại hết";
            case BOTH_TEAMS_ELIMINATED->"Hai đội cùng bị loại trong một tick";
            case TIMEOUT->"Hết thời gian — cả hai đội vẫn còn người sống";
            case ABORTED->"Trận đấu được kết thúc trước thời hạn";});
        trophy.setText(aborted?"!":draw?"=":won?"★":"×");trophy.setBackground(aborted?new Color(190,45,68):draw?new Color(14,165,233):won?new Color(245,158,11):new Color(190,45,68));
        players.stream().sorted(java.util.Comparator.comparing(MatchStartMetadata.Member::team).thenComparingInt(MatchStartMetadata.Member::slot)).forEach(p->
                rows.addRow(new Object[]{p.team().name(),p.username()+(p.playerId()==localId?" (Bạn)":""),aborted?"HỦY":draw?"HÒA":p.team()==result.winningTeam()?"THẮNG":"THUA"}));
    }
    public String summary(){return headline.getText()+" "+summary.getText();}
    public int rowCount(){return rows.getRowCount();}

    private static final class HeaderRenderer extends DefaultTableCellRenderer {
        @Override public Component getTableCellRendererComponent(JTable table,Object value,boolean selected,boolean focus,int row,int column){super.getTableCellRendererComponent(table,value,selected,focus,row,column);setOpaque(true);setBackground(new Color(26,40,61));setForeground(new Color(148,163,184));setFont(UiTheme.BODY.deriveFont(Font.BOLD,11f));setHorizontalAlignment(column==1?SwingConstants.LEFT:SwingConstants.CENTER);setBorder(new EmptyBorder(0,column==1?18:8,0,8));return this;}
    }
    private static final class ResultRenderer extends DefaultTableCellRenderer {
        @Override public Component getTableCellRendererComponent(JTable table,Object value,boolean selected,boolean focus,int row,int column){super.getTableCellRendererComponent(table,value,selected,focus,row,column);String text=String.valueOf(value);setText(text);setOpaque(true);setBackground(selected?new Color(22,78,108):row%2==0?new Color(14,24,41):new Color(18,30,49));setHorizontalAlignment(column==1?SwingConstants.LEFT:SwingConstants.CENTER);setFont(UiTheme.BODY.deriveFont(column==0||column==2?Font.BOLD:Font.PLAIN,13f));if(column==0)setForeground(row==0?new Color(251,191,36):row==1?new Color(203,213,225):new Color(205,127,50));else if(column==2)setForeground(("S\u1ed0NG S\u00d3T".equals(text)||"TH\u1eaeNG".equals(text))?new Color(52,211,153):new Color(248,113,113));else setForeground(UiTheme.TEXT);setBorder(new EmptyBorder(0,column==1?18:8,0,8));return this;}
    }
}
