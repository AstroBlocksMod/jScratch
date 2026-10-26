// Stand-in for scratch-vm's `src/util/log.js`, which is a tslog logger that
// prints to the console. The converter collects warnings and errors instead,
// so they can be reported with the conversion result.
"use strict";

// Messages that only say there is no renderer or audio engine, which is how the converter runs.
var HEADLESS_NOISE = /^No (rendering module|audio engine) present/;

var messages = [];

function record() {
  var text = Array.prototype.map.call(arguments, String).join(" ");
  if (!HEADLESS_NOISE.test(text)) messages.push(text);
}

function ignore() {}

module.exports = {
  silly: ignore,
  trace: ignore,
  debug: ignore,
  info: ignore,
  log: ignore,
  warn: record,
  warning: record,
  error: record,
  fatal: record,
  /** Returns the messages recorded since the last call and forgets them. */
  takeMessages: function () {
    return messages.splice(0, messages.length);
  },
};
