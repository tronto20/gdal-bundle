// Serve GDAL's binary assets verbatim, outside Karma's source preprocessing.
const fs = require('fs');
const path = require('path');
const runtime = process.env.GDAL4K_TEST_RUNTIME;
config.plugins.push({
    'middleware:gdalRuntime': ['factory', function () { return function (request, response, next) {
        const match = /^\/gdal\/([A-Za-z0-9_.-]+)$/.exec(request.url.split('?')[0]);
        if (!match) return next();
        fs.readFile(path.join(runtime, match[1]), (error, bytes) => {
            if (error) { response.statusCode = 404; return response.end(); }
            const extension = path.extname(match[1]);
            response.setHeader('Content-Type', extension === '.wasm' ? 'application/wasm'
                : extension === '.data' ? 'application/octet-stream' : 'text/javascript');
            response.end(bytes);
        });
    }; }],
});
config.middleware = [...(config.middleware || []), 'gdalRuntime'];
config.browserNoActivityTimeout = 120000;
config.client.mocha = { ...config.client.mocha, timeout: 120000 };
// Kotlin/JS strips import magic comments; preserve runtime URLs in the emitted library.
config.webpack.module.rules.push({ test: /gdal4k\.js$/, parser: { import: false } });
