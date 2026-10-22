package com.bombit.tools;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class NetworkBenchmarkTest {
    @TempDir Path temp;
    @Test void configurationSeparatesOneClientTransportFromMultiplayerAndRejectsAmbiguousOptions(){
        assertThrows(IllegalArgumentException.class,()->NetworkBenchmark.parse(new String[]{"--clients","1"}));
        assertThrows(IllegalArgumentException.class,()->NetworkBenchmark.parse(new String[]{"--local","--mode","suite","--seconds","1"}));
        assertThrows(IllegalArgumentException.class,()->NetworkBenchmark.parse(new String[]{"--clients","2","--clients","4"}));
        var one=NetworkBenchmark.parse(new String[]{"--local","--clients","1","--mode","transport"});assertEquals(1,one.clients());
        var guest=NetworkBenchmark.parse(new String[]{"--clients","1","--expected-clients","4","--room-id",UUID.randomUUID().toString()});
        assertEquals(4,guest.expected());assertFalse(guest.local());
    }
    @Test void csvPreservesUnicodeQuotesAndNewlinesAndRefusesMismatchedAppend()throws Exception{
        Path file=temp.resolve("csv/report.csv");
        BenchmarkCsv.append(file,List.of("a","b"),List.of(Map.of("a","Mai, \"M\"","b","Việt\nNam")));
        BenchmarkCsv.append(file,List.of("a","b"),List.of(Map.of("a","plain","b","text")));
        assertEquals("a,b\r\n\"Mai, \"\"M\"\"\",\"Việt\nNam\"\r\nplain,text\r\n",Files.readString(file));
        assertThrows(java.io.IOException.class,()->BenchmarkCsv.append(file,List.of("other"),List.of()));
    }
    @Test void realOneClientTransportMeasurementWritesSuccessfulMeasuredCountersAndCleansUp()throws Exception{
        Path file=temp.resolve("transport.csv");
        var c=new NetworkBenchmark.Config(true,"127.0.0.1",0,1,1,null,"transport",1,0,file,"test","working-tree",UUID.randomUUID().toString());
        assertTrue(NetworkBenchmark.run(c));var lines=Files.readAllLines(file);assertEquals(2,lines.size());
        var values=lines.get(1).split(",",-1);assertEquals("PASS",value(values,"status"));assertEquals("true",value(values,"cleanup_ok"));
        assertEquals("0",value(values,"match_players"));assertTrue(Long.parseLong(value(values,"rtt_samples"))>0);
        assertTrue(Long.parseLong(value(values,"rx_bytes"))>0);assertEquals("",value(values,"move_ack_mean_ms"));
    }
    @Test void realFourClientBombWorkloadMeasuresEverySharedExplosionAndMovementAck()throws Exception{
        Path file=temp.resolve("bombs.csv");
        var c=new NetworkBenchmark.Config(true,"127.0.0.1",0,4,4,null,"bombs",4,0,file,"test","working-tree",UUID.randomUUID().toString());
        assertTrue(NetworkBenchmark.run(c));var lines=Files.readAllLines(file);assertEquals(5,lines.size());
        for(String line:lines.subList(1,lines.size())){
            var values=line.split(",",-1);assertEquals("PASS",value(values,"status"));assertEquals("4",value(values,"match_players"));
            assertEquals("4",value(values,"bombs_observed"));assertEquals("4",value(values,"explosions_observed"));
            assertTrue(Long.parseLong(value(values,"move_ack_samples"))>30);assertFalse(value(values,"plant_ack_ms").isEmpty());
            assertEquals("0",value(values,"resync_requests"));
        }
    }
    private static String value(String[] values,String column){return values[NetworkBenchmark.COLUMNS.indexOf(column)];}
}
