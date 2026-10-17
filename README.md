# gdal4k

GDAL 3.12.4를 내장한 Kotlin Multiplatform 라이브러리입니다. JVM, Android, Kotlin/Native, JS, wasmJs에서 같은 `suspend` API를 사용합니다. GDAL 설치, 데이터 디렉터리 지정, 웹 엔진 CDN 설정은 필요하지 않습니다. WASI는 제외합니다.

JVM 기본 의존성에는 바이너리가 없습니다. 아래 플랫폼 좌표 중 필요한 것만 명시적으로 추가합니다. 선택한 JAR에 GDAL·JXL·PROJ 데이터가 포함되므로 별도 설치나 경로 설정은 필요하지 않습니다.

Android는 API 28 이상, `arm64-v8a`만 지원합니다. Android x86/x86_64와 32비트 ARM은 지원하지 않습니다.

```kotlin
commonMain.dependencies {
    implementation("dev.tronto.gdal4k:gdal4k:3.12.4-1.0.1-SNAPSHOT")
}
```

일반 JVM 앱의 예시입니다(Linux x64/glibc).

```kotlin
dependencies {
    implementation("dev.tronto.gdal4k:gdal4k:3.12.4-1.0.1-SNAPSHOT")
    runtimeOnly("dev.tronto.gdal4k:gdal4k-binary-linux-x64-gnu:3.12.4-1.0.1-SNAPSHOT")
}
```

| JVM 실행 환경 | 선택할 아티팩트 (`dev.tronto.gdal4k`) |
| --- | --- |
| Linux x64 / glibc (Ubuntu 등) | `gdal4k-binary-linux-x64-gnu` |
| Linux ARM64 / glibc | `gdal4k-binary-linux-arm64-gnu` |
| macOS Intel | `gdal4k-binary-macos-x64` |
| macOS Apple Silicon | `gdal4k-binary-macos-arm64` |
| Windows x64 | `gdal4k-binary-windows-x64` |

KMP 앱에서는 같은 `runtimeOnly`를 `jvmMain.dependencies`에 추가합니다. 모든 좌표는 `gdal4k`와 같은 버전을 사용합니다. 여러 OS로 배포하는 앱은 필요한 좌표를 여러 개 추가할 수 있고, 런타임은 현재 OS/CPU에 맞는 리소스만 로드합니다. 미선택 또는 다른 플랫폼용만 선택했다면 `runtime()`에서 필요한 좌표를 안내하며 실패합니다. 자동 다운로드나 시스템 GDAL fallback은 없습니다. Alpine/musl은 지원하지 않습니다.

이 좌표는 이번 구현의 게시 대상 버전입니다. 아직 게시하지 않은 버전은 [개발·검증 절차](gdal4k-binary/README.md)에 따라 로컬 Maven 저장소에서 사용합니다.

```kotlin
import dev.gdal4k.Gdal4k
import dev.gdal4k.runtime.*

suspend fun describe(bytes: ByteArray): String {
    val gdal = Gdal4k.runtime() // 첫 호출에서 자동 초기화
    return gdal.openDataset("input.tif", bytes).use { dataset ->
        gdal.datasetInfo(dataset, DatasetInfoOptions(listOf("-json")))
    }
}
```

`runtime()`은 `suspend` 함수이며 첫 호출에서 초기화하고 이후 같은 런타임을 반환합니다. 별도 `prepare()`는 선택적인 사전 로딩용입니다. 데이터셋은 `use`로 닫아 쓰기를 완료하고 자원을 정리합니다. 전역 `close()`는 앱 실행 중 엔진 컨텍스트·Worker를 명시적으로 반환할 때 선택적으로 호출합니다. 앱 종료 훅으로 반드시 호출할 필요는 없으며, 공유 런타임을 작업마다 닫지 않습니다. 명시적으로 닫은 뒤 다시 `runtime()`을 호출하면 새로 초기화됩니다.

- `gdal4k-runtime`: 기존 `GdalRuntime`, `Dataset`, `Band`, OGR·SRS 인터페이스와 공통 파일 값 타입·편의 함수. GDAL을 호출하는 메서드는 `suspend`입니다.
- `gdal4k-binary`: 인터페이스의 플랫폼별 구현. JVM에는 코드만, Native·웹에는 해당 타깃 엔진을 포함합니다.
- `gdal4k-binary-<platform>`: JVM 앱이 선택하는 리소스 전용 JAR 5종. 소스의 `gdal4k-jvm-binaries` 모듈에서 게시합니다.
- `gdal4k`: 공통 진입점과 통합 테스트. `gdal4k-binary`를 전이 의존성으로 제공합니다.
- `gdal4k-android-runtime`: JNI 바이너리를 전달하는 Android AAR.

바이트 입력도 `runtime.openDataset("input.tif", bytes)`로 열고 기존 `Dataset`을 사용합니다. `translate`·`warp`·`translateVector`는 이 데이터셋의 메서드입니다.

[API·비동기 실행·지원 범위](gdal4k/README.md), [빌드·게시 산출물](gdal4k-binary/README.md), [별도 JVM 소비 앱](sample-cli/README.md)을 참고하세요. `verification`의 초기 접속성 probe는 과거 진단용이며, 제품 검증은 실제 라이브러리의 `commonTest`와 `verification/consumer.py`를 사용합니다.
