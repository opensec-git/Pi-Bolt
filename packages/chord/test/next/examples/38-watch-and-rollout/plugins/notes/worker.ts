// The notes plugin's worker facet: its label.

import { constant, defineFacet } from "@earendil-works/chord/next";
import { labelOf } from "@example/shared/contract";
import { label } from "./label.ts";

export default defineFacet((host) =>
	host.serve(labelOf("notes"), () => ({ text: constant(`${label} on the worker`) })),
);
