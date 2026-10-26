// Stand-in for scratch-storage's `src/scratchFetch.ts`, which builds a fetch
// `Headers` object as soon as it loads. Rhino has no fetch, and the converter
// never downloads anything: assets come with the project.
"use strict";

var RequestMetadata = { ProjectId: "X-Project-ID", RunId: "X-Run-ID" };

function scratchFetch(resource) {
  return Promise.reject(new Error("the converter does not download assets (" + resource + ")"));
}

function ignore() {}

module.exports = {
  Headers: globalThis.Headers,
  RequestMetadata: RequestMetadata,
  hasMetadata: function () {
    return false;
  },
  applyMetadata: function (options) {
    return options;
  },
  scratchFetch: scratchFetch,
  createQueue: ignore,
  setMetadata: ignore,
  unsetMetadata: ignore,
  getMetadata: function () {
    return null;
  },
};
