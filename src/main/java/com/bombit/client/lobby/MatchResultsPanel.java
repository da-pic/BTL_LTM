package com.bombit.client.lobby;

import com.bombit.common.dto.MatchResult;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.Map;

/** Displays only a validated server result; it never calculates a winner from the local replica. */
public final class MatchResultsPanel extends JPanel {
    private final JLabel summary=new JLabel("Chưa có kết quả trận.");
    private final DefaultTableModel rows=new DefaultTableModel(new Object[]{"Hạng","Người chơi"},0){
        @Override public boolean isCellEditable(int row,int column){return false;}
    };
    public MatchResultsPanel(){super(new BorderLayout());add(summary,BorderLayout.NORTH);add(new JScrollPane(new JTable(rows)));}
    public void showResult(MatchResult result,Map<Integer,String> names){
        rows.setRowCount(0);
        String text=switch(result.reason()){
            case LAST_ALIVE->"Thắng: "+names.getOrDefault(result.winnerPlayerId(),"P"+result.winnerPlayerId());
            case ALL_DEAD->"Hòa — không còn người sống.";
            case TIMEOUT->"Hòa — hết thời gian.";
            case ABORTED->"Trận bị hủy — không tính thắng/thua.";
        };
        summary.setText(text);
        for(var rank:result.rankings())rows.addRow(new Object[]{rank.rank(),names.getOrDefault(rank.playerId(),"P"+rank.playerId())});
    }
    public void clear(){rows.setRowCount(0);summary.setText("Trận mới đang diễn ra.");}
    public String summary(){return summary.getText();}
    public int rowCount(){return rows.getRowCount();}
}
