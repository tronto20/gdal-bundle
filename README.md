# gdal4k

GDAL 3.12.4를 내장한 Kotlin Multiplatform 라이브러리입니다. JVM, Android, Kotlin/Native, JS, wasmJs에서 같은 `suspend` API를 사용합니다. GDAL 설치, 데이터 디렉터리 지정, 웹 엔진 CDN 설정은 필요하지 않습니다. WASI는 제외합니다.

Android는 API 28 이상, `arm64-v8a`와 `x86_64`만 지원합니다. Android 32비트는 지원하지 않습니다.

```kotlin
commonMain.dependencies {
    implementation("dev.tronto.gdal4k:gdal4k:3.12.4-1.0.1-SNAPSHOT")
}
```

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
- `gdal4k-binary`: 인터페이스의 플랫폼별 구현, JNI·정적 Native·자체 Wasm 엔진과 데이터.
- `gdal4k`: 공통 진입점과 통합 테스트. `gdal4k-binary`를 전이 의존성으로 제공합니다.
- `gdal4k-android-runtime`: JNI 바이너리를 전달하는 Android AAR.

바이트 입력도 `runtime.openDataset("input.tif", bytes)`로 열고 기존 `Dataset`을 사용합니다. `translate`·`warp`·`translateVector`는 이 데이터셋의 메서드입니다.

[API·비동기 실행·지원 범위](gdal4k/README.md), [빌드·게시 산출물](gdal4k-binary/README.md), [별도 JVM 소비 앱](sample-cli/README.md)을 참고하세요. `verification`의 초기 접속성 probe는 과거 진단용이며, 제품 검증은 실제 라이브러리의 `commonTest`와 `verification/consumer.py`를 사용합니다.
