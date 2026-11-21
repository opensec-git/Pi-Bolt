/**
 * Theme JSON validation, kept out of `theme.ts` on purpose.
 *
 * The CLI installs this validator before loading user themes. The generated check validates each document.
 * An invalid document loads TypeBox's diagnostic formatter to explain the error.
 */

import { checkThemeDocument } from "../../../core/config-checks.generated.ts";
import { loadTypeBoxValue } from "../../../core/typebox-lazy.ts";
import { getThemeSchemas, type ValidatedThemeJson } from "./theme-schema-definition.ts";

export { getThemeSchemas, type ThemeColorValue, type ValidatedThemeJson } from "./theme-schema-definition.ts";

/** Validate one theme document, throwing a message that names the offending tokens. */
export function validateThemeJson(label: string, json: unknown): ValidatedThemeJson {
	if (
		typeof json === "object" &&
		json !== null &&
		"name" in json &&
		typeof json.name === "string" &&
		json.name.includes("/")
	) {
		throw new Error(
			`Invalid theme name "${json.name}": theme names cannot contain "/" because it is reserved for automatic light/dark theme settings.`,
		);
	}

	if (!checkThemeDocument(json)) {
		const { ThemeJsonSchema } = getThemeSchemas();
		const { Value } = loadTypeBoxValue();
		const errors = Array.from(Value.Errors(ThemeJsonSchema, json));
		const missingColors = new Set<string>();
		const otherErrors: string[] = [];

		for (const error of errors) {
			if (error.keyword === "required" && error.instancePath === "/colors") {
				const requiredProperties = (error.params as { requiredProperties?: string[] }).requiredProperties;
				for (const requiredProperty of requiredProperties ?? []) {
					missingColors.add(requiredProperty);
				}
				continue;
			}

			const path = error.instancePath || "/";
			otherErrors.push(`  - ${path}: ${error.message}`);
		}

		let errorMessage = `Invalid theme "${label}":\n`;
		if (missingColors.size > 0) {
			errorMessage += "\nMissing required color tokens:\n";
			errorMessage += Array.from(missingColors)
				.sort()
				.map((color) => `  - ${color}`)
				.join("\n");
			errorMessage += '\n\nPlease add these colors to your theme\'s "colors" object.';
			errorMessage += "\nSee the built-in themes (dark.json, light.json) for reference values.";
		}
		if (otherErrors.length > 0) {
			errorMessage += `\n\nOther errors:\n${otherErrors.join("\n")}`;
		}

		throw new Error(errorMessage);
	}

	return json as ValidatedThemeJson;
}
