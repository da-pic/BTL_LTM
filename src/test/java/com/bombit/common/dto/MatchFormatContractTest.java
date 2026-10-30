package com.bombit.common.dto;

import com.bombit.common.protocol.*;
import com.bombit.server.game.mode.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MatchFormatContractTest {
    private final ModeRegistry registry=new ModeRegistry(EffectiveRules.Common.shadowRelayV1());
    private MatchStartMetadata metadata(GameMode mode,MatchFormat format,int count){
        var config=registry.resolvePublic(mode,format);var members=new ArrayList<MatchStartMetadata.Member>();
        var identities=new ArrayList<MatchContext.PlayerIdentity>();
        for(int i=0;i<count;i++){
            String session=new UUID(0,i+1).toString();int id=i+10;
            identities.add(new MatchContext.PlayerIdentity(session,id));
            members.add(new MatchStartMetadata.Member(session,id,i+1,"P"+i,config.teamForSlot(i+1)));
        }
        return new MatchStartMetadata(new MatchContext(UUID.randomUUID().toString(),UUID.randomUUID(),MapTheme.ICE,identities),config,1,members,new StreamContext(10,1,0,0));
    }
    @Test void defaultsAndConstraintsAreIndependentOfGameMode(){
        for(var mode:List.of(GameMode.CLASSIC,GameMode.PARADOX)){
            var config=registry.resolvePublic(mode);assertEquals(MatchFormat.INDIVIDUAL,config.matchFormat());assertNull(config.teamForSlot(1));
            var teams=config.withFormat(MatchFormat.TEAM_2V2);assertNotEquals(config.configurationId(),teams.configurationId());
            assertEquals(List.of(TeamId.A,TeamId.B,TeamId.A,TeamId.B),java.util.stream.IntStream.rangeClosed(1,4).mapToObj(teams::teamForSlot).toList());
            assertDoesNotThrow(()->registry.validatePublicConfiguration(teams));
            assertDoesNotThrow(()->registry.validatePublicStart(teams,4));
            assertEquals(ModeConfigurationException.Code.INVALID_CONFIGURATION,assertThrows(ModeConfigurationException.class,()->registry.validatePublicStart(teams,3)).code());
            assertThrows(ModeConfigurationException.class,()->registry.validateClientFeatures(teams,Set.of("CLASSIC_V1","PARADOX_V1")));
            assertDoesNotThrow(()->registry.validateClientFeatures(teams,Set.of("CLASSIC_V1","PARADOX_V1","TEAM_MATCH_V1")));
        }
        assertEquals(MatchFormat.TEAM_2V2,registry.resolvePublic(GameMode.SHADOW_RELAY).matchFormat());
        assertThrows(ModeConfigurationException.class,()->registry.resolvePublic(GameMode.SHADOW_RELAY,MatchFormat.INDIVIDUAL));
        assertThrows(IllegalArgumentException.class,()->MatchFormat.TEAM_2V2.teamForSlot(0));
        assertThrows(IllegalArgumentException.class,()->registry.resolveInternalClassic(EffectiveRules.Common.shadowRelayV1()).withFormat(MatchFormat.TEAM_2V2));
    }
    @Test void configurationRoundTripsBothFormatsAndMissingFieldUsesModeDefault(){
        for(var mode:List.of(GameMode.CLASSIC,GameMode.PARADOX,GameMode.SHADOW_RELAY)){
            var config=registry.resolvePublic(mode);var raw=JsonV3.object(JsonV3.encode(MetadataV3Codec.configuration(config)),16384);
            assertEquals(config,MetadataV3Codec.configuration(raw));raw.remove("matchFormat");assertEquals(config,MetadataV3Codec.configuration(raw));
            raw.put("matchFormat","SQUADS");assertThrows(IllegalArgumentException.class,()->MetadataV3Codec.configuration(raw));
            raw.put("matchFormat",null);assertThrows(IllegalArgumentException.class,()->MetadataV3Codec.configuration(raw));
            if(mode!=GameMode.SHADOW_RELAY){var teams=config.withFormat(MatchFormat.TEAM_2V2);assertEquals(teams,MetadataV3Codec.configuration(JsonV3.object(JsonV3.encode(MetadataV3Codec.configuration(teams)),16384)));}
        }
    }
    @Test void rosterTeamIsBasedOnSlotAndStartedWireKeepsIt(){
        for(var mode:List.of(GameMode.CLASSIC,GameMode.PARADOX,GameMode.SHADOW_RELAY)){
            var meta=metadata(mode,MatchFormat.TEAM_2V2,4);
            assertEquals(meta,MetadataV3Codec.decodeStarted(MetadataV3Codec.started(meta)));
            assertEquals(TeamId.A,meta.players().getFirst().team()); // numeric ID 10 không quyết định đội.
            var invalid=new ArrayList<>(meta.players());var first=invalid.getFirst();
            invalid.set(0,new MatchStartMetadata.Member(first.sessionId(),first.playerId(),1,first.username(),TeamId.B));
            assertThrows(IllegalArgumentException.class,()->new MatchStartMetadata(meta.identity(),meta.configuration(),1,invalid,meta.stream()));
            assertThrows(IllegalArgumentException.class,()->metadata(mode,MatchFormat.TEAM_2V2,3));
        }
    }
    @Test void startedLegacyDefaultDoesNotSilentlyInterpretTeamRosterAsIndividual(){
        for(var mode:List.of(GameMode.CLASSIC,GameMode.PARADOX)){
            var individual=metadata(mode,MatchFormat.INDIVIDUAL,2);var raw=JsonV3.object(MetadataV3Codec.started(individual).getJsonPayload(),16384);
            raw.remove("matchFormat");assertEquals(individual,MetadataV3Codec.decodeStarted(JsonV3.packet(PacketType.GAME_STARTED,raw)));
            var teams=metadata(mode,MatchFormat.TEAM_2V2,4);var teamRaw=JsonV3.object(MetadataV3Codec.started(teams).getJsonPayload(),16384);teamRaw.remove("matchFormat");
            assertThrows(IllegalArgumentException.class,()->MetadataV3Codec.decodeStarted(JsonV3.packet(PacketType.GAME_STARTED,teamRaw)));
        }
    }
}
