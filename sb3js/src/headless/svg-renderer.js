// Stand-in for @scratch/scratch-svg-renderer. scratch-vm only uses it while
// importing to sanitize SVG costumes with DOMPurify, which needs a DOM. The
// converter keeps every SVG byte for byte instead; the Scratch 3 editor
// sanitizes them again whenever the converted project is opened there.
"use strict";

module.exports = {
  sanitizeSvg: {
    sanitizeByteStream: function (data) {
      return data;
    },
  },
};
