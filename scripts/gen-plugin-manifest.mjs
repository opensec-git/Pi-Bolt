#!/usr/bin/env node
/**
 * Writes the plugin folder that `build-pi.sh --plugins` takes, from the extensions installed on this machine: the command of
 * packages/coding-agent/src/gen-plugin-manifest.mjs, which says what it does and how (--help for the options).
 */
import { main } from "../packages/coding-agent/src/gen-plugin-manifest.mjs";

await main(process.argv.slice(2));
