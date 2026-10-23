// jScratch wrapper around scratch-parser, the Scratch Foundation's JavaScript sb3 parser.
//
// scratch-parser's public entry point is promise based, but the pieces it is built
// from (`lib/parse` and `lib/validate`) call their callbacks synchronously. Using
// those directly gives one synchronous function that runs unchanged in the browser
// and inside Rhino on the JVM. Unzipping stays on the Kotlin side, so JSZip is not
// bundled.
"use strict";

var parse = require("scratch-parser/lib/parse");
var validate = require("scratch-parser/lib/validate");

/**
 * Parses project.json text and validates it against the official sb3 schema.
 * Returns the project object. Throws an Error with a readable message otherwise.
 */
function parseProject(text) {
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
  if (project.projectVersion !== 3) throw new Error("only Scratch 3 (.sb3) projects are supported");
  return project;
}

function describe(error) {
  if (typeof error === "string") return error;
  var errors = (error.sb3Errors || []).slice(0, 5).map(function (e) {
    return (e.dataPath || e.instancePath || "project") + " " + e.message;
  });
  var head = error.validationError || "project.json is not a valid Scratch project";
  return errors.length ? head + " (" + errors.join("; ") + ")" : head;
}

/** Same as parseProject, serialized back to JSON. Used from Rhino, where a string is the cheapest thing to hand over. */
function parseProjectToJson(text) {
  return JSON.stringify(parseProject(text));
}

module.exports = { parseProject: parseProject, parseProjectToJson: parseProjectToJson };
