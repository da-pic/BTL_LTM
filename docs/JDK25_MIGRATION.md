# Chuyển baseline sang JDK 25

Baseline hiện hành: build và chạy bằng Java 25+, biên dịch với `--release 25`.
JAR mới không chạy được trên Java 17/21. Cả máy server và mọi máy client cần Java 25+.

## Cấu hình máy và IDE

JAVA_HOME phải trỏ đến thư mục gốc JDK, không phải thư mục bin. Ví dụ trên máy hiện tại:

```powershell
$env:JAVA_HOME='D:\JavaJDK'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
java -version
javac -version
mvn -version
```

Ba lệnh cần hiện Java/JDK 25. Nếu Maven không ở PATH, dùng đường dẫn Maven đã cài
hoặc đặt BOMBIT_MVN theo [PACKAGING.md](PACKAGING.md). Biến môi trường ví dụ chỉ áp dụng
cho terminal hiện tại. Khi cấu hình JAVA_HOME lâu dài trong Windows, mở lại terminal và IDE.

IntelliJ: chọn Project SDK 25, language level 25 và Maven runner/importer JDK 25.
VS Code: dùng **Java: Configure Java Runtime**, chọn JDK 25 cho project rồi reload Maven project.
Không cần ghi đường dẫn JDK riêng của một máy vào repository.

`java-env.cmd` dùng cùng JAVA_HOME cho java/javac và chuẩn hóa trường hợp trỏ nhầm vào bin
trong lần chạy script; không sửa biến môi trường Windows lâu dài.

## File cấu hình và tái build

- `pom.xml`: maven.compiler.release=25.
- `scripts/build.ps1`: javac release 25, BUILD_INFO target_java=25.
- `scripts/check-tv2.cmd`: javac release 25 và dùng java/javac từ cùng JAVA_HOME.
- Các script run/test/package dùng JDK/runtime do JAVA_HOME hoặc PATH chọn.
- README và hướng dẫn chạy/build/LAN/demo dùng baseline 25.

```powershell
cmd /c scripts\test.cmd clean
cmd /c scripts\package.cmd
```

Clean test tránh dùng class target 17 còn sót từ lần build trước. Package build lại production JAR,
ZIP và SHA256; phân phối lại cả server/client từ cùng ZIP mới. Bytecode Java 25 có major version 69.

Các số đo CSV và log nhiệm vụ trước chuyển đổi vẫn mô tả lần chạy cũ (JDK 25, target 17).
Không sửa lịch sử thành kết quả target 25; lần chuyển đổi được kiểm chứng riêng và ghi bên dưới.

## Kết quả kiểm chứng — 22/10/2026

- [x] Clean build/test trên Windows với JDK 25.0.1, Maven 3.9.12 offline:
  **112 test, 0 failure/error/skipped**, production/test cùng release 25.
- [x] Check/Demo TV2 và NetworkTest main đạt trên JDK 25.
- [x] Build production JAR với release 25; BUILD_INFO ghi target_java=25.
- [x] Class production, test, TV2 và demo có major version 69.
- [x] ZIP/SHA256 đã tạo lại; giải nén vào đường dẫn có dấu cách và chạy transport smoke đạt
  trên Java 25.0.1, cleanup=true; kiểm tra JAR trong ZIP có bytecode 69 và target_java=25.
- [x] Runner hoạt động khi JAVA_HOME trỏ nhầm vào bin nhờ helper chuẩn hóa trong lần chạy.
- [ ] GUI/LAN nhiều máy, soak dài hạn và OS ngoài Windows: cần nhóm kiểm chứng.

Các file thay đổi: pom.xml; scripts/build.ps1, check-tv2.cmd, java-env.cmd;
README và các yêu cầu JDK trong hướng dẫn build/LAN/demo/gameplay; tài liệu chuyển đổi này.
Script run-server/run-client/run-benchmark dùng helper Java chung nên lấy cùng JAVA_HOME.
Không sửa Java toàn hệ thống hoặc cấu hình SDK riêng của IDE; nhóm chọn JDK 25 theo hướng dẫn trên.

Log lần chuyển đổi: `jdk25-tests.log`, `jdk25-build.log` (workspace, được gitignore),
`target/jdk25-tests.log`. Log ở root cho phép Maven clean xóa target mà không gặp file log đang mở.
CSV đo trước đó vẫn là bằng chứng của bản target 17, không dùng để so sánh hiệu năng hai target.
Yêu cầu kiểm chứng JVM 17 trong checklist lịch sử không còn áp dụng cho baseline mới.
Bước tiếp theo: cấu hình SDK IDE 25, phân phối ZIP mới và nghiệm thu LAN với Java 25 trên mọi máy.
