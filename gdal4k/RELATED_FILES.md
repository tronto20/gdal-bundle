# 직접 읽는 관련 파일

Shapefile의 `.shp`, `.shx`, `.dbf`, `.prj`는 같은 디렉터리에 보여야 합니다. GDAL이 주 파일을 열고 관련 파일을 요청할 때 플랫폼 어댑터가 이름으로 파일을 찾아 필요한 구간을 읽습니다. 전체 입력을 `/vsimem`이나 임시 파일로 복사하지 않습니다.

일반 JVM·Android·Native 파일 경로는 기존 GDAL 파일 시스템을 그대로 사용합니다. Node에서 실행하는 JS·wasmJs도 같은 디렉터리의 파일을 지연 개방합니다.

## Android

[Android URI 입력](ANDROID.md)의 `openDataset(main, directory = treeUri)` 또는 `openDataset(main, files = namedUris)`를 사용합니다. 폴더 접근 권한 또는 각 파일의 URI 접근 권한이 필요합니다. 현재 가상 디렉터리는 한 단계의 파일만 노출합니다.

## JS와 wasmJs 브라우저

```kotlin
import dev.gdal4k.Gdal4k
import dev.gdal4k.runtime.*
import org.w3c.files.File

// 파일 선택기의 다중 선택 또는 폴더 선택 결과에서 같은 폴더의 File을 모읍니다.
val selectedFiles: List<File> = /* 선택한 파일들 */
Gdal4k.runtime().openDataset("roads.shp", files = selectedFiles).use { dataset ->
    // 도형과 속성을 공통 API로 읽습니다.
}
```

파일명은 고유해야 합니다. 파일 객체와 메타데이터를 Worker에 전달하며 내용은 `File.slice`로 필요한 구간만 읽습니다. 읽기 전용이고, 임의의 로컬 경로로 권한을 확대하지 않습니다. 하위 폴더를 평탄화하면 이름 충돌이 생길 수 있으므로 같은 폴더의 파일만 모읍니다.

## HTTP

```kotlin
val runtime = Gdal4k.runtime()
runtime.openDataset("https://example.com/data/roads.shp").use { dataset ->
    // roads.shx, roads.dbf, roads.prj를 같은 URL 디렉터리에서 탐색합니다.
}

// 파일별로 서명 URL이 다르거나 저장 경로가 다른 경우.
runtime.openDataset(
    "https://example.com/data/roads.shp?signature=main",
    relatedFiles = mapOf(
        "roads.shx" to shxUrl,
        "roads.dbf" to dbfUrl,
        "roads.prj" to prjUrl,
    ),
).use { dataset -> /* 공통 API */ }
```

관련 파일 매핑을 제공하면 주 파일과 명시한 파일만 노출합니다. 매핑을 생략하면 같은 디렉터리의 상대 URL을 탐색하며 주 URL의 query를 복사하지 않습니다. 서버는 `206`과 올바른 `Content-Range`를 지원해야 하며 브라우저에서는 CORS가 허용되어야 합니다. 관련 파일의 `404`는 없는 파일로 처리하지만 다른 전송 오류나 읽는 도중 변경은 오류로 전달합니다. 디렉터리 목록을 가져오는 HTTP API는 요구하지 않습니다.

HTTP 방식은 현재 JS·wasmJs에서 지원합니다. 모든 직접 입력은 읽기 전용이고 런타임 전체의 입력 캐시는 기존 8 MiB 한도를 유지합니다. `Dataset.close()`는 해당 디렉터리의 열린 파일과 캐시를 정리합니다.
