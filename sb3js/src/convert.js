// jScratch converter from Scratch 1.4 (.sb) and Scratch 2 (.sb2) to Scratch 3.
//
// This does what the Scratch 3 editor does when it opens an old project and saves
// it again, with the same code: scratch-sb1-converter turns a 1.4 project into
// Scratch 2 JSON, scratch-vm's sb2 deserializer loads Scratch 2 JSON into a
// runtime, and scratch-vm's sb3 serializer writes that runtime back out. There is
// no renderer and no audio engine, so `headless/` replaces the few modules that
// would need a DOM (see build.mjs for how they are swapped in).
//
// Archives stay on the Kotlin side, like in index.js: the caller passes the files
// of an .sb2 archive (or the assets of a project downloaded from the Scratch
// server, named by MD5) and gets the files of the .sb3 back.
"use strict";

var Runtime = require("@scratch/scratch-vm/src/engine/runtime");
var sb2 = require("@scratch/scratch-vm/src/serialization/sb2");
var sb3 = require("@scratch/scratch-vm/src/serialization/sb3");
var serializeAssets = require("@scratch/scratch-vm/src/serialization/serialize-assets");
var MathUtil = require("@scratch/scratch-vm/src/util/math-util");
var StringUtil = require("@scratch/scratch-vm/src/util/string-util");
var ScratchStorage = require("@scratch/scratch-storage").ScratchStorage;
var SB1File = require("scratch-sb1-converter").SB1File;
var parse = require("scratch-parser/lib/parse");
var validate = require("scratch-parser/lib/validate");
var log = require("./headless/log");

// Sprite names the VM refuses to give a sprite (virtual-machine.js).
var RESERVED_NAMES = ["_mouse_", "_stage_", "_edge_", "_myself_", "_random_"];
var MD5_NAME = /^[0-9a-f]{32}\.[0-9a-z]+$/i;

/**
 * Converts a Scratch 1.4 project, given as the bytes of its .sb file.
 * Resolves to the same result as convertSb2.
 */
function convertSb1(data) {
  return run(function () {
    var sb1 = new SB1File(data.buffer.slice(data.byteOffset, data.byteOffset + data.byteLength));
    var json = sb1.json;
    json.projectVersion = 2;
    return { json: json, zip: sb1.zip, storage: new ScratchStorage() };
  });
}

/**
 * Converts a Scratch 2 project from the text of its project.json and the other
 * files of its archive (`names[i]` holds `datas[i]`, a Uint8Array). Files named
 * by index (`0.png`, `1.wav`) are looked up the way an .sb2 archive stores them,
 * files named by MD5 the way the Scratch server stores them.
 *
 * Resolves to `{ projectJson, names, datas, warnings }`: the Scratch 3
 * project.json, the asset files that go next to it, and anything that went
 * wrong without stopping the conversion.
 */
function convertSb2(projectJson, names, datas) {
  return run(function () {
    var json = parseSb2(projectJson);
    var storage = new ScratchStorage();
    var archived = {};
    var hasArchiveNames = false;
    for (var i = 0; i < names.length; i++) {
      var name = names[i];
      var data = datas[i];
      if (MD5_NAME.test(name)) {
        var parts = name.toLowerCase().split(".");
        var type = assetType(storage, parts[1]);
        if (type) storage.cache(type, parts[1], data, parts[0]);
      } else {
        archived[name] = data;
        hasArchiveNames = true;
      }
    }
    return { json: json, zip: hasArchiveNames ? new FakeZip(archived) : null, storage: storage };
  });
}

function parseSb2(text) {
  var project = null;
  var error = null;
  parse(text, function (e, result) {
    error = e;
    project = result;
  });
  if (error) throw new Error("project.json is not valid JSON: " + error);
  validate(false, project, function (e, result) {
    error = e;
    project = result;
  });
  if (error) throw new Error(describe(error));
  if (project.projectVersion !== 2) throw new Error("project.json is not a Scratch 2 project");
  return project;
}

function describe(error) {
  var errors = (error.sb2Errors || []).slice(0, 5).map(function (e) {
    return (e.dataPath || e.instancePath || "project") + " " + e.message;
  });
  var head = "project.json is not a valid Scratch 2 project";
  return errors.length ? head + " (" + errors.join("; ") + ")" : head;
}

function assetType(storage, ext) {
  if (ext === "svg") return storage.AssetType.ImageVector;
  if (ext === "png" || ext === "jpg" || ext === "jpeg" || ext === "gif" || ext === "bmp") {
    return storage.AssetType.ImageBitmap;
  }
  if (ext === "wav" || ext === "mp3") return storage.AssetType.Sound;
  return null;
}

function run(load) {
  log.takeMessages();
  var runtime = new Runtime();
  return Promise.resolve()
    .then(function () {
      var input = load();
      runtime.attachStorage(input.storage);
      return sb2.deserialize(input.json, runtime, false, input.zip);
    })
    .then(function (project) {
      var layers = installTargets(runtime, project.targets);
      var json = sb3.serialize(runtime);
      // sb3.serialize only writes layerOrder when a renderer reports it, so
      // write the order the renderer would have reported.
      var originals = runtime.targets.filter(function (t) {
        return t.isOriginal;
      });
      var order = MathUtil.reducedSortOrdering(
        originals.map(function (t) {
          return layers[t.id];
        }),
      );
      json.targets.forEach(function (t, i) {
        t.layerOrder = order[i];
      });
      return result(StringUtil.stringify(json), runtime);
    });
}

/**
 * The parts of VirtualMachine.installTargets that matter without a renderer, an
 * editor or extensions. Returns the layer order of each target by ID.
 */
function installTargets(runtime, targets) {
  targets = targets.filter(function (t) {
    return !!t;
  });
  targets.forEach(function (target) {
    runtime.addTarget(target);
    if (target.isSprite()) renameSprite(runtime, target);
  });
  runtime.executableTargets.sort(function (a, b) {
    return a.layerOrder - b.layerOrder;
  });
  var layers = {};
  targets.forEach(function (target) {
    layers[target.id] = target.layerOrder;
    delete target.layerOrder;
  });
  runtime.restoreSharedMissingDefinitions(targets);
  targets.forEach(function (target) {
    target.reconcileVariableReferences();
  });
  return layers;
}

/**
 * VirtualMachine.renameSprite with the sprite's own name, which makes sprite
 * names unique. The VM then renames references from the old name to the
 * requested one, which is the same name here, so that step is left out.
 */
function renameSprite(runtime, target) {
  var sprite = target.sprite;
  var name = sprite.name;
  if (!name || RESERVED_NAMES.indexOf(name) !== -1) return;
  var names = runtime.targets
    .filter(function (t) {
      return t.isSprite() && t.id !== target.id;
    })
    .map(function (t) {
      return t.sprite.name;
    });
  sprite.name = StringUtil.unusedName(name, names);
}

function result(projectJson, runtime) {
  var files = serializeAssets.serializeSounds(runtime).concat(serializeAssets.serializeCostumes(runtime));
  var names = [];
  var datas = [];
  var seen = {};
  files.forEach(function (file) {
    if (seen[file.fileName]) return;
    seen[file.fileName] = true;
    names.push(file.fileName);
    datas.push(file.fileContent);
  });
  return { projectJson: projectJson, names: names, datas: datas, warnings: log.takeMessages() };
}

/** The part of the JSZip API that scratch-vm's asset loading uses. */
function FakeZip(files) {
  this.files = files;
}

FakeZip.prototype.file = function (name) {
  var files = this.files;
  if (name instanceof RegExp) {
    return Object.keys(files)
      .filter(function (n) {
        return name.test(n);
      })
      .map(function (n) {
        return new FakeZipFile(files[n]);
      });
  }
  return Object.prototype.hasOwnProperty.call(files, name) ? new FakeZipFile(files[name]) : null;
};

function FakeZipFile(data) {
  this.data = data;
}

FakeZipFile.prototype.async = function () {
  return Promise.resolve(this.data);
};

module.exports = { convertSb1: convertSb1, convertSb2: convertSb2 };
