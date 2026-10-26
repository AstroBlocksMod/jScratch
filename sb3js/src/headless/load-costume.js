// Stand-in for scratch-vm's `src/import/load-costume.js` when no renderer is attached.
//
// Without a renderer, scratch-vm's own loadCostumeFromAsset returns the costume
// unchanged, so the Scratch 2 SVG fixes and the bitmap resolution upgrade (both
// need a DOM and a canvas) never run. This module keeps that behavior and loads
// assets from storage by MD5 the way loadCostume does, with two differences:
//
// - A missing asset: scratch-vm loads the default costume through the renderer
//   and keeps the original under `broken`, so the saved project still points at
//   the missing file. Here the costume keeps pointing at it directly, with the
//   same result and no renderer.
// - A Scratch 1.4 text layer: costumes that went from Scratch 1.4 through Scratch
//   2 keep their text as a second image. scratch-vm draws it over the costume on
//   a canvas and saves the result as a PNG. This does the same with fast-png, for
//   costumes whose image is a PNG.
"use strict";

var png = require("fast-png");
var StringUtil = require("@scratch/scratch-vm/src/util/string-util");
var log = require("./log");

function loadCostumeFromAsset(costume, runtime) {
  costume.assetId = costume.asset.assetId;
  if (costume.textLayerMD5) mergeTextLayer(costume, runtime);
  return Promise.resolve(costume);
}

function loadCostume(md5ext, costume, runtime) {
  var idParts = StringUtil.splitFirst(md5ext, ".");
  var md5 = idParts[0];
  var ext = idParts[1].toLowerCase();
  costume.dataFormat = ext;

  var storage = runtime.storage;
  var assetType = ext === "svg" ? storage.AssetType.ImageVector : storage.AssetType.ImageBitmap;
  var asset = costume.asset ? Promise.resolve(costume.asset) : storage.load(assetType, md5, ext);
  var textLayer =
    costume.textLayerMD5 && !costume.textLayerAsset
      ? storage.load(storage.AssetType.ImageBitmap, costume.textLayerMD5, "png")
      : Promise.resolve(costume.textLayerAsset || null);
  return Promise.all([asset, textLayer]).then(function (assets) {
    if (!assets[0]) {
      log.warn("Costume '" + costume.name + "' is missing its image " + md5ext);
      return costume;
    }
    costume.asset = assets[0];
    costume.textLayerAsset = assets[1];
    return loadCostumeFromAsset(costume, runtime);
  });
}

/** Draws the text layer over the costume image, like fetchBitmapCanvas_ in scratch-vm. */
function mergeTextLayer(costume, runtime) {
  var textLayer = costume.textLayerAsset;
  delete costume.textLayerMD5;
  delete costume.textLayerAsset;
  var problem = !textLayer
    ? "is missing its text layer"
    : costume.dataFormat !== "png"
      ? "has a text layer over a " + costume.dataFormat.toUpperCase() + " image, which is not merged"
      : null;
  if (!problem) {
    try {
      var base = rgba(png.decode(costume.asset.data));
      var text = rgba(png.decode(textLayer.data));
      drawOver(base, text);
      var storage = runtime.storage;
      var data = png.encode({ width: base.width, height: base.height, data: base.data, channels: 4, depth: 8 });
      costume.asset = storage.createAsset(storage.AssetType.ImageBitmap, storage.DataFormat.PNG, data, null, true);
      costume.assetId = costume.asset.assetId;
      costume.md5 = costume.assetId + ".png";
      return;
    } catch (e) {
      problem = "has a text layer that could not be merged (" + e.message + ")";
    }
  }
  log.warn("Costume '" + costume.name + "' " + problem + "; the costume shows without its text");
}

/** A decoded PNG as 8-bit RGBA. */
function rgba(image) {
  var count = image.width * image.height;
  var src = image.data;
  var channels = image.channels;
  var shift = image.depth === 16 ? 8 : 0;
  if (image.palette) {
    src = png.convertIndexedToRgb(image);
    channels = image.palette[0].length;
    shift = 0;
  } else if (image.depth < 8) {
    throw new Error(image.depth + "-bit greyscale");
  }
  var out = new Uint8Array(count * 4);
  for (var i = 0; i < count; i++) {
    var s = i * channels;
    var grey = channels < 3;
    out[i * 4] = src[s] >> shift;
    out[i * 4 + 1] = src[grey ? s : s + 1] >> shift;
    out[i * 4 + 2] = src[grey ? s : s + 2] >> shift;
    out[i * 4 + 3] = channels === 2 || channels === 4 ? src[s + channels - 1] >> shift : 255;
  }
  return { width: image.width, height: image.height, data: out };
}

/** Canvas "source-over" of `top` onto `bottom`, both at the top left corner. */
function drawOver(bottom, top) {
  var b = bottom.data;
  var t = top.data;
  var width = Math.min(bottom.width, top.width);
  var height = Math.min(bottom.height, top.height);
  for (var y = 0; y < height; y++) {
    for (var x = 0; x < width; x++) {
      var i = (y * bottom.width + x) * 4;
      var j = (y * top.width + x) * 4;
      var ta = t[j + 3] / 255;
      if (ta === 0) continue;
      var ba = (b[i + 3] / 255) * (1 - ta);
      var alpha = ta + ba;
      for (var c = 0; c < 3; c++) b[i + c] = Math.round((t[j + c] * ta + b[i + c] * ba) / alpha);
      b[i + 3] = Math.round(alpha * 255);
    }
  }
}

module.exports = {
  loadCostume: loadCostume,
  loadCostumeFromAsset: loadCostumeFromAsset,
};
