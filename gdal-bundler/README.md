# gdal4k-binary

GDAL JNI 동적 라이브러리와 의존 라이브러리, 드라이버 플러그인, 데이터(`share/gdal`, `share/proj`)를
Conda 환경에서 추출해 앱 번들에 넣기 위한 모듈입니다. macOS에서는 `install_name_tool`로
의존 경로를 `@loader_path` 기반으로 패치하고, Linux에서는 동일한 소스 빌드 결과를 그대로
수집합니다. 배포 시에는 플랫폼별 classifier JAR로 내보내고, 그 안에 포함된 `txz`
아카이브를 JVM 런타임이 첫 사용 시 로컬 캐시에 압축 해제해 사용합니다.

## 요구 사항
- macOS (Apple Silicon) 또는 Linux (amd64/arm64)
- `bundleGdal`만 쓸 경우에는 Conda 환경에 GDAL + Java 바인딩 포함 (`libgdalalljni.*` 존재)
- 네이티브 C/C++ 컴파일러 툴체인 (`Xcode Command Line Tools` 또는 `build-essential`)

## 현재 비활성화한 GDAL 구성요소
아래 항목들은 현재 번들 크기, 의존성 안정성, 또는 Windows 링크 문제 때문에 의도적으로 비활성화한 상태입니다.
문제를 해결하거나 별도 검증이 끝나면 하나씩 다시 켤 수 있습니다.

| 범위 | 비활성화한 CMake 옵션 | 영향 |
| --- | --- | --- |
| 공통 (macOS / Linux / Windows) | `GDAL_USE_ARROW=OFF` | Arrow 기반 지원 |
| 공통 (macOS / Linux / Windows) | `GDAL_USE_PARQUET=OFF` | Parquet/columnar 관련 지원 |
| 공통 (macOS / Linux / Windows) | `GDAL_USE_SFCGAL=OFF` | SFCGAL 기반 지오메트리 지원 |
| 공통 (macOS / Linux / Windows) | `GDAL_USE_POPPLER=OFF` | Poppler 기반 PDF 지원 |
| 공통 (macOS / Linux / Windows) | `GDAL_USE_NETCDF=OFF` | NetCDF 드라이버 |
| Windows 추가 | `GDAL_USE_HDF4=OFF` | HDF4 드라이버 |
| Windows 추가 | `GDAL_USE_OPENEXR=OFF` | OpenEXR 드라이버 |
| Windows 추가 | `GDAL_USE_POSTGRESQL=OFF` | PostgreSQL/PostGIS 드라이버 |
| Windows 추가 | `GDAL_USE_MUPARSER=OFF` | muParser 기반 기능 |
| Windows 추가 | `GDAL_USE_XERCESC=OFF` | XercesC 기반 기능 |

macOS와 Linux는 공통 목록을 사용하며, NetCDF도 함께 끕니다.
Windows는 내부 `libopencad`와 LIBKML를 켜고, `libkml` 소스를 함께 빌드합니다.

## 사용 방법
### 빌드 + 번들링 (GDAL + LIBKML)
GDAL과 LIBKML을 소스 빌드한 뒤 번들링까지 한 번에 수행합니다. KMZ를 위해 LIBKML 드라이버가 포함됩니다.

```bash
./gradlew :gdal4k-binary:buildAndBundleGdal \
  --conda-prefix=/path/to/conda/env \
  --conda-exe=/path/to/conda
```

옵션:
- `--conda-prefix=/path/to/env`, `--conda-exe=/path/to/conda`: 기본값은 `CONDA_PREFIX`, `CONDA_EXE`
- `--work-dir=/path/to/work`: 소스/빌드 작업 경로 지정
- `--gdal-version=3.9.0`, `--libkml-version=1.3.0`: 버전 변경
- `--output-dir=/path/to/output`: 번들 출력 경로 지정
- `--codesign-identity="Developer ID Application: ..."`: 번들된 dylib 재서명 (또는 `CODESIGN_IDENTITY`)
- Docker/CI에서 prefix와 작업 디렉터리를 분리하고 싶으면 `GDAL4K_CONDA_INSTALL_DIR`, `GDAL4K_WORK_DIR`를 사용할 수 있습니다.

이 작업은 conda prefix에 GDAL/libkml을 설치(덮어쓰기)합니다. Windows에서도 `libkml`과 OpenCAD를 함께 빌드합니다.
`buildLibkml`, `buildGdal`, `buildAndBundleGdal`은 `installConda`와 `installGdalDeps`를 먼저 수행합니다.
기존 `*Macos` task는 호환용 alias로 남아 있습니다.

### 단계별 실행
필요한 단계만 따로 실행할 수 있습니다. 작업 디렉터리를 지우려면 `:gdal4k-binary:cleanGdalWorkDir`를 실행하세요.

```bash
./gradlew :gdal4k-binary:installGdalDeps \
  --conda-prefix=/path/to/conda/env \
  --conda-exe=/path/to/conda
```

```bash
./gradlew :gdal4k-binary:cleanGdalWorkDir \
  --work-dir=/path/to/work
```

```bash
./gradlew :gdal4k-binary:buildLibkml \
  --conda-prefix=/path/to/conda/env
```

```bash
./gradlew :gdal4k-binary:buildGdal \
  --conda-prefix=/path/to/conda/env
```

### 번들링만
```bash
./gradlew :gdal4k-binary:bundleGdal
```

Conda 환경이 활성화되지 않았다면 prefix를 지정하세요.
```bash
./gradlew :gdal4k-binary:bundleGdal \
  --conda-prefix=/path/to/conda/env
```

배포용으로 dylib를 재서명하려면:
```bash
./gradlew :gdal4k-binary:bundleGdal \
  --conda-prefix=/path/to/conda/env \
  --codesign-identity="Developer ID Application: ..."
```

출력 경로를 바꾸려면:
```bash
./gradlew :gdal4k-binary:bundleGdal \
  --output-dir=/path/to/output
```

기본 출력은 `gdal-bundler/build/gdal-bundle/<classifier>/gdal` 이고,
배포용 압축 파일은 내부적으로 `gdal-bundler/build/published-bundles/gdal4k-binary-<classifier>.txz` 에 생성됩니다.
최종 Maven 배포물인 `gdal4k-binary:<version>:<classifier>` JAR에는 이 `txz`와 함께
`gdal4k-runtime` JVM 클래스도 같이 들어가므로, 소비자는 `gdal4k-binary` 하나만 의존해도 됩니다.

## Compose Desktop 앱에 포함하기 (예시)
Compose Desktop(1.9.x 기준)는 `appResourcesRootDir` 아래의
`common/`, `macos/`, `macos-arm64/`를 읽어 macOS 패키지에
`Contents/app/resources`로 복사합니다.
그래서 GDAL 번들은 `macos/gdal`에 복사해야 합니다.

```kotlin
val gdalBundleDir = project(":gdal4k-binary").layout.buildDirectory.dir("gdal-bundle")
val gdalResourcesRoot = layout.buildDirectory.dir("app-resources")

val syncGdalResources by tasks.registering(Sync::class) {
    dependsOn(":gdal4k-binary:bundleGdal")
    from(gdalBundleDir.map { it.dir("macos-arm64/gdal") })
    into(gdalResourcesRoot.map { it.dir("macos/gdal") })
}

tasks.named("prepareAppResources") {
    dependsOn(syncGdalResources)
}

compose.desktop {
    application {
        nativeDistributions {
            appResourcesRootDir.set(gdalResourcesRoot)
        }
    }
}
```

## 런타임 설정 (Kotlin)
```kotlin
import java.io.File
import org.gdal.gdal.gdal

val resourcesDir = System.getProperty("compose.application.resources.dir")
val gdalDir = File(resourcesDir, "gdal")
System.load(File(gdalDir, "lib/libgdalalljni.dylib").absolutePath)

gdal.SetConfigOption("GDAL_DATA", File(gdalDir, "share/gdal").absolutePath)
gdal.SetConfigOption("PROJ_DATA", File(gdalDir, "share/proj").absolutePath)
val pluginsDir = File(gdalDir, "gdalplugins")
if (pluginsDir.exists()) {
    gdal.SetConfigOption("GDAL_DRIVER_PATH", pluginsDir.absolutePath)
}

gdal.AllRegister()
```

필요 시 `org.gdal:gdal` 의존성(버전은 기존 카탈로그)에 추가하세요.

`Gdal4kBinary.prepare()`는 기본적으로 JAR 안에 포함된 `gdal/gdal-bundle.txz` 를 찾습니다.
필요하면 `bundleDir` 인자나 `-Dgdal.bundle.dir` / `GDAL_BUNDLE_DIR` 로 압축 해제된 번들 디렉터리 또는
`.txz` 파일 경로를 직접 넘길 수 있습니다. 아카이브를 넘기면 첫 사용 시 사용자 캐시에 압축을 푼 뒤 그 디렉터리를 재사용합니다.

JVM 런타임은 추출된 `libgdalalljni` 경로를 `gdal4k.gdaljni.path` 시스템 속성으로 넘기고,
패치된 GDAL Java 바인딩이 그 경로를 직접 로드합니다. 그래서 시작 시 `Native library load failed.`
경고가 뜨지 않습니다.

패키지 버전은 `-PgdalVersion` 값과 같습니다. workflow_dispatch에서는 선택한 GDAL 버전이 그대로
`gdal4k-runtime`와 `gdal4k-binary`의 Maven 버전이 됩니다.
