// TextEncoder and TextDecoder for every module in the converter bundle (see the
// `inject` option in build.mjs). Browsers have them; Rhino does not, so it gets
// this small version of what the bundle needs: UTF-8 both ways, and decoding
// windows-1252, which the Encoding Standard also calls latin1 and which fast-png
// asks for as soon as it loads. Errors decode to U+FFFD, as in a browser.
"use strict";

var UTF8_LABELS = ["utf-8", "utf8", "unicode-1-1-utf-8", "unicode11utf8", "unicode20utf8", "x-unicode20utf8"];
var WINDOWS_1252_LABELS = [
  "windows-1252", "latin1", "iso-8859-1", "iso8859-1", "iso88591", "iso_8859-1", "l1", "ascii", "us-ascii",
  "cp1252", "cp819", "ibm819", "x-cp1252", "ansi_x3.4-1968", "csisolatin1", "iso-ir-100",
];
// What windows-1252 has at 0x80 to 0x9F; every other byte is the code point of the same value.
var WINDOWS_1252_HIGH = [
  0x20ac, 0x81, 0x201a, 0x192, 0x201e, 0x2026, 0x2020, 0x2021, 0x2c6, 0x2030, 0x160, 0x2039, 0x152, 0x8d, 0x17d, 0x8f,
  0x90, 0x2018, 0x2019, 0x201c, 0x201d, 0x2022, 0x2013, 0x2014, 0x2dc, 0x2122, 0x161, 0x203a, 0x153, 0x9d, 0x17e, 0x178,
];

function Encoder() {}

Encoder.prototype.encoding = "utf-8";

Encoder.prototype.encode = function (text) {
  var s = text === undefined ? "" : String(text);
  var out = [];
  for (var i = 0; i < s.length; i++) {
    var c = s.charCodeAt(i);
    if (c >= 0xd800 && c < 0xe000) {
      var next = s.charCodeAt(i + 1);
      if (c < 0xdc00 && next >= 0xdc00 && next < 0xe000) {
        c = 0x10000 + ((c - 0xd800) << 10) + (next - 0xdc00);
        i++;
      } else {
        c = 0xfffd;
      }
    }
    if (c < 0x80) out.push(c);
    else if (c < 0x800) out.push(0xc0 | (c >> 6), 0x80 | (c & 0x3f));
    else if (c < 0x10000) out.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 0x3f), 0x80 | (c & 0x3f));
    else out.push(0xf0 | (c >> 18), 0x80 | ((c >> 12) & 0x3f), 0x80 | ((c >> 6) & 0x3f), 0x80 | (c & 0x3f));
  }
  return new Uint8Array(out);
};

function Decoder(label, options) {
  var name = label === undefined ? "utf-8" : String(label).trim().toLowerCase();
  if (UTF8_LABELS.indexOf(name) !== -1) this.encoding = "utf-8";
  else if (WINDOWS_1252_LABELS.indexOf(name) !== -1) this.encoding = "windows-1252";
  else throw new RangeError("The encoding label provided ('" + label + "') is not supported.");
  this.ignoreBOM = !!(options && options.ignoreBOM);
}

Decoder.prototype.decode = function (input) {
  if (input === undefined) return "";
  var bytes = ArrayBuffer.isView(input)
    ? new Uint8Array(input.buffer, input.byteOffset, input.byteLength)
    : new Uint8Array(input);
  var points = this.encoding === "utf-8" ? utf8(bytes, this.ignoreBOM) : windows1252(bytes);
  var text = "";
  // In slices, so the argument list stays well below every engine's limit.
  for (var i = 0; i < points.length; i += 8192) {
    text += String.fromCharCode.apply(null, points.slice(i, i + 8192));
  }
  return text;
};

function windows1252(bytes) {
  var units = new Array(bytes.length);
  for (var i = 0; i < bytes.length; i++) {
    var b = bytes[i];
    units[i] = b >= 0x80 && b < 0xa0 ? WINDOWS_1252_HIGH[b - 0x80] : b;
  }
  return units;
}

/** UTF-16 code units of UTF-8 bytes, replacing each broken sequence the way the Encoding Standard does. */
function utf8(bytes, ignoreBOM) {
  var units = [];
  var i = !ignoreBOM && bytes[0] === 0xef && bytes[1] === 0xbb && bytes[2] === 0xbf ? 3 : 0;
  while (i < bytes.length) {
    var b = bytes[i++];
    var c = b;
    var need = 0;
    var lower = 0x80;
    var upper = 0xbf;
    if (b >= 0xc2 && b <= 0xdf) {
      need = 1;
      c = b & 0x1f;
    } else if (b >= 0xe0 && b <= 0xef) {
      need = 2;
      c = b & 0x0f;
      if (b === 0xe0) lower = 0xa0;
      if (b === 0xed) upper = 0x9f;
    } else if (b >= 0xf0 && b <= 0xf4) {
      need = 3;
      c = b & 0x07;
      if (b === 0xf0) lower = 0x90;
      if (b === 0xf4) upper = 0x8f;
    } else if (b >= 0x80) {
      c = 0xfffd;
    }
    while (need > 0) {
      if (i >= bytes.length || bytes[i] < lower || bytes[i] > upper) {
        c = 0xfffd;
        break;
      }
      c = (c << 6) | (bytes[i++] & 0x3f);
      lower = 0x80;
      upper = 0xbf;
      need--;
    }
    if (c >= 0x10000) units.push(0xd800 + ((c - 0x10000) >> 10), 0xdc00 + ((c - 0x10000) & 0x3ff));
    else units.push(c);
  }
  return units;
}

export var TextEncoder = typeof globalThis.TextEncoder === "function" ? globalThis.TextEncoder : Encoder;
export var TextDecoder = typeof globalThis.TextDecoder === "function" ? globalThis.TextDecoder : Decoder;
