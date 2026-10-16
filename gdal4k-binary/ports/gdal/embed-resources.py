"""Expand GDAL's C23 #embed directives for the platform's C compiler.

The bytes and terminating zero remain identical to upstream. Apple Clang and
older NDK/GCC versions can then use the same embedded resources as Emscripten.
"""
from pathlib import Path
import re
import sys

root = Path(sys.argv[1])
for source in root.rglob('embedded_resources*.c'):
    text = source.read_text()
    def expand(match):
        data = (source.parent / match[1]).read_bytes()
        return '\n'.join(','.join(f'0x{b:02x}' for b in data[i:i+24]) + (',' if i+24 < len(data) else '') for i in range(0, len(data), 24))
    expanded = re.sub(r'^[ \t]*#embed "([^"]+)"[ \t]*$', expand, text, flags=re.M)
    source.write_text(expanded)
for relative in ['gcore/CMakeLists.txt', 'cmake/helpers/GdalDriverHelper.cmake']:
    source = root / relative
    source.write_text(source.read_text().replace('C_STANDARD 23', 'C_STANDARD 99'))
source = root / 'gdal.cmake'
text = source.read_text()
text = text.replace('if (EMBED_RESOURCE_FILES AND NOT IS_SHARP_EMBED_AVAILABLE_RES)\n  message(FATAL_ERROR "C23 #embed not available with this compiler")\nendif()', '# gdal4k expands resource bytes before configuring; a C23 compiler is not required.')
source.write_text(text)

for relative in ['frmts/gtiff/CMakeLists.txt', 'frmts/jpegxl/CMakeLists.txt']:
    source = root / relative
    text = source.read_text()
    text = text.replace('set(CMAKE_REQUIRED_LIBRARIES ${JXL_LIBRARIES})', 'set(CMAKE_REQUIRED_INCLUDES ${JXL_INCLUDE_DIRS})\nset(_gdal4k_old_try_compile_type "${CMAKE_TRY_COMPILE_TARGET_TYPE}")\nset(CMAKE_TRY_COMPILE_TARGET_TYPE STATIC_LIBRARY)\ninclude(CheckSymbolExists)')
    text = re.sub(r'check_function_exists\((Jxl\w+) (\w+)\)', lambda m: f'check_symbol_exists({m[1]} "jxl/encode.h;jxl/decode.h" {m[2]})', text)
    text = text.replace('cmake_pop_check_state()', 'set(CMAKE_TRY_COMPILE_TARGET_TYPE "${_gdal4k_old_try_compile_type}")\ncmake_pop_check_state()', 1)
    source.write_text(text)
