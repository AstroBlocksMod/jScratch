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
  const warningsOut = document.getElementById("warnings");
  const warningCount = document.getElementById("warning-count");
  const listingOut = document.getElementById("listing-out");
  const listingDetails = document.getElementById("listing-details");
  const targetsBody = document.querySelector("#targets tbody");

  let runtimeJar = null;
  let selected = null;
  let lastUrl = null;

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
    dropText.textContent = file ? file.name + " (" + Math.round(file.size / 1024) + " KiB)" : "Drop an .sb3 file here or click to choose one";
    updateButton();
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
    setStatus("Compiling " + selected.name + "…");
    result.classList.add("hidden");
    try {
      const sb3 = new Int8Array(await selected.arrayBuffer());
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
      download.href = lastUrl;
      download.download = selected.name.replace(/\.sb3$/i, "") + ".jar";

      const classes = out.classes ? out.classes.split("\n").filter(Boolean) : [];
      summary.textContent =
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

      const warnings = out.warnings ? out.warnings.split("\n").filter(Boolean) : [];
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
