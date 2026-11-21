// Optional Node-API byte scans for tool output and saved subagent previews.
#include <node_api.h>

static bool input_bytes(napi_env env, napi_callback_info info, const uint8_t **bytes, size_t *length) {
    napi_value input;
    size_t argc = 1;
    napi_typedarray_type type;
    void *data;
    if (napi_get_cb_info(env, info, &argc, &input, NULL, NULL) != napi_ok ||
        argc != 1 || napi_get_typedarray_info(env, input, &type, length, &data, NULL, NULL) != napi_ok ||
        type != napi_uint8_array) {
        napi_throw_type_error(env, NULL, "Expected a Uint8Array");
        return false;
    }
    *bytes = data;
    return true;
}

static napi_value count_line_feeds(napi_env env, napi_callback_info info) {
    const uint8_t *bytes;
    size_t length;
    napi_value result;
    if (!input_bytes(env, info, &bytes, &length)) return NULL;
    size_t lines = 0;
    while (length) {
        // A 16-bit sum gives the compiler narrower SIMD lanes. Each block fits
        // even when every byte is a newline.
        size_t block = length > UINT16_MAX ? UINT16_MAX : length;
        uint16_t subtotal = 0;
        for (size_t i = 0; i < block; i++) subtotal += bytes[i] == '\n';
        lines += subtotal;
        bytes += block;
        length -= block;
    }
    if (napi_create_double(env, (double)lines, &result) != napi_ok) return NULL;
    return result;
}

static bool is_hex_digit(uint8_t c) {
    return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
}

// Start inside a JSON string, immediately after a complete unit. Non-ASCII bytes,
// including malformed UTF-8, decode to non-structural characters or U+FFFD.
static size_t string_body_end(const uint8_t *bytes, size_t length) {
    size_t i = 0;
    while (i < length) {
        // The reduction vectorizes on arm64 and x86 without unaligned or out-of-range loads.
        if (length - i >= 64) {
            uint8_t special = 0;
            for (size_t j = 0; j < 64; j++) {
                uint8_t c = bytes[i + j];
                special |= (c < 0x20) | (c == '"') | (c == '\\');
            }
            if (!special) {
                i += 64;
                continue;
            }
        }
        // Process the rest of this group, including whole escapes crossing the group boundary.
        size_t stop = i + (length - i < 64 ? length - i : 64);
        while (i < stop) {
            uint8_t c = bytes[i];
            if (c < 0x20 || c == '"') return i;
            if (c != '\\') {
                i++;
                continue;
            }
            if (length - i < 2) return i;
            switch (bytes[i + 1]) {
                case '"': case '\\': case '/': case 'b': case 'f': case 'n': case 'r': case 't':
                    i += 2;
                    break;
                case 'u':
                    if (length - i < 6) return i;
                    for (size_t j = 2; j < 6; j++) if (!is_hex_digit(bytes[i + j])) return i;
                    i += 6;
                    break;
                default:
                    return i;
            }
        }
    }
    return i;
}

static napi_value json_string_body_end(napi_env env, napi_callback_info info) {
    const uint8_t *bytes;
    size_t length;
    napi_value result;
    if (!input_bytes(env, info, &bytes, &length)) return NULL;
    if (napi_create_double(env, (double)string_body_end(bytes, length), &result) != napi_ok) return NULL;
    return result;
}

NAPI_MODULE_INIT() {
    napi_value fn, scan;
    if (napi_create_function(env, "countLineFeeds", NAPI_AUTO_LENGTH, count_line_feeds, NULL, &fn) != napi_ok)
        return NULL;
    if (napi_create_function(env, "jsonStringBodyEnd", NAPI_AUTO_LENGTH, json_string_body_end, NULL, &scan) != napi_ok ||
        napi_set_named_property(env, fn, "jsonStringBodyEnd", scan) != napi_ok) return NULL;
    return fn;
}
