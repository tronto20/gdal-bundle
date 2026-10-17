// Included inside g4k after Runtime and the shared file helpers.
struct VectorGeometry {
    OGRGeometryH handle = nullptr;
    ~VectorGeometry() { if (handle) OGR_G_DestroyGeometry(handle); }
    CPLJSONObject result(Runtime& runtime) {
        auto value = runtime.result(handle, "Geometry", true); handle = nullptr; return value;
    }
};
inline std::string vectorText(const CPLJSONObject& input) {
    auto text = input.ToString();
    require(!text.empty() && text.find('\0') == std::string::npos, "Empty geometry text or embedded NUL");
    return text;
}
inline void vectorSameCrs(OGRSpatialReferenceH left, OGRSpatialReferenceH right) {
    require(!left || !right || OSRIsSame(left, right), "Geometry CRSs differ; transform coordinates explicitly before this operation");
}
inline CPLJSONObject createVectorGeometry(Runtime& runtime, const CPLJSONObject& args, const std::string& method) {
    auto srs = args["spatialReference"].GetType() == CPLJSONObject::Type::Null ? nullptr : runtime.arg(args["spatialReference"]);
    VectorGeometry geometry;
    CPLErrorReset();
    if (method == "createGeometryFromWkt") {
        auto text = vectorText(args["wkt"]); char* cursor = text.data();
        auto code = OGR_G_CreateFromWkt(&cursor, srs, &geometry.handle);
        fileEnsure(code == OGRERR_NONE && geometry.handle, "Invalid WKT geometry");
        while (*cursor && std::isspace(static_cast<unsigned char>(*cursor))) ++cursor;
        require(!*cursor, "Trailing content after WKT geometry");
    } else if (method == "createGeometryFromWkb") {
        const auto input = args.GetArray("wkb");
        // GDAL interprets size zero as unchecked input. Reject it before passing a pointer.
        require(input.Size() > 0, "Empty WKB geometry");
        std::vector<GByte> bytes; bytes.reserve(input.Size());
        for (const auto& item : input) bytes.push_back(static_cast<GByte>(item.ToInteger()));
        auto code = OGR_G_CreateFromWkbEx(bytes.data(), srs, &geometry.handle, bytes.size());
        fileEnsure(code == OGRERR_NONE && geometry.handle, "Invalid or truncated WKB geometry");
    } else {
        auto text = vectorText(args["geoJson"]);
        geometry.handle = OGR_G_CreateGeometryFromJson(text.c_str());
        fileEnsure(geometry.handle != nullptr, "Invalid GeoJSON geometry");
        if (srs) OGR_G_AssignSpatialReference(geometry.handle, srs);
    }
    return geometry.result(runtime);
}
inline CPLJSONObject vectorOverlay(Runtime& runtime, OGRGeometryH source, const CPLJSONObject& args, const std::string& method) {
    auto other = runtime.arg(args["other"]);
    vectorSameCrs(OGR_G_GetSpatialReference(source), OGR_G_GetSpatialReference(other));
    CPLErrorReset();
    VectorGeometry output;
    output.handle = method == "intersection" ? OGR_G_Intersection(source, other) :
                    method == "union" ? OGR_G_Union(source, other) : OGR_G_Difference(source, other);
    fileEnsure(CPLGetLastErrorType() < CE_Failure, "Geometry overlay failed");
    return output.result(runtime);
}
inline CPLJSONObject setVectorSpatialFilter(Runtime& runtime, OGRLayerH layer, const CPLJSONObject& args, bool rectangle) {
    auto native = OGRLayer::FromHandle(layer);
    if (rectangle) {
        auto minX = CPLAtof(args["minX"].ToString().c_str()), minY = CPLAtof(args["minY"].ToString().c_str());
        auto maxX = CPLAtof(args["maxX"].ToString().c_str()), maxY = CPLAtof(args["maxY"].ToString().c_str());
        require(std::isfinite(minX) && std::isfinite(minY) && std::isfinite(maxX) && std::isfinite(maxY) && minX <= maxX && minY <= maxY,
                "Spatial filter rectangle must be finite and ordered");
        require(OGR_FD_GetGeomFieldCount(OGR_L_GetLayerDefn(layer)) > 0, "Layer has no geometry field");
        return value(native->SetSpatialFilterRect(minX, minY, maxX, maxY));
    }
    auto filter = args["filter"].GetType() == CPLJSONObject::Type::Null ? nullptr : runtime.arg(args["filter"]);
    if (filter) {
        require(OGR_FD_GetGeomFieldCount(OGR_L_GetLayerDefn(layer)) > 0, "Layer has no geometry field");
        vectorSameCrs(OGR_L_GetSpatialRef(layer), OGR_G_GetSpatialReference(filter));
    }
    return value(native->SetSpatialFilter(OGRGeometry::FromHandle(filter)));
}
