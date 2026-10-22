package com.bombit.client.lobby;

import com.bombit.common.dto.MatchResult;
import java.util.*;
import java.awt.image.BufferedImage;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MatchResultsPanelTest {
    @Test void showsAuthoritativeWinnerDrawAbortAndClearsForReplay()throws Exception{
        SwingUtilities.invokeAndWait(()->{
            var panel=new MatchResultsPanel();
            panel.showResult(new MatchResult(9,2,MatchResult.Reason.LAST_ALIVE,List.of(new MatchResult.Ranking(2,1),new MatchResult.Ranking(1,2))),Map.of(1,"Chinh",2,"Mai"));
            assertTrue(panel.summary().contains("Mai"));assertEquals(2,panel.rowCount());
            panel.setSize(400,200);panel.doLayout();var image=new BufferedImage(400,200,BufferedImage.TYPE_INT_ARGB);var g=image.createGraphics();
            try{panel.paint(g);}finally{g.dispose();}assertNotEquals(0,image.getRGB(0,0));
            panel.showResult(new MatchResult(10,null,MatchResult.Reason.TIMEOUT,List.of(new MatchResult.Ranking(1,1))),Map.of());
            assertTrue(panel.summary().contains("Hòa"));
            panel.showResult(new MatchResult(11,null,MatchResult.Reason.ABORTED,List.of()),Map.of());assertEquals(0,panel.rowCount());
            panel.clear();assertEquals(0,panel.rowCount());assertTrue(panel.summary().contains("Trận mới"));
        });
    }
}
