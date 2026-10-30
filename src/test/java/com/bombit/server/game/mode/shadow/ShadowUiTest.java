package com.bombit.server.game.mode.shadow;

import com.bombit.client.game.movement.*;
import com.bombit.client.network.ClientNetwork;
import com.bombit.client.ui.game.*;
import com.bombit.client.ui.screen.MatchResultsPanel;
import com.bombit.common.dto.*;
import com.bombit.common.protocol.*;
import com.bombit.server.game.movement.StateDeltaBuilder;
import com.bombit.server.game.replication.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.image.BufferedImage;
import java.net.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.*;
import javax.swing.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** UI trên EDT + input thật qua TCP; không mở cửa sổ hoặc chạy toàn suite cho thay đổi này. */
class ShadowUiTest {
    private static MatchStartMetadata metadata(ShadowScanTest.Fixture f){return new MatchStartMetadata(
            new MatchContext(UUID.randomUUID().toString(),UUID.randomUUID(),MapTheme.FIRE,f.roster.identities()),
            ShadowMatchSetup.newConfiguration(),1,f.roster.players(),new StreamContext(1,1,0,0));}
    private static ShadowSnapshotV6 snapshot(ShadowScanTest.Fixture f,MatchStartMetadata meta)throws Exception{
        var frame=new WorldFrame(new StateDeltaBuilder().buildUpdate(f.state,f.state.getShadowFrame().tick(),true),f.clock.get()/1_000_000,
                10000,Set.of(),List.of(),f.state.getShadowFrame(),f.scans());
        var update=new ShadowRecipientStream(meta).build(frame);
        return SnapshotV6Codec.decodeShadow(SnapshotV6Codec.encode(update,10000,meta.identity()),meta);
    }
    private static void key(JComponent root,String stroke){var name=root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(KeyStroke.getKeyStroke(stroke));root.getActionMap().get(name).actionPerformed(new ActionEvent(root,0,"test"));}

    @Test void heldKeysSendOnceAndLockedPressCannotReplayAfterRecovery()throws Exception{
        SwingUtilities.invokeAndWait(()->{
            var root=new JPanel();var ready=new AtomicBoolean(true);var normal=new AtomicInteger();var scan=new AtomicInteger();
            root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("pressed F"),"old-F");
            try(var bindings=new BombInputBindings(root,null,ready::get,normal::incrementAndGet,scan::incrementAndGet,error->{throw error;})){
                key(root,"pressed F");key(root,"pressed F");assertEquals(1,scan.get());
                key(root,"released F");ready.set(false);key(root,"pressed F");ready.set(true);key(root,"pressed F");assertEquals(1,scan.get());
                key(root,"released F");key(root,"pressed F");key(root,"pressed SPACE");key(root,"pressed SPACE");
                assertEquals(2,scan.get());assertEquals(1,normal.get());
            }
            assertEquals("old-F",root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(KeyStroke.getKeyStroke("pressed F")));
        });
    }

    @Test void normalAndScanInputsShareRequestIdsOnTheWireAndStopWhileSuspended()throws Exception{
        try(var f=new ShadowScanTest.Fixture();var listener=new ServerSocket(0);var network=new ClientNetwork()){
            network.connect("127.0.0.1",listener.getLocalPort());try(var peer=listener.accept()){
                peer.setSoTimeout(2000);var meta=metadata(f);f.at(0);var update=snapshot(f,meta);var failures=new ArrayList<Throwable>();
                try(var controller=new MovementClientController(network,1,Runnable::run,()->{},failures::add,meta.identity().matchId(),false)){
                    SwingUtilities.invokeAndWait(()->{controller.bindCurrentMetadata(meta);controller.acceptSnapshotV6(update);controller.resumeAfterRecovery();controller.plantBomb();controller.plantScan();});
                    var first=MatchEnvelopeV3Codec.unwrapCommand(PacketDecoder.decode(peer.getInputStream()),meta.identity().matchId());
                    var second=MatchEnvelopeV3Codec.unwrapCommand(PacketDecoder.decode(peer.getInputStream()),meta.identity().matchId());
                    var one=JsonV3.object(first.getJsonPayload(),4096);var two=JsonV3.object(second.getJsonPayload(),4096);
                    assertEquals(1L,one.get("requestId"));assertEquals("NORMAL",one.get("bombType"));assertEquals(2L,two.get("requestId"));assertEquals("SCAN",two.get("bombType"));
                    SwingUtilities.invokeAndWait(()->{controller.suspendForRecovery();controller.plantScan();});assertEquals(0,peer.getInputStream().available());assertTrue(failures.isEmpty());
                }
            }
        }
    }

    @Test void hudHidesUnknownStatsAndFogKeepsPublicStationsAndScanVisible()throws Exception{
        try(var f=new ShadowScanTest.Fixture()){
            var meta=metadata(f);f.plant(1,1,BombType.SCAN);f.plant(2,1,BombType.SCAN);f.at(0);var update=snapshot(f,meta);
            try(var controller=new MovementClientController(new ClientNetwork(),1,Runnable::run,()->{},e->{throw new AssertionError(e);},meta.identity().matchId(),false)){
                SwingUtilities.invokeAndWait(()->{
                    controller.bindCurrentMetadata(meta);controller.acceptSnapshotV6(update);controller.resumeAfterRecovery();
                    var hud=new ShadowHudPanel();hud.update(controller,1);
                    assertTrue(hud.playerText(2).contains("Chỉ số chưa quan sát"));assertFalse(hud.playerText(1).contains("Chỉ số chưa quan sát"));
                    assertTrue(hud.scanText().contains("Scan hồi"));assertEquals("A 0 : 0 B",hud.scoreText());
                    var renderer=new ShadowOverlayRenderer();var image=new BufferedImage(544,480,BufferedImage.TYPE_INT_ARGB);var g=image.createGraphics();
                    try{
                        g.setColor(new Color(90,100,120));g.fillRect(0,0,544,480);int base=image.getRGB(36,36);
                        renderer.fog(g,controller.shadow(),17,15,32);assertEquals(base,image.getRGB(36,36));assertNotEquals(base,image.getRGB(15*32+10,32+10));
                        int hidden=image.getRGB(15*32+10,32+10);renderer.objects(g,controller,32);
                        assertNotEquals(hidden,image.getRGB(15*32+10,32+10)); // Scan công khai được vẽ sau fog.
                    }finally{g.dispose();}
                    hud.setSize(232,480);hud.doLayout();var graphics=image.createGraphics();try{hud.paint(graphics);}finally{graphics.dispose();}
                    assertTrue(ShadowHudPanel.RULES.contains("F: bom quét"));
                });
            }
        }
    }

    @Test void resultsDistinguishTeamWinDrawAbortAndClassicReplay()throws Exception{
        try(var f=new ShadowScanTest.Fixture()){
            SwingUtilities.invokeAndWait(()->{
                var panel=new MatchResultsPanel();panel.showTeamResult(new ShadowFrame.Result(1,TeamId.A,ShadowFrame.Reason.TARGET_SCORE,60,45),f.roster.players(),1);
                assertTrue(panel.summary().contains("CHIẾN THẮNG"));assertTrue(panel.summary().contains("60 : 45"));assertEquals(4,panel.rowCount());
                panel.showTeamResult(new ShadowFrame.Result(2,null,ShadowFrame.Reason.TIMEOUT,10,10),f.roster.players(),1);assertTrue(panel.summary().contains("HÒA"));
                panel.showTeamResult(new ShadowFrame.Result(3,null,ShadowFrame.Reason.ABORTED,10,10),f.roster.players(),1);assertTrue(panel.summary().contains("HỦY"));
                panel.showResult(new MatchResult(4,1,MatchResult.Reason.LAST_ALIVE,List.of(new MatchResult.Ranking(1,1))),Map.of(1,"Chinh"),1);
                assertTrue(panel.summary().contains("CHIẾN THẮNG"));assertEquals(1,panel.rowCount());
            });
        }
    }

    @Test void enemyDamageBombOutsideVisionIsHiddenButExplosionRemainsPublic()throws Exception{
        try(var f=new ShadowScanTest.Fixture()){
            var meta=metadata(f);var stream=new ShadowRecipientStream(meta);var errors=new ArrayList<Throwable>();
            try(var controller=new MovementClientController(new ClientNetwork(),1,Runnable::run,()->{},errors::add,meta.identity().matchId(),false)){
                f.at(0);var first=stream.build(new WorldFrame(new StateDeltaBuilder().buildUpdate(f.state,1,true),0,10000,Set.of(),List.of(),f.state.getShadowFrame(),f.scans()));
                var full=SnapshotV6Codec.decodeShadow(SnapshotV6Codec.encode(first,10000,meta.identity()),meta);stream.commit(first);
                var placed=f.plant(2,1,BombType.NORMAL);assertTrue(placed.accepted());f.at(50);
                var next=stream.build(new WorldFrame(new StateDeltaBuilder().buildUpdate(f.state,2,true),50,10000,Set.of(),List.of(),f.state.getShadowFrame(),f.scans()));
                assertFalse(next.update().isFullState());var delta=SnapshotV6Codec.decodeShadow(SnapshotV6Codec.encode(next,10000,meta.identity()),meta);stream.commit(next);
                SwingUtilities.invokeAndWait(()->{
                    controller.bindCurrentMetadata(meta);controller.acceptSnapshotV6(full);controller.resumeAfterRecovery();controller.acceptSnapshotV6(delta);
                    var cell=new com.bombit.server.game.model.TilePosition(15,1);
                    assertFalse(controller.shadow().visibleCells().contains(cell));assertFalse(controller.world().getPlayers().containsKey(2));
                    assertFalse(controller.world().getEntities().containsKey("BOMB:"+placed.bombId()));
                    assertTrue(controller.bombs().state().bombs().isEmpty());
                    assertTrue(delta.update().getGameNotices().isEmpty(),"Hidden placement notice must not expose bomb coordinates");
                    var image=new BufferedImage(544,480,BufferedImage.TYPE_INT_ARGB);var g=image.createGraphics();
                    try{
                        new TileGridRenderer().render(g,17,15,32,controller.world().getTiles(),MapTheme.FIRE);
                        new ShadowOverlayRenderer().fog(g,controller.shadow(),17,15,32);
                        int[] before=image.getRGB(15*32,32,32,32,null,0,32);
                        new ShadowOverlayRenderer().objects(g,controller,32);
                        int[] after=image.getRGB(15*32,32,32,32,null,0,32);int changed=0;
                        for(int i=0;i<before.length;i++)if(before[i]!=after[i])changed++;
                        assertEquals(0,changed,"Hidden enemy bomb must not paint an icon");
                    }finally{g.dispose();}
                    assertTrue(errors.isEmpty(),errors.toString());
                });
                f.at(3000);var ended=stream.build(new WorldFrame(new StateDeltaBuilder().buildUpdate(f.state,3,true),3000,10000,Set.of(),List.of(),f.state.getShadowFrame(),f.scans()));
                var explosion=SnapshotV6Codec.decodeShadow(SnapshotV6Codec.encode(ended,10000,meta.identity()),meta);
                SwingUtilities.invokeAndWait(()->{
                    controller.acceptSnapshotV6(explosion);
                    assertFalse(controller.world().getPlayers().containsKey(2));
                    assertTrue(controller.bombs().state().bombs().isEmpty());
                    assertTrue(controller.bombs().state().explosions().stream().anyMatch(e->e.bombId()==placed.bombId()));
                });
            }
        }
    }
}
