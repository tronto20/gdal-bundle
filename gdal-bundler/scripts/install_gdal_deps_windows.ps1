param(
    [Parameter(Mandatory = $true)]
    [string]$CondaPrefix,

    [string]$CondaExe
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Resolve-CondaExe {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Prefix,

        [string]$Exe
    )

    if ($Exe -and $Exe.Trim().Length -gt 0) {
        return (Resolve-Path -LiteralPath $Exe).Path
    }

    $candidates = @(
        (Join-Path $Prefix "Scripts\conda.exe"),
        (Join-Path $Prefix "Scripts\conda.bat"),
        (Join-Path $Prefix "Scripts\conda.cmd"),
        (Join-Path $Prefix "condabin\conda.bat"),
        (Join-Path $Prefix "condabin\conda.cmd")
    )

    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }

    throw "Conda executable not found under $Prefix"
}

if (-not (Test-Path -LiteralPath $CondaPrefix -PathType Container)) {
    throw "Conda prefix not found: $CondaPrefix"
}

$condaExePath = Resolve-CondaExe -Prefix $CondaPrefix -Exe $CondaExe

$packages = @(
    "cmake"
    "ninja"
    "pkg-config"
    "swig"
    "ant"
    "openjdk"
    "geos"
    "proj"
    "sqlite"
    "libxml2"
    "xerces-c"
    "curl"
    "openssl"
    "libtiff"
    "libjpeg-turbo"
    "libpng"
    "giflib"
    "zlib"
    "zstd"
    "lz4-c"
    "snappy"
    "bzip2"
    "xz"
    "libdeflate"
    "expat"
    "json-c"
    "muparser"
    "libspatialite"
    "freexl"
    "pcre2"
    "libiconv"
    "hdf5"
    "libnetcdf"
    "cfitsio"
    "openjpeg"
    "openexr"
    "imath"
    "libheif"
    "libaec"
    "geotiff"
    "qhull"
    "libarchive"
    "libwebp"
    "libjxl"
    "aws-sdk-cpp"
    "minizip"
    "uriparser"
    "boost-cpp"
)

Write-Host "Installing GDAL dependencies into $CondaPrefix"
& $condaExePath install -y -p $CondaPrefix -c conda-forge --solver=classic @packages
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}
