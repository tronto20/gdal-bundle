# Android URI 입력

Android에서는 앱 시작 시 AndroidX App Startup이 앱 컨텍스트의 `ContentResolver` 참조를 등록합니다. 이 단계는 GDAL을 로드하거나 파일을 열지 않습니다. GDAL은 첫 `Gdal4k.runtime()` 호출에서 준비합니다.

```kotlin
import dev.gdal4k.Gdal4k
import dev.gdal4k.runtime.*

val runtime = Gdal4k.runtime()
runtime.openDataset(uri).use { dataset ->
    println(dataset.getRasterXSize())
}

// 다른 Resolver가 필요한 경우. 자동 등록을 비활성화한 앱에서도 사용 가능.
runtime.openDataset(uri, resolver = context.contentResolver).use { dataset ->
    println(dataset.getRasterYSize())
}

// URI 문자열도 같은 방식으로 사용할 수 있습니다.
runtime.openDataset("content://documents/...")
runtime.openDataset(uri.toString(), resolver = context.contentResolver)

// 일반 파일 경로는 기존 GDAL 파일 읽기를 유지합니다.
runtime.openDataset("/data/user/0/example.app/files/image.tif")
```

Android `Uri` 또는 URI 문자열을 전달합니다. String 오버로드도 `content:`, `file:`, `android.resource:`를 인식해 기본 Resolver를 사용합니다. 일반 경로와 GDAL 가상 파일 경로는 기존 방식으로 처리합니다. `resolver`는 선택 인자이고 명시한 값이 기본값보다 우선합니다. 일반 파일 경로에는 Resolver가 필요하지 않습니다. `options = DatasetOpenOptions(...)`도 함께 지정할 수 있습니다. 반환값은 기존 공통 `Dataset`이며 `Band`, `Layer`, `datasetInfo`, 변환 API를 그대로 사용합니다.

URI 어댑터는 `content:`, `file:`, `android.resource:`를 읽기 전용으로 엽니다. `ContentResolver`가 제공한 디스크립터에서 실제 seek 지원과 파일 범위를 검사합니다. seek할 수 없는 파이프, 잘못된 범위와 빈 입력은 오류로 거절합니다. 전체 파일을 메모리나 캐시 파일에 복사하지 않습니다. GDAL의 `/vsisubfile/`을 사용해 `AssetFileDescriptor`의 시작 오프셋과 길이를 지키면서 디스크립터를 직접 읽습니다.

디스크립터는 반환한 데이터셋이 닫힐 때까지 유지합니다. 열기 실패, 취소된 열기, `Dataset.close()`와 전역 `Gdal4k.close()`에서 정리합니다. URI를 저장하는 것과 데이터셋을 열어 두는 것은 별개입니다. 나중에 다시 사용할 URI의 접근 권한 유지(`takePersistableUriPermission`)는 앱이 관리합니다. 권한은 URI를 열 때 검사하며 시작 시 권한을 고정하지 않습니다.

단일 `content://` URI의 권한은 관련 파일의 접근 권한까지 포함하지 않습니다. Shapefile처럼 여러 파일을 사용하는 경우 다음 중 하나를 사용합니다.

```kotlin
// ACTION_OPEN_DOCUMENT_TREE로 받은 폴더 권한 사용.
runtime.openDataset("roads.shp", directory = treeUri).use { dataset ->
    // GDAL이 roads.shx, roads.dbf, roads.prj를 필요할 때 엽니다.
}

// 여러 파일을 선택한 경우: 이름과 각 URI를 제공.
runtime.openDataset(
    "roads.shp",
    files = mapOf("roads.shp" to shpUri, "roads.shx" to shxUri,
        "roads.dbf" to dbfUri, "roads.prj" to prjUri),
    resolver = context.contentResolver, // 생략하면 앱 기본 Resolver
).use { dataset -> /* 공통 Dataset API */ }
```

폴더 입력은 SAF에서 부여한 tree URI의 바로 아래 파일 목록을 조회합니다. 하위 디렉터리는 포함하지 않습니다. URI 묶음은 전달한 이름만 노출하며 중복 이름이나 경로는 허용하지 않습니다. GDAL의 파일 열기·디렉터리 조회를 이 목록에 연결하고, 실제 파일 디스크립터는 GDAL이 해당 파일을 요청할 때만 엽니다. 파일 내용은 전체 복사하지 않으며 각 디스크립터의 seek와 범위를 검사합니다. 데이터셋 종료·열기 실패·취소·런타임 종료에서 열린 파일과 가상 디렉터리를 정리합니다. 일부 드라이버가 부속 파일 오류를 무시하더라도 공급자의 권한 오류는 호출자에게 전달합니다.

실제 파일 경로(`/data/.../roads.shp`)는 원래 GDAL 동작을 유지하므로 같은 폴더의 관련 파일을 자동으로 찾습니다. `GdalFiles`는 기존처럼 이미 메모리에 있는 여러 파일을 제공할 때 사용할 수 있습니다.

자동 등록을 끄려면 앱 Manifest에서 `dev.gdal4k.AndroidResolverInitializer` metadata를 `tools:node="remove"`로 제거합니다. 이 경우에는 `resolver`를 명시합니다. 기본 Resolver가 없는데 인자를 생략하면 해결 방법을 포함한 오류가 발생합니다. 다른 프로세스에서 자동 등록을 사용할 수 없는 경우에도 명시적 인자를 사용할 수 있습니다.
