// Stand-in for JSZip. scratch-vm's asset loading only checks
// `JSZip.support.uint8array`; the archive itself is a FakeZip from convert.js.
"use strict";

module.exports = { support: { uint8array: true } };
