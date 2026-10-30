package com.bombit.client.ui;

import com.bombit.client.ui.component.ModeSelectionPanel;
import com.bombit.client.ui.game.MatchPresentation;
import com.bombit.client.ui.screen.MatchResultsPanel;
import com.bombit.common.dto.*;
import com.bombit.server.game.mode.ModeRegistry;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TeamGuiPresentationTest {
    private MatchStartMetadata metadata(GameMode mode,MatchFormat format){
        var config=new ModeRegistry(EffectiveRules.Common.shadowRelayV1()).resolvePublic(mode,format);
        var members=new ArrayList<MatchStartMetadata.Member>();var identities=new ArrayList<MatchContext.PlayerIdentity>();
        for(int slot=1;slot<=4;slot++){String id=new UUID(0,slot).toString();int player=slot*10;
            identities.add(new MatchContext.PlayerIdentity(id,player));members.add(new MatchStartMetadata.Member(id,player,slot,"Tên "+slot,config.teamForSlot(slot)));}
        return new MatchStartMetadata(new MatchContext(UUID.randomUUID().toString(),UUID.randomUUID(),MapTheme.FIRE,identities),config,1,members,new StreamContext(10,1,0,0));
    }
    @Test void selectorsHaveIndependentCallbacksSilentSyncAndShadowCannotChooseIndividual()throws Exception{
        SwingUtilities.invokeAndWait(()->{
            var modes=new AtomicInteger();var formats=new AtomicInteger();var registry=new ModeRegistry(EffectiveRules.Common.shadowRelayV1());
            var panel=new ModeSelectionPanel(mode->modes.incrementAndGet(),format->formats.incrementAndGet());panel.setModes(registry.descriptors());panel.setChangeAllowed(true);
            panel.formatSelector().setSelectedItem(MatchFormat.TEAM_2V2);assertEquals(1,formats.get());assertEquals(0,modes.get());
            assertTrue(panel.description().contains("4 người"));assertTrue(panel.description().contains("sát thương đồng đội"));
            panel.showConfiguration(registry.resolvePublic(GameMode.PARADOX,MatchFormat.TEAM_2V2));
            assertEquals(MatchFormat.TEAM_2V2,panel.selectedFormat());assertEquals(1,formats.get());assertEquals(0,modes.get());
            panel.setChangeAllowed(false);assertFalse(panel.formatSelector().isEnabled());
            panel.formatSelector().setSelectedItem(MatchFormat.INDIVIDUAL);assertEquals(MatchFormat.TEAM_2V2,panel.selectedFormat());assertEquals(1,formats.get());
            panel.setChangeAllowed(true);panel.selector().setSelectedItem(GameMode.SHADOW_RELAY);
            assertEquals(MatchFormat.TEAM_2V2,panel.selectedFormat());assertFalse(panel.formatSelector().isEnabled());
            panel.formatSelector().setSelectedItem(MatchFormat.INDIVIDUAL);assertEquals(MatchFormat.TEAM_2V2,panel.formatSelector().getSelectedItem());
            panel.selector().setSelectedItem(GameMode.CLASSIC);assertTrue(panel.formatSelector().isEnabled());assertEquals(MatchFormat.INDIVIDUAL,panel.selectedFormat());
        });
    }
    @Test void colorsAndTagsUseRosterTeamsAndRulesMatchActualFormat(){
        for(var mode:List.of(GameMode.CLASSIC,GameMode.PARADOX)){
            var meta=metadata(mode,MatchFormat.TEAM_2V2);
            assertEquals(MatchPresentation.color(meta,10),MatchPresentation.color(meta,30));
            assertNotEquals(MatchPresentation.color(meta,10),MatchPresentation.color(meta,20));
            assertEquals("P40 · B",MatchPresentation.tag(meta,40));
            var rules=MatchPresentation.rules(meta);assertTrue(rules.contains("sát thương đồng đội"));assertTrue(rules.contains("Hai đội hết người cùng tick"));
            assertFalse(rules.contains("Người sống sót cuối cùng thắng"));if(mode==GameMode.PARADOX)assertTrue(rules.contains("đội và lượt đã dùng giữ nguyên"));
            var individual=metadata(mode,MatchFormat.INDIVIDUAL);assertEquals("P40",MatchPresentation.tag(individual,40));
            assertTrue(MatchPresentation.rules(individual).contains("Người sống sót cuối cùng thắng"));
        }
    }
    @Test void teamWinLossDrawAbortAndReturnToIndividualHaveCorrectHeadlines()throws Exception{
        SwingUtilities.invokeAndWait(()->{
            var players=metadata(GameMode.CLASSIC,MatchFormat.TEAM_2V2).players();var panel=new MatchResultsPanel();
            var win=new TeamMatchResult(50,TeamId.A,TeamMatchResult.Reason.TEAM_ELIMINATED);
            for(int own:List.of(10,30)){panel.showTeamResult(win,players,own);assertTrue(panel.summary().contains("ĐỘI BẠN CHIẾN THẮNG"));assertEquals(4,panel.rowCount());}
            panel.showTeamResult(win,players,20);assertTrue(panel.summary().contains("ĐỘI BẠN ĐÃ THUA"));assertFalse(panel.summary().contains("0 : 0"));
            for(var reason:List.of(TeamMatchResult.Reason.BOTH_TEAMS_ELIMINATED,TeamMatchResult.Reason.TIMEOUT)){
                panel.showTeamResult(new TeamMatchResult(50,null,reason),players,10);assertTrue(panel.summary().contains("HÒA"));
            }
            panel.showTeamResult(new TeamMatchResult(50,null,TeamMatchResult.Reason.ABORTED),players,10);assertTrue(panel.summary().contains("HỦY"));
            panel.showResult(new MatchResult(50,10,MatchResult.Reason.LAST_ALIVE,List.of(new MatchResult.Ranking(10,1))),Map.of(10,"Tên 1"),10);
            assertTrue(panel.summary().contains("CHIẾN THẮNG TUYỆT ĐỐI"));assertEquals(1,panel.rowCount());
        });
    }
}
