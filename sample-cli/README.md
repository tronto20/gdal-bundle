# JVM 소비 예제

별도 JVM 프로젝트가 게시된 `gdal4k`와 명시적으로 선택한 플랫폼 바이너리를 사용하는 예제입니다. GDAL 작업은 `suspend`이며 CLI의 `suspend main`에서 호출합니다. GDAL 설치나 경로 지정은 필요하지 않습니다.

`runtime()`의 첫 호출이 자동으로 초기화합니다. CLI 프로세스 종료용 전역 `close()` 훅은 요구하지 않습니다. 데이터셋은 `use`로 닫습니다.

macOS ARM64에서 로컬 검증 아티팩트를 준비한 뒤 실행하는 예시입니다.

```sh
python3 verification/consumer.py --target jvm --jvm-binary macos-arm64
./gradlew -p sample-cli -Pgdal4kJvmBinary=macos-arm64 -Pgdal4kPackagesUrl="$PWD/verification/build/consumer-maven" run --args='smoke'
```

`gdal4kJvmBinary`는 필수이며 기본값이 없습니다. `macos-arm64`, `linux-x64-gnu`, `linux-arm64-gnu`, `windows-x64` 중 실행할 플랫폼을 선택합니다. Linux는 glibc용이며 Alpine/musl은 지원하지 않습니다. 선택한 좌표만 `runtimeOnly`로 들어갑니다. 다른 앱은 같은 의존성을 Gradle에 직접 선언하면 됩니다([설정 예시](../README.md)).

`verification/consumer.py`는 외부 API·JXL 왕복을 별도로 검사합니다. CI는 플랫폼별 리소스 JAR 5개를 각각 게시하고 실제 호스트에서 소비합니다. 이 예제의 jpackage installer는 해당 OS에서 별도로 검증해야 합니다.
