# gdal4k

GDAL 3.12.4를 내장한 Kotlin Multiplatform 라이브러리입니다. JVM, Android, Kotlin/Native, JS, wasmJs에서 같은 `suspend` API를 사용합니다. GDAL 설치, 데이터 디렉터리 지정, 웹 엔진 CDN 설정은 필요하지 않습니다. WASI는 제외합니다.

JVM 기본 의존성에는 바이너리가 없습니다. 아래 플랫폼 좌표 중 필요한 것만 명시적으로 추가합니다. 선택한 JAR에 GDAL·JXL·PROJ 데이터가 포함되므로 별도 설치나 경로 설정은 필요하지 않습니다.

Android는 API 28 이상, `arm64-v8a`만 지원합니다. Android x86/x86_64와 32비트 ARM은 지원하지 않습니다.

Apple 타깃은 `macosArm64`, `iosArm64`, `iosSimulatorArm64`입니다. macOS와 iOS 시뮬레이터는 Apple Silicon만 지원합니다. 실제 iPhone·iPad용 `iosArm64`와 시뮬레이터용 `iosSimulatorArm64`는 SDK·플랫폼이 달라 별도 바이너리를 제공하며, Gradle이 소비 앱의 타깃에 맞는 아티팩트를 선택합니다.

```kotlin
commonMain.dependencies {
    implementation("dev.tronto.gdal4k:gdal4k:0.1.0-SNAPSHOT")
}
```

일반 JVM 앱의 예시입니다(Linux x64/glibc).

```kotlin
dependencies {
    implementation("dev.tronto.gdal4k:gdal4k:0.1.0-SNAPSHOT")
    runtimeOnly("dev.tronto.gdal4k:gdal4k-binary-linux-x64-gnu:0.1.0-SNAPSHOT")
}
```

| JVM 실행 환경 | 선택할 아티팩트 (`dev.tronto.gdal4k`) |
| --- | --- |
| Linux x64 / glibc (Ubuntu 등) | `gdal4k-binary-linux-x64-gnu` |
| Linux ARM64 / glibc | `gdal4k-binary-linux-arm64-gnu` |
| macOS Apple Silicon | `gdal4k-binary-macos-arm64` |
| Windows x64 | `gdal4k-binary-windows-x64` |

KMP 앱에서는 같은 `runtimeOnly`를 `jvmMain.dependencies`에 추가합니다. 모든 아티팩트는 같은 라이브러리 버전 `0.1.0-SNAPSHOT`을 사용합니다. GDAL 버전은 빌드 설정과 엔진 manifest에서 별도로 관리합니다. 여러 OS로 배포하는 앱은 필요한 좌표를 여러 개 추가할 수 있고, 런타임은 현재 OS/CPU에 맞는 리소스만 로드합니다. 미선택 또는 다른 플랫폼용만 선택했다면 `runtime()`에서 필요한 좌표를 안내하며 실패합니다. 자동 다운로드나 시스템 GDAL fallback은 없습니다. Alpine/musl은 지원하지 않습니다.

모든 플랫폼에서 `gdal4k`만 의존하면 GDAL 엔진을 포함하지 않습니다. Android, Native, JS, wasmJs도 엔진을 명시적으로 추가합니다.

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("dev.tronto.gdal4k:gdal4k:0.1.0-SNAPSHOT")
        }
        androidMain.dependencies {
            implementation("dev.tronto.gdal4k:gdal4k-android-runtime:0.1.0-SNAPSHOT")
        }
        nativeMain.dependencies {
            implementation("dev.tronto.gdal4k:gdal4k-engine:0.1.0-SNAPSHOT")
        }
        jsMain.dependencies {
            implementation("dev.tronto.gdal4k:gdal4k-engine:0.1.0-SNAPSHOT")
        }
        wasmJsMain.dependencies {
            implementation("dev.tronto.gdal4k:gdal4k-engine:0.1.0-SNAPSHOT")
        }
    }
}
```

위 예시에서는 앱에서 사용하는 타깃의 소스셋만 설정합니다. `gdal4k-engine`은 Gradle이 선택한 Native 플랫폼의 정적 라이브러리 또는 웹 Worker·Wasm 자산을 제공합니다. JVM은 위 표의 리소스 JAR을, Android는 ARM64 JNI AAR을 선택합니다. 선택 후에는 모든 플랫폼에서 `Gdal4k.runtime()`으로 자동 로딩하며 설치 경로나 초기화 API는 추가하지 않습니다. Native 실행 파일 링크와 웹 번들에는 선택한 엔진이 필요합니다.

main CI는 전체 검증이 통과하면 Maven Central을 기본 대상으로 게시합니다. 정식 버전은 서명·업로드·최종 공개까지 자동으로 진행하고 `PUBLISHED` 상태를 확인합니다. 현재 `SNAPSHOT` 버전은 Sonatype Snapshot 저장소에 게시되므로 소비 앱에도 다음 저장소를 추가합니다. 아직 게시하지 않은 버전은 [개발·검증 절차](gdal4k-binary/README.md)에 따라 로컬 Maven 저장소에서 사용합니다.

```kotlin
repositories {
    mavenCentral()
    maven("https://central.sonatype.com/repository/maven-snapshots/") {
        content { includeGroup("dev.tronto.gdal4k") }
    }
}
```

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
- `gdal4k-binary-<platform>`: JVM 앱이 선택하는 리소스 전용 JAR 4종. 소스의 `gdal4k-jvm-binaries` 모듈에서 게시합니다.
- `gdal4k`: 공통 진입점과 통합 테스트. `gdal4k-binary`를 전이 의존성으로 제공합니다.
- `gdal4k-android-runtime`: JNI 바이너리를 전달하는 Android AAR.

웹에서는 URL을 바로 열면 HTTP Range로 필요한 구간만 읽습니다. 브라우저에서 선택한 `File`도 `jsMain` / `wasmJsMain`의 `runtime.openDataset(file)`로 열 수 있습니다. Node의 로컬 경로는 디스크를 직접 읽습니다. 기존 바이트 입력은 이미 메모리에 있는 데이터에 사용합니다.

바이트 입력도 `runtime.openDataset("input.tif", bytes)`로 열고 기존 `Dataset`을 사용합니다. `translate`·`warp`·`translateVector`는 이 데이터셋의 메서드입니다.

[API·비동기 실행·지원 범위](gdal4k/README.md), [빌드·게시 산출물](gdal4k-binary/README.md), [별도 JVM 소비 앱](sample-cli/README.md)을 참고하세요. `verification`의 초기 접속성 probe는 과거 진단용이며, 제품 검증은 실제 라이브러리의 `commonTest`와 `verification/consumer.py`를 사용합니다.
