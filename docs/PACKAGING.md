# Build và đóng gói

Build cần **JDK 25+**, PowerShell và công cụ `javac`/`jar` qua JAVA_HOME hoặc PATH.
Không cần Maven để build bản chạy. Test JUnit cần Maven và các dependency trong pom.xml.

```powershell
cmd /c scripts\build-demo.cmd
cmd /c scripts\test.cmd
cmd /c scripts\package.cmd
```

`build-demo.cmd` gọi `build.ps1`: xóa riêng thư mục output `target/demo-classes`,
biên dịch nguồn production UTF-8 với `--release 25`, chép resources và tạo
`target/bomb-it-multiplayer.jar`. Không đóng test class vào JAR.
`target/BUILD_INFO.txt` ghi UTC, revision, target Java và SHA256 JAR.
Hậu tố `-dirty` nghĩa là có sửa đổi chưa commit; không phải mã định danh bất biến cho toàn bộ nguồn.

`test.cmd` chạy Maven test, các Check/Demo TV2 rồi `NetworkTest` main; lỗi bước nào trả exit code khác 0.
Nếu Maven không ở PATH, đặt `$env:BOMBIT_MVN='C:\duong-dan\mvn.cmd'`.
Có thể truyền tùy chọn Maven, ví dụ `cmd /c scripts\test.cmd -o` khi dependency đã có trong cache.

`package.cmd` build lại và tạo:

```text
target/release/bomb-it-multiplayer.zip
target/release/bomb-it-multiplayer.zip.sha256
  bomb-it-multiplayer/
    bomb-it-multiplayer.jar
    BUILD_INFO.txt
    README.md, PROTOCOL.md, docs/
    scripts/run-server.cmd, run-client.cmd, run-benchmark.cmd
    scripts/java-env.cmd, classpath.cmd
```

Thư mục staging `target/release/bomb-it-multiplayer` được thay thế mỗi lần package.
ZIP là bản runtime, không kèm source, Maven, JDK/JRE hoặc installer. Bàn giao source repository
riêng nếu cần sửa/build lại. Giữ ZIP và file SHA256 đi cùng nhau; kiểm tra:

```powershell
Get-FileHash target\release\bomb-it-multiplayer.zip -Algorithm SHA256
Get-Content target\release\bomb-it-multiplayer.zip.sha256
```

Nếu Windows khóa JAR vì server/client đang chạy, có thể build sang tên khác mà không đóng tiến trình đó:

```powershell
cmd /c scripts\package.cmd -ArtifactName bomb-it-multiplayer-grid.jar
```

JAR nguồn mới ở target mang tên đã chọn và có file BUILD_INFO riêng; trong ZIP vẫn tên chuẩn
`bomb-it-multiplayer.jar` để các script chạy được. Giải nén ZIP sang thư mục mới để chạy bản mới.
Launcher tại source vẫn ưu tiên JAR tên chuẩn, vì vậy không dùng nó để kiểm tra bản tên riêng.

## Chạy bản giải nén

Máy nhận cần Java runtime 25+; gameplay Swing cần desktop. Mở terminal tại thư mục
`bomb-it-multiplayer` giải nén, rồi chạy:

```powershell
cmd /c scripts\run-server.cmd 8888
cmd /c scripts\run-client.cmd 127.0.0.1 8888 Mai
cmd /c scripts\run-benchmark.cmd --local --mode suite --seconds 5 --warmup 1 --csv results.csv
```

Các script tự tìm root, hỗ trợ đường dẫn chứa dấu cách, ưu tiên root JAR của bản phát hành,
rồi JAR trong target, rồi demo-classes. Sau khi sửa source phải build lại; script không tự compile.
Nếu JAVA_HOME không hợp lệ, runner tìm `java` trên PATH. Dừng server bằng Ctrl+C.

Chạy Java trực tiếp (cũng dùng được trên Linux/macOS; chưa kiểm chứng trên hai OS đó):

```text
java -cp bomb-it-multiplayer.jar com.bombit.server.app.ServerApplication 8888
java -jar bomb-it-multiplayer.jar 127.0.0.1 8888 Mai
java -cp bomb-it-multiplayer.jar com.bombit.tools.NetworkBenchmark --help
```

Hướng dẫn này không yêu cầu mở GUI trong môi trường kiểm thử tự động.
Để chơi nhiều máy, xem [LAN_GUIDE.md](LAN_GUIDE.md); kịch bản trình diễn tại [DEMO_SCRIPT.md](DEMO_SCRIPT.md).
