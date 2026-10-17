# gdal4k

GDAL 3.12.4를 내장한 Kotlin Multiplatform 라이브러리입니다. JVM, Android, Kotlin/Native, JS, wasmJs에서 같은 `suspend` API를 사용합니다. GDAL 설치, 데이터 디렉터리 지정, 웹 엔진 CDN 설정은 필요하지 않습니다. WASI는 제외합니다.

JVM 기본 의존성에는 바이너리가 없습니다. 아래 플랫폼 좌표 중 필요한 것만 명시적으로 추가합니다. 선택한 JAR에 GDAL·JXL·PROJ 데이터가 포함되므로 별도 설치나 경로 설정은 필요하지 않습니다.

Android는 API 28 이상, `arm64-v8a`만 지원합니다. Android x86/x86_64와 32비트 ARM은 지원하지 않습니다.

Apple 타깃은 `macosArm64`, `iosArm64`, `iosSimulatorArm64`입니다. macOS와 iOS 시뮬레이터는 Apple Silicon만 지원합니다. 실제 iPhone·iPad용 `iosArm64`와 시뮬레이터용 `iosSimulatorArm64`는 SDK·플랫폼이 달라 별도 바이너리를 제공하며, Gradle이 소비 앱의 타깃에 맞는 아티팩트를 선택합니다.

```kotlin
commonMain.dependencies {
    implementation("dev.tronto.gdal4k:gdal4k-runtime:0.1.0-SNAPSHOT")
    implementation("dev.tronto.gdal4k:gdal4k-engine:0.1.0-SNAPSHOT")
}
```

일반 JVM 앱의 예시입니다(Linux x64/glibc).

```kotlin
dependencies {
    implementation("dev.tronto.gdal4k:gdal4k-runtime:0.1.0-SNAPSHOT")
    runtimeOnly("dev.tronto.gdal4k:gdal4k-engine:0.1.0-SNAPSHOT:linux-x64-gnu")
}
```

| JVM 실행 환경 | `gdal4k-engine`의 classifier |
| --- | --- |
| Linux x64 / glibc (Ubuntu 등) | `linux-x64-gnu` |
| Linux ARM64 / glibc | `linux-arm64-gnu` |
| macOS Apple Silicon | `macos-arm64` |
| Windows x64 | `windows-x64` |

KMP 앱에서는 같은 `runtimeOnly`를 `jvmMain.dependencies`에 추가합니다. 모든 아티팩트는 같은 라이브러리 버전 `0.1.0-SNAPSHOT`을 사용합니다. GDAL 버전은 빌드 설정과 엔진 manifest에서 별도로 관리합니다. 여러 OS로 배포하는 앱은 필요한 좌표를 여러 개 추가할 수 있고, 런타임은 현재 OS/CPU에 맞는 리소스만 로드합니다. 미선택 또는 다른 플랫폼용만 선택했다면 `runtime()`에서 필요한 좌표를 안내하며 실패합니다. 자동 다운로드나 시스템 GDAL fallback은 없습니다. Alpine/musl은 지원하지 않습니다.

`gdal4k-runtime`은 엔진을 포함하지 않습니다. KMP 앱은 `commonMain`에서 runtime과 엔진 루트를 선언하면 Native·Android·JS·wasmJs에 맞는 엔진을 Gradle이 선택합니다. JVM은 배포할 OS·CPU·ABI를 classifier로 추가합니다.

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("dev.tronto.gdal4k:gdal4k-runtime:0.1.0-SNAPSHOT")
            implementation("dev.tronto.gdal4k:gdal4k-engine:0.1.0-SNAPSHOT")
        }
        jvmMain.dependencies {
            runtimeOnly("dev.tronto.gdal4k:gdal4k-engine:0.1.0-SNAPSHOT:macos-arm64")
        }
    }
}
```

`gdal4k-engine`은 Native 타깃별 정적 라이브러리, 웹 Worker·Wasm 자산 또는 Android ARM64 JNI AAR을 선택합니다. JVM의 기본 variant에는 바이너리가 없으며 classifier를 명시해야 합니다. 복수 classifier를 추가하면 JVM 결과물에 선택한 OS 바이너리가 모두 포함됩니다. Compose Desktop의 플랫폼별 패키지에는 배포 대상 classifier 하나를 선택합니다. Compose에 의한 GDAL 바이너리 자동 제거는 가정하지 않습니다.

Android 엔진은 `arm64-v8a`만 제공하며 초기화 시 ARM64 실행을 지원하지 않는 환경과 32비트 프로세스를 거절합니다. 소비 앱의 ABI 설정은 AAR에서 강제할 수 없으므로 앱 배포 대상도 ARM64로 제한합니다. x86_64 네이티브 엔진은 제공하지 않습니다.

선택 후에는 모든 플랫폼에서 `Gdal4k.runtime()`으로 자동 로딩하며 GDAL 설치나 경로 설정이 필요하지 않습니다. 각 Native 타깃은 자기 플랫폼 엔진만 링크합니다. 여러 타깃을 빌드하면 필요한 각 엔진을 받지만 하나의 결과물에 모든 Native 엔진을 넣지는 않습니다.

main CI는 전체 검증이 통과하면 Maven Central을 기본 대상으로 게시합니다. 정식 버전은 서명·업로드·최종 공개까지 자동으로 진행하고 `PUBLISHED` 상태를 확인합니다. 현재 `SNAPSHOT` 버전은 Sonatype Snapshot 저장소에 게시되므로 소비 앱에도 다음 저장소를 추가합니다. 아직 게시하지 않은 버전은 [개발·검증 절차](gdal4k-engine/README.md)에 따라 로컬 Maven 저장소에서 사용합니다.

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

- `gdal4k`: `GdalRuntime`, `Dataset`, `Band`, OGR·SRS 인터페이스와 공통 파일 값 타입·편의 함수. 구현·엔진에 의존하지 않습니다.
- `gdal4k-engine:<version>:<classifier>`: JVM 앱이 선택하는 리소스 JAR 4종. `gdal4k-jvm-binaries`에서 만들고 엔진 루트 좌표의 classifier로 게시합니다.
- `gdal4k-runtime`: `Gdal4k.runtime()` 진입점, 플랫폼별 Kotlin 구현·로더와 통합 테스트. `gdal4k` 인터페이스를 전이 의존성으로 제공하며 모든 플랫폼에서 엔진을 포함하지 않습니다.
- `gdal4k-engine`: Native·Android·웹 엔진을 선택하는 KMP 루트 좌표. JVM은 classifier를 추가합니다. 타깃별 실제 게시 이름은 [엔진 좌표 표](gdal4k-engine/README.md#산출물과-게시)를 참고하세요.
- `gdal4k-engine-android-arm64`: JNI 바이너리를 전달하는 Android AAR.

웹에서는 URL을 바로 열면 HTTP Range로 필요한 구간만 읽습니다. 브라우저에서 선택한 `File`도 `jsMain` / `wasmJsMain`의 `runtime.openDataset(file)`로 열 수 있습니다. Node의 로컬 경로는 디스크를 직접 읽습니다. 기존 바이트 입력은 이미 메모리에 있는 데이터에 사용합니다.

바이트 입력도 `runtime.openDataset("input.tif", bytes)`로 열고 기존 `Dataset`을 사용합니다. `translate`·`warp`·`translateVector`는 이 데이터셋의 메서드입니다.

[API·비동기 실행·지원 범위](gdal4k-runtime/README.md), [빌드·게시 산출물](gdal4k-engine/README.md), [별도 JVM 소비 앱](sample-cli/README.md)을 참고하세요. `verification`의 초기 접속성 probe는 과거 진단용이며, 제품 검증은 실제 라이브러리의 `commonTest`와 `verification/consumer.py`를 사용합니다.

### Band 색상표·RAT·블록 접근

`runtime.createColorTable()`은 수정 가능한 색상표를 만듭니다. `band.getColorTable()`은
Band와 수명이 분리된 복사본을 반환하므로 변경을 반영하려면 `band.setColorTable(table)`을
호출합니다. `use` 또는 `close()`로 소유한 색상표를 해제합니다.

`runtime.createRasterAttributeTable()`은 Integer/Real/String 열을 가진 RAT를 만듭니다.
`readIntegerValues(column, startRow, count)` 등의 열 구간 API는 요청한 행만 읽습니다.
`band.getDefaultRAT()`은 전체 표를 복사하지 않는 참조입니다. Band/Dataset이 닫히거나
`setDefaultRAT`으로 표를 교체하면 기존 참조는 무효화됩니다. 독립 사본은 `cloneTable()`로
명시적으로 만듭니다. 변경의 파일 반영 여부는 `changesAreWrittenToFile()`과 드라이버 정책을
따르며, 자동 반영되지 않는 표는 `setDefaultRAT`으로 다시 연결합니다. Boolean/DateTime/WKB의
전용 RAT 접근은 아직 노출하지 않습니다. 값 읽기·쓰기는 열의 형식과 일치하는 메서드를
사용하며 암시적인 문자열/숫자 변환을 하지 않습니다.

`readBlock`/`writeBlock`은 변환 없는 **원래 픽셀 형식의 바이트**를 사용합니다. 버퍼는
`getBlockSize()`의 폭 × 높이 × 픽셀 바이트 수를 담아야 하며 다중 바이트 값은 네이티브
바이트 순서를 따릅니다. `getActualBlockSize`로 가장자리 유효 영역을 구할 수 있고,
읽을 때 유효 영역 밖 패딩은 0으로 채웁니다. 큰 버퍼의 나머지는 보존합니다.
타입 변환이나 리샘플링에는 기존 `readRaster`/`writeRaster`를 사용합니다. 직접 블록 접근
전에 대기 중인 RasterIO 쓰기를 flush하며, 직접 쓰기는 기존 블록 캐시도 비웁니다.
