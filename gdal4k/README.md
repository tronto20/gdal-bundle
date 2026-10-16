# gdal4k 공통 라이브러리

`dev.tronto.gdal4k:gdal4k`의 공통 `suspend` API를 JVM, Android, Kotlin/Native, JS, wasmJs에서 사용합니다. WASI는 제외합니다.

```kotlin
import dev.gdal4k.*

suspend fun convert(input: ByteArray, options: GdalOptions): ByteArray =
    Gdal.create(options).use { gdal ->
        gdal.open(GdalFile("input.tif", input)).use { dataset ->
            println(dataset.info()) // GDAL JSON: projection, geotransform, NoData, metadata
            val values: DoubleArray = dataset.readRaster(band = 1)
            dataset.translate(driver = "COG").mainFile().bytes
        }
    }
```

공통 기능:

- 메모리 파일 열기, 다중 파일 입력/출력 (`GdalFiles`), 드라이버 목록 조회
- 래스터/벡터 JSON 정보, 밴드/윈도우 픽셀 읽기
- `translate`, `warp`, `translateVector`: GDAL의 옵션을 `arguments`로 전달
- CRS 좌표 변환: 모든 플랫폼에서 longitude/easting, latitude/northing 순서
- 세션/데이터셋의 명시적 종료와 `use`; 부모 종료 시 자식도 종료
- 호출 직렬화, 세션별 입력 파일 분리, 오류 전파와 실패 후 재사용

사용 가능한 드라이버는 `drivers()`로 확인합니다. 모든 플랫폼의 GDAL 빌드가 같은 선택 의존성을 포함하는 것은 아닙니다. 입력과 출력은 메모리 기반이며, Base64 전송과 픽셀 읽기의 XYZ 변환 비용이 있습니다. 대용량 스트리밍과 전체 GDAL C 객체 API는 이 공통 API의 범위에 포함하지 않습니다.

기존 `gdal4k-runtime` / `gdal4k-binary`의 `Gdal4kBinary.runtime()` 및 JVM 객체 래퍼는 호환성을 위해 유지합니다. 그 전체 객체 API를 웹용 stub으로 구현하지 않습니다. 새 공통 코드는 위의 `dev.gdal4k.Gdal`을 사용합니다.

## JVM / Native

GDAL 3.7 이상과 이 디렉터리의 C bridge를 링크합니다. JVM은 GDAL Java/SWIG 바인딩을 요구하지 않습니다.

```sh
cmake -S gdal4k/native -B gdal4k/build/bridge -G Ninja -DCMAKE_BUILD_TYPE=Release
cmake --build gdal4k/build/bridge --parallel 3
```

JVM: `GdalOptions(libraryPath = "/absolute/path/libgdal4k.so", projData = "/.../share/proj", gdalData = "/.../share/gdal")`. 확장자는 macOS `.dylib`, Windows `.dll`입니다. 기본값은 `System.loadLibrary("gdal4k")`입니다. GDAL 공유 라이브러리와 그 의존성도 시스템 로더가 찾을 수 있어야 합니다.

Native: `Gdal.create(GdalOptions(projData = ..., gdalData = ...))`. 배포하는 klib는 `-lgdal4k` 링크를 요구합니다. 앱 링크에 bridge 디렉터리의 `-L`과 런타임 검색 경로를 지정하거나 CMake `install`로 검색 가능한 곳에 설치합니다. iOS는 해당 SDK/아키텍처로 빌드한 GDAL/bridge와 데이터가 필요합니다. 저장소 테스트는 `-Pgdal4kBridgePrefix=/absolute/path`를 사용합니다.

선언한 Native 타깃: macosArm64/X64, linuxX64/Arm64, mingwX64, iosArm64/SimulatorArm64/X64. 실행 검증과 단순 타깃 선언은 구분하며, 최신 실행 결과는 PR/CI를 확인합니다.

## Android

NDK로 GDAL과 bridge를 빌드한 뒤 다음 스크립트로 ABI별 JNI와 데이터를 준비합니다. GDAL의 CMake 패키지/의존성 탐색에는 vcpkg toolchain 또는 `CMAKE_PREFIX_PATH`를 사용할 수 있습니다.

```sh
python gdal4k/tools/package-android.py \
  --abi arm64-v8a --bridge /path/libgdal4k.so --ndk /path/android-ndk \
  --proj-data /path/share/proj --gdal-data /path/share/gdal
./gradlew -PportableOnly=true :gdal4k:assembleAndroidMain :gdal4k-android-runtime:assembleRelease
```

`gdal4k` Android AAR는 JNI/데이터 AAR인 `gdal4k-android-runtime`을 전이 의존합니다. NDK r28 이상 권장, 최소 Android API 28. 다른 ABI는 그 ABI로 GDAL/bridge를 빌드하고 스크립트를 반복 실행해 추가합니다. 준비되지 않은 JNI/데이터로 runtime AAR를 패키징하면 실패합니다.

Android 초기화만 `GdalAndroid.options(context)`를 호출하고, 결과를 공통 코드의 `Gdal.create(options)`로 전달합니다. `GdalAndroid.create(context)` 단축 API도 제공합니다. 데이터는 무결성을 검사해 앱 전용 디렉터리에 추출하며 저장소 권한이 필요하지 않습니다.

## JS / wasmJs (브라우저 / Node)

```sh
./gradlew -PportableOnly=true :gdal4k:webRuntime
```

`gdal4k/build/distributions/gdal4k-web.zip`에 adapter, Worker, 고정된 `gdal3.js@2.8.1`의 JS/Wasm/data를 함께 넣습니다. 브라우저는 압축을 풀어 같은 origin에서 정적 제공합니다. Wasm MIME은 `application/wasm`으로 설정합니다. Worker와 동적 모듈 import가 허용되어야 합니다.

```kotlin
val options = GdalOptions(
    webBridgeUrl = "https://example.com/gdal/gdal4k-web.mjs",
    webAssetsUrl = "https://example.com/gdal/", // 마지막 / 포함
)
```

Node는 압축 해제한 같은 파일들의 절대 `file:` URL을 전달합니다. 세션마다 별도 Worker를 사용하고 `close()`에서 종료합니다. Kotlin의 `ByteArray`를 넘기므로 브라우저 File/Node 파일 경로 API가 공통 코드에 노출되지 않습니다.

## 검증

```sh
cd verification && npm ci && npx playwright install chromium && cd ..
python gdal4k-contract/run-local.py
```

별도 consumer 모듈 `gdal4k-contract`가 실제 라이브러리 의존성을 사용합니다. GeoTIFF/COG 재열기·픽셀·NoData·geotransform, 윈도우 읽기, 재투영, 좌표 왕복, Shapefile 보조 파일, 중복 이름, Unicode 파일명, 잘못된 드라이버/CRS/파일/옵션, 종료 후 사용을 같은 Kotlin 코드로 검증합니다. `.github/workflows/library-contract.yml`에서 데스크톱 3종과 iOS Simulator를 실행합니다.

Android APK 검증: `:gdal4k-android-test:assembleDebug` 후 테스트 장치에 설치하고 `adb shell am instrument -w dev.gdal4k.contract.app/.ContractInstrumentation`. 종료 코드뿐 아니라 `GDAL4K_LIBRARY_CONTRACT_OK`를 확인합니다. `GdalAndroid`의 데이터 추출과 전이 AAR 패키징도 이 경로에서 실행합니다.

`-PportableOnly=true`는 기존 conda/JVM 번들 모듈을 제외하여 공통 라이브러리만 빌드하는 옵션입니다. Maven 공개 배포는 별도 작업입니다.
