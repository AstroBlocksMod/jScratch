// TextEncoder and TextDecoder for every module in the converter bundle (see the
// `inject` option in build.mjs). Browsers have them; Rhino does not, so it gets
// the text-encoding package, which scratch-sb1-converter brings along anyway.
// fast-png needs a TextDecoder for latin1 as soon as it loads.
"use strict";

var polyfill = require("text-encoding");

export var TextEncoder = typeof globalThis.TextEncoder === "function" ? globalThis.TextEncoder : polyfill.TextEncoder;
export var TextDecoder = typeof globalThis.TextDecoder === "function" ? globalThis.TextDecoder : polyfill.TextDecoder;
