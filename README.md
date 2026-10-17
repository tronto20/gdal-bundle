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

### 여러 입력의 VRT·Warp

```kotlin
runtime.buildVRT(listOf(left, right)).use { mosaic ->
    mosaic.translateTo("mosaic.tif", driver = "COG")
}
runtime.warpTo(listOf(left, right), "projected.tif", target = "EPSG:3857")
val files = runtime.warp(listOf(left, right), target = "EPSG:3857")
```

`buildVRT`는 픽셀을 복사하지 않는 live Dataset을 반환합니다. 입력 핸들을 먼저 닫아도
VRT가 사용하는 네이티브 Dataset, 메모리 작업공간, Android URI descriptor·directory, 웹 File/HTTP/Node 입력 mount는 유지됩니다.
마지막 의존 VRT를 닫으면 함께 해제됩니다. 원본 파일 자체를 외부에서 삭제하거나 변경하는
동작까지 막지는 않습니다. 임시 원본 경로가 들어간 XML을 독립적인 결과물로 반환하지 않습니다.
입력 불일치는 기본 `-strict`로 실패하며, GDAL의 입력 건너뛰기는 `-non_strict`를 명시해야 합니다.

다중 입력 Warp는 목록 순서대로 처리하며 겹치는 곳은 뒤 입력의 유효 픽셀이 우선합니다.
NoData/마스크·해상도·extent·리샘플링은 GDAL 옵션을 `arguments`로 전달합니다. `warp`는
결과 파일을 메모리에 반환하고 `warpTo`는 경로에 직접 기록합니다. 큰 결과는 `warpTo`를
사용합니다. 웹의 직접 출력은 Node 파일 경로 또는 브라우저 `opfs:/`를 사용합니다.
이 두 Warp API는 실제 파일 형식에 기록하며 VRT/MEM 출력은 지원하지 않습니다.
실패·취소 후 부분 출력 파일은 호출자가 정리합니다. 기존 단일 Dataset 변환 API는 유지됩니다.
