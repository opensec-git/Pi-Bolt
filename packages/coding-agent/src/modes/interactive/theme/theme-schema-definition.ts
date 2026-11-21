/** Theme schema shared by the build-time generator and runtime diagnostics. */
import type { Static, TProperties } from "typebox";
import { loadTypeBox } from "../../../core/typebox-lazy.ts";
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

/** The schema used to generate the startup check, publish the JSON Schema, and explain invalid documents. */
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
