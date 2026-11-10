/**
 * Theme JSON validation, kept out of `theme.ts` on purpose.
 *
 * Validating user-authored theme files needs typebox, which costs ~17 MB of module graph and millions of instructions to
 * evaluate. Palette lookup does not, so a presentation that only uses built-in themes should never pay for it: TypeBox is
 * loaded on the first validation (typebox-lazy.ts), not with this module. `main.ts` installs this validator before
 * runtime resource loading; other consumers can opt in with `setThemeJsonValidator()`. Built-in themes do not need
 * validation.
 */

import type { Static, TProperties } from "typebox";
import { loadTypeBox, loadTypeBoxValue } from "../../../core/typebox-lazy.ts";
import { THEME_TOKENS, type ThemeColorValues, type ThemeTokenDescriptors } from "./theme-tokens.ts";

function colorValue(description?: string) {
	const { Type } = loadTypeBox();
	return Type.Union(
		[
			Type.String({
				description:
					"Hex color (#RGB or #RRGGBB), OKLCH or OKHSL color, variable reference, or empty string for terminal default",
			}),
			Type.Integer({
				minimum: 0,
				maximum: 255,
				description: "256-color palette index (0-255)",
			}),
		],
		description ? { description } : {},
	);
}

function themeTokenProperties(tokens: ThemeTokenDescriptors): TProperties {
	const { Type } = loadTypeBox();
	const properties: TProperties = {};
	for (const [name, descriptor] of Object.entries(tokens)) {
		const schema = colorValue(descriptor.description);
		properties[name] = descriptor.fallback === undefined ? schema : Type.Optional(schema);
	}
	return properties;
}

/**
 * The schemas, built on first use rather than when this module loads: `main.ts` imports `validateThemeJson` at every start,
 * and only a user-authored theme needs it. Themes are checked with `Value.Check()` against the schema as it is: `Compile()`
 * would generate a checker as source code first.
 */
function buildThemeSchemas() {
	const { Type } = loadTypeBox();
	const ColorValueSchema = colorValue();
	const ThemeColorsSchema = Type.Unsafe<ThemeColorValues<ThemeColorValue>>(
		Type.Object(themeTokenProperties(THEME_TOKENS), {
			description:
				"Theme color definitions (scrollbar, thinkingMax, search highlight, and mdCodeBlockBg colors are optional and use compatible fallbacks)",
			additionalProperties: false,
		}),
	);

	const ThemeJsonSchema = Type.Object(
		{
			$schema: Type.Optional(Type.String({ description: "JSON schema reference" })),
			name: Type.String({
				pattern: "^[^/]+$",
				description:
					"Theme name. Must not contain '/' because it is reserved for automatic light/dark theme settings.",
			}),
			appearance: Type.Optional(
				Type.Union([Type.Literal("dark"), Type.Literal("light")], {
					description: "Background the theme is designed for. Detected from the theme colors when omitted.",
				}),
			),
			vars: Type.Optional(
				Type.Record(Type.String(), ColorValueSchema, {
					description: "Reusable color variables",
				}),
			),
			colors: ThemeColorsSchema,
			export: Type.Optional(
				Type.Object(
					{
						pageBg: Type.Optional(colorValue("Page background color")),
						cardBg: Type.Optional(colorValue("Card/container background color")),
						infoBg: Type.Optional(colorValue("Info sections background (system prompt, notices)")),
					},
					{
						description: "Optional colors for HTML export (defaults derived from userMessageBg if not specified)",
						additionalProperties: false,
					},
				),
			),
		},
		{
			title: "Pi Coding Agent Theme",
			description: "Theme schema for Pi coding agent",
			additionalProperties: false,
		},
	);
	return { ColorValueSchema, ThemeJsonSchema };
}

let themeSchemas: ReturnType<typeof buildThemeSchemas> | undefined;

/** The theme schemas (for the published JSON Schema and for validation). */
export function getThemeSchemas(): ReturnType<typeof buildThemeSchemas> {
	themeSchemas ??= buildThemeSchemas();
	return themeSchemas;
}

/** A color: a string (hex, OKLCH or OKHSL, a variable, or "" for the terminal default) or a 256-color palette index. */
export type ThemeColorValue = string | number;
export type ValidatedThemeJson = Static<ReturnType<typeof buildThemeSchemas>["ThemeJsonSchema"]>;

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

	const { ThemeJsonSchema } = getThemeSchemas();
	const { Value } = loadTypeBoxValue();
	if (!Value.Check(ThemeJsonSchema, json)) {
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
