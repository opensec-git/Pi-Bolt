// The old plugin's server facet; the example deletes the plugin's directory.

import { constant, defineFacet } from "@earendil-works/chord/next";
import { labelOf } from "@example/shared/contract";

export default defineFacet((host) => host.serve(labelOf("old"), () => ({ text: constant("old on the server") })));
