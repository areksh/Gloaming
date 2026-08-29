import { build } from "esbuild";

/**
 * Bundles everything into one file that Node can run on its own.
 *
 * The point is the deployment: a seedbox needs a single artefact copied into
 * place, not a source tree, a package manager and a build toolchain it would
 * then have to keep patched. What both components have to agree on - the health
 * contract, and how a failed request is described - is pulled in from the
 * Worker's source at build time, so there is exactly one copy of it in the
 * project however many places run it.
 */
await build({
  entryPoints: ["src/main.ts"],
  outfile: "dist/gloaming-elements.mjs",
  bundle: true,
  platform: "node",
  target: "node20",
  format: "esm",
  banner: { js: "// Built from the Gloaming repository. Do not edit here; rebuild and redeploy." },
});

console.log("built dist/gloaming-elements.mjs");
