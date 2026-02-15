# gdal-bundler

macOS용 GDAL JNI 동적 라이브러리와 의존 dylib, 드라이버 플러그인, 데이터(`share/gdal`, `share/proj`)를
Conda 환경에서 추출해 앱 번들에 넣기 위한 모듈입니다. `install_name_tool`로 의존 경로를
`@loader_path` 기반으로 패치해 앱 내부 상대 경로로 로딩되도록 만듭니다.

## 요구 사항
- macOS (Apple Silicon)
- Conda 환경에 GDAL + Java 바인딩 포함 (`libgdalalljni.dylib` 존재)
- Xcode Command Line Tools (`otool`, `install_name_tool`)

## 사용 방법
### 빌드 + 번들링 (GDAL + LIBKML)
GDAL과 LIBKML을 소스 빌드한 뒤 번들링까지 한 번에 수행합니다. KMZ를 위해 LIBKML 드라이버가 포함됩니다.

```bash
cd ovision-ui
./gradlew :gdal-bundler:buildAndBundleGdalMacos \
  --conda-prefix=/path/to/conda/env \
  --conda-exe=/path/to/conda
```

옵션:
- `--conda-prefix=/path/to/env`, `--conda-exe=/path/to/conda`: 기본값은 `CONDA_PREFIX`, `CONDA_EXE`
- `--work-dir=/path/to/work`: 소스/빌드 작업 경로 지정
- `--gdal-version=3.12.2`, `--libkml-version=1.3.0`: 버전 변경
- `--output-dir=/path/to/output`: 번들 출력 경로 지정
- `--codesign-identity="Developer ID Application: ..."`: 번들된 dylib 재서명 (또는 `CODESIGN_IDENTITY`)

이 작업은 conda prefix에 GDAL/libkml을 설치(덮어쓰기)합니다.
`buildLibkmlMacos`, `buildGdalMacos`, `buildAndBundleGdalMacos`는 `installGdalDeps`를 먼저 수행합니다.

### 단계별 실행
필요한 단계만 따로 실행할 수 있습니다. 작업 디렉터리를 지우려면 `:gdal-bundler:cleanGdalWorkDir`를 실행하세요.

```bash
./gradlew :gdal-bundler:installGdalDeps \
  --conda-prefix=/path/to/conda/env \
  --conda-exe=/path/to/conda
```

```bash
./gradlew :gdal-bundler:cleanGdalWorkDir \
  --work-dir=/path/to/work
```

```bash
./gradlew :gdal-bundler:buildLibkmlMacos \
  --conda-prefix=/path/to/conda/env
```

```bash
./gradlew :gdal-bundler:buildGdalMacos \
  --conda-prefix=/path/to/conda/env
```

### 번들링만
```bash
cd ovision-ui
./gradlew :gdal-bundler:bundleGdalMacos
```

Conda 환경이 활성화되지 않았다면 prefix를 지정하세요.
```bash
./gradlew :gdal-bundler:bundleGdalMacos \
  --conda-prefix=/path/to/conda/env
```

배포용으로 dylib를 재서명하려면:
```bash
./gradlew :gdal-bundler:bundleGdalMacos \
  --conda-prefix=/path/to/conda/env \
  --codesign-identity="Developer ID Application: ..."
```

출력 경로를 바꾸려면:
```bash
./gradlew :gdal-bundler:bundleGdalMacos \
  --output-dir=/path/to/output
```

기본 출력은 `gdal-bundler/build/gdal-bundle/gdal` 입니다.

## Compose Desktop DMG에 포함하기 (예시)
Compose Desktop(1.9.x 기준)는 `appResourcesRootDir` 아래의
`common/`, `macos/`, `macos-arm64/`를 읽어 macOS 패키지에
`Contents/app/resources`로 복사합니다.
그래서 GDAL 번들은 `macos/gdal`에 복사해야 합니다.

```kotlin
val gdalBundleDir = project(":gdal-bundler").layout.buildDirectory.dir("gdal-bundle")
val gdalResourcesRoot = layout.buildDirectory.dir("app-resources")

val syncGdalResources by tasks.registering(Sync::class) {
    dependsOn(":gdal-bundler:bundleGdalMacos")
    from(gdalBundleDir.map { it.dir("gdal") })
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
