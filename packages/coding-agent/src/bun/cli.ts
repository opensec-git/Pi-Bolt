#!/usr/bin/env node
import "./sandbox-env-setup.ts";
import "./version-fast-path.ts";
import { loadMain } from "./main.lazy.ts";

// The rest of Pi is imported, not loaded with this module: a build that splits its code (scripts/build-pi.sh) then makes it a
// chunk of its own, whose functions `pi --version` never creates.
await loadMain();
