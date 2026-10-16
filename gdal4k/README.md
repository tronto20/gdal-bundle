# 공통 API

`gdal4k-runtime`의 기존 GDAL 객체 인터페이스를 모든 지원 타깃에 공개하고, `gdal4k-binary`가 구현합니다. 테스트 전용 인터페이스/contract 모듈은 없습니다. `gdal4k`는 두 모듈을 함께 제공하는 진입점입니다.

## 초기화와 수명

```kotlin
import dev.gdal4k.Gdal4k
import dev.gdal4k.runtime.*

suspend fun readBand(bytes: ByteArray): ByteArray {
    Gdal4k.prepare()
    try {
        val runtime = Gdal4k.runtime()
        runtime.writeMemoryFile("/vsimem/image.tif", bytes)
        return runtime.openDataset("/vsimem/image.tif").use { dataset ->
            val result = ByteArray(dataset.getRasterXSize() * dataset.getRasterYSize())
            requireNotNull(dataset.getRasterBand(1)).use { band ->
                check(band.readRaster(result) == 0)
            }
            result
        }
    } finally { Gdal4k.close() }
}
```

이 예시는 작은 Byte 래스터용입니다. 큰 영상은 밴드의 window 인자를 사용해 나눠 읽습니다. `GdalDataType`은 공통 GDAL 데이터 타입 상수를 제공합니다. JVM의 `org.gdal.*` SWIG 클래스에는 의존하지 않습니다.

기존 진입점 `Gdal4kBinary.prepare()` / `runtime()`도 유지합니다. 모든 GDAL 호출은 이제 `suspend`이므로 기존 동기 호출부는 coroutine 또는 `suspend` 함수로 옮겨야 합니다. `runtime()`처럼 준비된 Kotlin 객체를 가져오는 함수와 캐시된 데이터 프로퍼티는 동기 함수입니다.

## 실행과 취소

| 플랫폼 | GDAL 실행 위치 | 내장 방식 |
| --- | --- | --- |
| JVM | `Dispatchers.IO` | JAR의 OS/아키텍처별 JNI 라이브러리를 해시별 임시 디렉터리에 자동 추출 |
| Android | `Dispatchers.IO` | 전이 의존 AAR의 `jniLibs`를 Android가 설치·로드 |
| Native | `Dispatchers.Default` | 게시 KLIB 안의 GDAL·의존성 정적 archive를 최종 앱에 링크 |
| JS / wasmJs | 전용 Worker | 같은 Emscripten GDAL Wasm 모듈을 라이브러리 안에 압축 내장, Worker에서 해제·초기화 |

한 런타임의 GDAL 호출과 종료는 직렬화합니다. 다른 런타임 핸들을 섞으면 실패합니다. 데이터셋을 닫은 뒤 그 밴드·레이어 핸들을 쓰면 실패합니다. `use`의 정리는 취소된 coroutine에서도 실행합니다.

대기 중인 호출은 취소할 수 있습니다. 이미 시작한 GDAL C 호출은 중간에 강제 중단하지 않고 완료한 뒤 핸들/결과를 정리합니다. 따라서 coroutine 취소가 오래 걸리는 GDAL 연산을 즉시 멈추지는 않습니다. 웹 Worker를 강제로 종료해서 데이터셋 상태를 손상시키는 동작도 하지 않습니다.

JS와 wasmJs가 사용하는 **GDAL 엔진 자체는 일반 WebAssembly**입니다. JS 경로는 Kotlin/Wasm의 Wasm GC를 지원하지 않는 브라우저에서도 기본 Wasm·Worker·ES module을 지원하면 사용할 수 있습니다. WebAssembly 자체가 없는 환경에서는 동작하지 않습니다. wasmJs로 컴파일한 Kotlin 앱은 Kotlin/Wasm의 브라우저 요구 사항도 충족해야 합니다. Node는 `process.getBuiltinModule`을 제공하는 20.16+ / 22+가 필요합니다. 웹 CSP는 Blob Worker, 모듈과 Wasm 실행을 허용해야 합니다. 엔진은 pthread/SharedArrayBuffer를 요구하지 않습니다.

## 데이터와 드라이버

GDAL 3.12.4, PROJ의 `proj.db`, 전체 GDAL 데이터 파일과 종속 라이브러리의 라이선스 고지를 바이너리에 포함합니다. 외부 설치나 `GDAL_DATA` / `PROJ_DATA` 지정이 필요하지 않습니다. 선택적 지역 변환 grid 파일은 포함하지 않습니다.

공통 빌드 프로필은 GDAL 기본 드라이버와 GEOS, JPEG, PNG, SQLite, WebP, Zstd, Expat, OpenJPEG, JPEG XL입니다. JPEGXL 드라이버와 GTiff/COG의 `COMPRESS=JXL`을 검증합니다. **모든 GDAL 선택 드라이버를 포함한다는 뜻은 아닙니다.** HDF5/netCDF/Parquet/PostgreSQL 등의 추가 SDK와 CURL 기반 원격 VFS는 이 프로필에 포함하지 않습니다. 웹에서는 앱이 HTTP로 받은 바이트를 `writeMemoryFile`로 전달할 수 있습니다. 원래 Conda 배포와 드라이버 목록이 같다고 가정하지 마세요.

`runtime.versionInfo("THIRD_PARTY_LICENSES")`로 실제 포함된 고지를 조회합니다. 기능 감지는 `getDriver(name)`의 결과로 수행합니다.

## 보조 바이트 API

기존의 간단한 `Gdal.create().use { session -> ... }` / `GdalFile` / `GdalFiles`도 유지합니다. `GdalFile`은 파일 이름과 바이트, `GdalFiles`는 Shapefile 같은 다중 파일 묶음이며 별도의 GDAL 엔진이 아닙니다. Base64는 이 API의 내부 JSON 전송 구현에만 사용합니다.

`GdalOptions()` 기본값만으로 내장 엔진을 사용합니다. `libraryPath`, `projData`, `gdalData`, `webBridgeUrl`은 고급 호스트가 직접 런타임을 바꾸는 선택적 override입니다. 일반 사용에서 지정하지 않습니다.

## 검증

테스트는 `gdal4k/src/commonTest`에 있습니다. 원래 객체 API smoke, 래스터·벡터·좌표변환, JXL 왕복, 오류·핸들 수명, 취소·재초기화, 호출 스레드/웹 이벤트 루프 응답성을 검사합니다. `verification/consumer.py`는 별도 Gradle 프로젝트에서 게시 Maven 아티팩트만 받아 실행합니다. Native 소비 테스트는 원래 producer 디렉터리를 숨기고 링크합니다.

지원 타깃 선언과 실행 검증은 다릅니다. 최신 실행 결과는 PR의 플랫폼별 CI와 JUnit 보고서를 확인합니다. iOS 디바이스 타깃은 서명된 실제 기기가 없어 링크까지, iOS 시뮬레이터는 실제 실행까지 검사합니다. Android는 ABI 4종을 빌드하고 에뮬레이터에서 동작을 검사합니다. WASI는 타깃이나 테스트에 포함하지 않습니다.
