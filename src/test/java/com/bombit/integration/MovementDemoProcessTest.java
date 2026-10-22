package com.bombit.integration;

import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

class MovementDemoProcessTest {
    @Test @Timeout(25)
    void launchersWorkInThreeSeparateJavaProcesses() throws Exception {
        String java=Path.of(System.getProperty("java.home"),"bin","java.exe").toString();
        if(!Files.exists(Path.of(java)))java=Path.of(System.getProperty("java.home"),"bin","java").toString();
        String classes=Path.of("target/classes").toAbsolutePath().toString();
        Path serverLog=Path.of("target/m1-server-process.log"),aLog=Path.of("target/m1-client-a-process.log"),bLog=Path.of("target/m1-client-b-process.log");
        Process server=null,a=null,b=null;
        try {
            server=new ProcessBuilder(java,"-cp",classes,"com.bombit.server.app.ServerApplication","0","2")
                    .redirectErrorStream(true).redirectOutput(serverLog.toFile()).start();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);String output="";
            while(System.nanoTime()<deadline){output=Files.readString(serverLog);if(output.contains("port="))break;Thread.sleep(10);}
            var port=Pattern.compile("port=(\\d+)").matcher(output);assertTrue(port.find(),output);
            a=new ProcessBuilder(java,"-cp",classes,"com.bombit.client.app.ClientApplication","127.0.0.1",port.group(1),"Process A","--headless","4")
                    .redirectErrorStream(true).redirectOutput(aLog.toFile()).start();
            b=new ProcessBuilder(java,"-cp",classes,"com.bombit.client.app.ClientApplication","127.0.0.1",port.group(1),"Process B","--headless","4")
                    .redirectErrorStream(true).redirectOutput(bLog.toFile()).start();
            assertTrue(a.waitFor(12,TimeUnit.SECONDS));assertTrue(b.waitFor(12,TimeUnit.SECONDS));
            for(var pair:new Object[][]{{a,aLog},{b,bLog}}){
                String log=Files.readString((Path)pair[1]);assertEquals(0,((Process)pair[0]).exitValue(),log);
                assertTrue(log.contains("READY local="),log);assertTrue(log.contains("players=2"),log);
                assertTrue(Pattern.compile("FINAL ack=[1-9][0-9]*").matcher(log).find(),log);
            }
            assertTrue(server.isAlive());
        } finally {
            for(Process process:new Process[]{a,b,server})if(process!=null){process.destroy();if(!process.waitFor(3,TimeUnit.SECONDS))process.destroyForcibly();}
        }
    }
}
