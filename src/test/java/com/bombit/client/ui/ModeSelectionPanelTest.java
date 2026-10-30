package com.bombit.client.ui;

import com.bombit.client.ui.component.ModeSelectionPanel;
import com.bombit.common.dto.*;
import com.bombit.server.game.mode.ModeRegistry;
import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Component/EDT thật, không coi ảnh offscreen là kiểm thử thao tác GUI của người dùng. */
class ModeSelectionPanelTest {
    @Test void paradoxSelectionUsesItsOwnDescriptionAndConfiguration()throws Exception{
        SwingUtilities.invokeAndWait(()->{
            var callbacks=new AtomicInteger();var panel=new ModeSelectionPanel(m->callbacks.incrementAndGet());
            var registry=new ModeRegistry(EffectiveRules.Common.shadowRelayV1());panel.setModes(registry.descriptors());panel.setChangeAllowed(true);
            panel.selector().setSelectedItem(GameMode.PARADOX);assertEquals(GameMode.PARADOX,panel.selectedMode());assertEquals(1,callbacks.get());
            assertTrue(panel.description().contains("R:"));assertTrue(panel.description().contains("2–4"));
            panel.showConfiguration(registry.resolvePublic(GameMode.PARADOX));assertTrue(panel.description().contains("1 lượt/người"));assertEquals(1,callbacks.get());
            panel.selector().setSelectedItem(GameMode.CLASSIC);assertEquals(2,callbacks.get());assertFalse(panel.description().contains("R:"));
        });
    }
    @Test void unavailableModesCannotTriggerAChangeAndProgrammaticSyncIsSilent()throws Exception{
        SwingUtilities.invokeAndWait(()->{var callbacks=new AtomicInteger();var panel=new ModeSelectionPanel(m->callbacks.incrementAndGet());
            panel.setModes(new ModeRegistry(EffectiveRules.Common.shadowRelayV1()).descriptors());panel.setChangeAllowed(true);
            panel.selector().setSelectedItem(GameMode.CIRCUIT);assertEquals(GameMode.CLASSIC,panel.selectedMode());assertEquals(0,callbacks.get());
            assertFalse(panel.available(GameMode.CIRCUIT));panel.setChangeAllowed(false);assertFalse(panel.selector().isEnabled());
            panel.showConfiguration(new ModeRegistry(EffectiveRules.Common.shadowRelayV1()).resolvePublic(GameMode.CLASSIC));assertEquals(0,callbacks.get());assertTrue(panel.description().contains("3 mạng"));
            panel.setSize(900,60);panel.doLayout();var image=new BufferedImage(900,60,BufferedImage.TYPE_INT_ARGB);var g=image.createGraphics();try{panel.printAll(g);}finally{g.dispose();}
        });
    }
}
