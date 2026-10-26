(function () {
  "use strict";

  const fileInput = document.getElementById("file");
  const drop = document.getElementById("drop");
  const dropText = document.getElementById("drop-text");
  const compileButton = document.getElementById("compile");
  const listingCheckbox = document.getElementById("listing");
  const status = document.getElementById("status");
  const result = document.getElementById("result");
  const summary = document.getElementById("summary");
  const download = document.getElementById("download");
  const downloadSb3 = document.getElementById("download-sb3");
  const warningsOut = document.getElementById("warnings");
  const warningCount = document.getElementById("warning-count");
  const listingOut = document.getElementById("listing-out");
  const listingDetails = document.getElementById("listing-details");
  const targetsBody = document.querySelector("#targets tbody");

  let runtimeJar = null;
  let selected = null;
  let lastUrl = null;
  let lastSb3Url = null;
  let converter = null;

  function setStatus(text, isError) {
    status.textContent = text;
    status.classList.toggle("error", !!isError);
  }

  function updateButton() {
    compileButton.disabled = !(selected && window.jscratch && runtimeJar);
  }

  // The TeaVM output defines a global main() that installs window.jscratch.
  try {
    if (typeof main === "function") main();
  } catch (e) {
    setStatus("The compiler failed to start: " + e, true);
  }

  fetch("jscratch-runtime.jar")
    .then((r) => {
      if (!r.ok) throw new Error("HTTP " + r.status);
      return r.arrayBuffer();
    })
    .then((buf) => {
      runtimeJar = new Int8Array(buf);
      setStatus("Ready. Runtime: " + (buf.byteLength / 1024 / 1024).toFixed(1) + " MiB.");
      updateButton();
    })
    .catch((e) => {
      setStatus("Could not load the runtime JAR (" + e + "). Compiled JARs will contain only the project classes.", true);
      updateButton();
    });

  function choose(file) {
    selected = file;
    dropText.textContent = file ? file.name + " (" + Math.round(file.size / 1024) + " KiB)" : "Drop a project file here or click to choose one";
    updateButton();
  }

  // The converter for Scratch 1.4 and 2 projects is large, so it loads with the first old project.
  function loadConverter() {
    if (!converter) {
      converter = new Promise((resolve, reject) => {
        const script = document.createElement("script");
        script.src = "jscratch-convert.js";
        script.onload = () => resolve(window.jscratchConvert);
        script.onerror = () => {
          converter = null;
          reject(new Error("could not load jscratch-convert.js"));
        };
        document.head.appendChild(script);
      });
    }
    return converter;
  }

  const uint8 = (a) => new Uint8Array(a.buffer, a.byteOffset, a.byteLength);
  const int8 = (a) => new Int8Array(a.buffer, a.byteOffset, a.byteLength);

  // Converts a Scratch 1.4 or 2 project to an .sb3, or returns null for anything else.
  async function convertOld(data) {
    const old = window.jscratch.readOld(data);
    if (!old) return null;
    setStatus("Converting the " + old.label + " project to Scratch 3…");
    const convert = await loadConverter();
    const started = performance.now();
    const converted =
      old.format === "sb1"
        ? await convert.convertSb1(uint8(old.datas[0]))
        : await convert.convertSb2(old.projectJson, old.names, old.datas.map(uint8));
    const sb3 = window.jscratch.writeSb3(converted.projectJson, converted.names, converted.datas.map(int8));
    return { label: old.label, sb3, warnings: converted.warnings, ms: Math.round(performance.now() - started) };
  }

  fileInput.addEventListener("change", () => choose(fileInput.files[0] || null));

  ["dragenter", "dragover"].forEach((ev) =>
    drop.addEventListener(ev, (e) => {
      e.preventDefault();
      drop.classList.add("active");
    })
  );
  ["dragleave", "drop"].forEach((ev) =>
    drop.addEventListener(ev, (e) => {
      e.preventDefault();
      drop.classList.remove("active");
    })
  );
  drop.addEventListener("drop", (e) => {
    const f = e.dataTransfer.files && e.dataTransfer.files[0];
    if (f) choose(f);
  });

  compileButton.addEventListener("click", async () => {
    if (!selected) return;
    compileButton.disabled = true;
    result.classList.add("hidden");
    try {
      const data = new Int8Array(await selected.arrayBuffer());
      let converted;
      try {
        converted = await convertOld(data);
      } catch (e) {
        setStatus("Conversion failed: " + (e && e.message ? e.message : e), true);
        return;
      }
      const sb3 = converted ? converted.sb3 : data;
      setStatus("Compiling " + selected.name + "…");
      const started = performance.now();
      const out = window.jscratch.compile(sb3, runtimeJar, listingCheckbox.checked);
      const ms = Math.round(performance.now() - started);
      if (!out.ok) {
        setStatus("Compilation failed: " + out.error, true);
        return;
      }
      const jarBytes = new Uint8Array(out.jar.buffer, out.jar.byteOffset, out.jar.byteLength);
      if (lastUrl) URL.revokeObjectURL(lastUrl);
      lastUrl = URL.createObjectURL(new Blob([jarBytes], { type: "application/java-archive" }));
      const baseName = selected.name.replace(/\.sb[23]?$/i, "");
      download.href = lastUrl;
      download.download = baseName + ".jar";

      if (lastSb3Url) URL.revokeObjectURL(lastSb3Url);
      lastSb3Url = null;
      downloadSb3.classList.toggle("hidden", !converted);
      if (converted) {
        lastSb3Url = URL.createObjectURL(new Blob([uint8(converted.sb3)], { type: "application/x.scratch.sb3" }));
        downloadSb3.href = lastSb3Url;
        downloadSb3.download = baseName + ".sb3";
      }

      const classes = out.classes ? out.classes.split("\n").filter(Boolean) : [];
      summary.textContent =
        (converted ? "Converted from " + converted.label + " in " + converted.ms + " ms. " : "") +
        "Compiled in " + ms + " ms: " + classes.length + " classes, " + Math.round(jarBytes.length / 1024) + " KiB JAR.";

      targetsBody.innerHTML = "";
      (out.targets ? out.targets.split("\n").filter(Boolean) : []).forEach((line) => {
        const tr = document.createElement("tr");
        line.split("\t").forEach((cell) => {
          const td = document.createElement("td");
          td.textContent = cell;
          tr.appendChild(td);
        });
        targetsBody.appendChild(tr);
      });

      const warnings = (converted ? converted.warnings : []).concat(out.warnings ? out.warnings.split("\n").filter(Boolean) : []);
      warningCount.textContent = String(warnings.length);
      warningsOut.textContent = warnings.join("\n");

      listingOut.textContent = out.listing || "";
      listingDetails.classList.toggle("hidden", !listingCheckbox.checked);

      result.classList.remove("hidden");
      setStatus("Done.");
    } catch (e) {
      setStatus("Compilation failed: " + e, true);
    } finally {
      updateButton();
    }
  });

  updateButton();
})();
