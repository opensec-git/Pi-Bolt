// The ruler plugin's server facet: imports a file of the shapes plugin that imports a package. Refused at build: a
// copied file may import only host packages and files of its own plugin.

import { defineFacet } from "@earendil-works/chord/next";
import { Millimetres } from "@example/shapes/units";

export default defineFacet(() => {
	console.log(Millimetres);
});
