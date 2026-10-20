package testing;

/** Bộ kiểm tra không cần JUnit; chạy main để xác nhận các module mạng hoạt động. */
public final class NetworkTest {
    private NetworkTest() { }

    public static void main(String[] args) throws Exception { runAll(); }

    public static void runAll() throws Exception {
        TestScenario.assertCodecRoundTrip();
        TestScenario.assertInvalidLengthRejected();
        TestScenario.assertRouter();
        TestScenario.assertMetrics();
        TestScenario.runLoopbackTest();
        System.out.println("[NetworkTest] PASS: codec, framing, validation, router, metrics, TCP loopback.");
    }
}
