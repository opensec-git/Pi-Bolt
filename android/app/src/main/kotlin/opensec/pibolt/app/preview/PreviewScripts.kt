package opensec.pibolt.app.preview

import kotlinx.serialization.json.JsonPrimitive

/**
 * The JavaScript the preview runs for Pi's tools, through `WebView.evaluateJavascript` in the page's main frame. Each
 * snippet is one expression whose value is a plain object (`{ok, …}` or `{ok: false, error}`); arguments are embedded
 * as JSON literals ([lit]), never spliced as code. Clicks and typing are DOM events, not touches.
 */
object PreviewScripts {
    /** [value] as a JavaScript string literal. */
    fun lit(value: String): String = JsonPrimitive(value).toString()

    private fun num(value: Double): String = if (value.isFinite()) value.toString() else "0"

    /** A short description of an element (`button#save.primary "Save"`) and the event sequence of a click. */
    private const val HELPERS = """
const d = (el) => {
  if (!el || !el.tagName) return String(el);
  let s = el.tagName.toLowerCase();
  if (el.id) s += '#' + el.id;
  const c = (typeof el.className === 'string' ? el.className : '').trim().split(/\s+/).filter(Boolean).slice(0, 3);
  if (c.length) s += '.' + c.join('.');
  const t = String(el.innerText || el.value || el.getAttribute('aria-label') || el.getAttribute('alt') || '').trim().replace(/\s+/g, ' ').slice(0, 60);
  return t ? s + ' "' + t + '"' : s;
};
const q = (sel) => { try { return { el: document.querySelector(sel) }; } catch (e) { return { error: 'Invalid selector ' + sel + ': ' + e.message }; } };
const fire = (el, x, y) => {
  const o = { bubbles: true, cancelable: true, composed: true, view: window, clientX: x, clientY: y, button: 0 };
  const P = window.PointerEvent || MouseEvent;
  el.dispatchEvent(new P('pointerdown', Object.assign({ pointerId: 1, isPrimary: true, pointerType: 'touch' }, o)));
  el.dispatchEvent(new MouseEvent('mousedown', o));
  if (typeof el.focus === 'function') el.focus({ preventScroll: true });
  el.dispatchEvent(new P('pointerup', Object.assign({ pointerId: 1, isPrimary: true, pointerType: 'touch' }, o)));
  el.dispatchEvent(new MouseEvent('mouseup', o));
  el.dispatchEvent(new MouseEvent('click', o));
};
"""

    private fun wrap(body: String): String = "(() => {\n$HELPERS\n$body\n})()"

    fun clickSelector(selector: String): String = wrap(
        """
const r = q(${lit(selector)});
if (r.error) return { ok: false, error: r.error };
const el = r.el;
if (!el) return { ok: false, error: 'No element matches ' + ${lit(selector)} };
el.scrollIntoView({ block: 'center', inline: 'center' });
const b = el.getBoundingClientRect();
fire(el, b.left + b.width / 2, b.top + b.height / 2);
return { ok: true, target: d(el) };
""",
    )

    /** A click at fractions [fx], [fy] of the viewport ([ScreenshotScale.fraction]). */
    fun clickAt(fx: Double, fy: Double): String = wrap(
        """
const x = ${num(fx)} * window.innerWidth, y = ${num(fy)} * window.innerHeight;
const el = document.elementFromPoint(x, y);
if (!el) return { ok: false, error: 'Nothing is at that point' };
fire(el, x, y);
return { ok: true, target: d(el), x: Math.round(x), y: Math.round(y) };
""",
    )

    /**
     * Replaces the value of an input, textarea, select or contenteditable with [text] the way a framework notices (the
     * prototype's value setter, then `input` and `change`), and on [submit] presses Enter and submits its form.
     */
    fun type(selector: String, text: String, submit: Boolean): String = wrap(
        """
const r = q(${lit(selector)});
if (r.error) return { ok: false, error: r.error };
const el = r.el;
if (!el) return { ok: false, error: 'No element matches ' + ${lit(selector)} };
const text = ${lit(text)};
el.scrollIntoView({ block: 'center' });
if (typeof el.focus === 'function') el.focus();
if (el.isContentEditable) {
  document.execCommand('selectAll', false, null);
  document.execCommand('insertText', false, text);
} else if (el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement || el instanceof HTMLSelectElement) {
  const proto = el instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : el instanceof HTMLSelectElement ? HTMLSelectElement.prototype : HTMLInputElement.prototype;
  const set = Object.getOwnPropertyDescriptor(proto, 'value').set;
  set.call(el, text);
  el.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: 'insertText', data: text }));
  el.dispatchEvent(new Event('change', { bubbles: true }));
} else {
  return { ok: false, error: d(el) + ' is not a text field' };
}
let submitted = false;
if (${submit}) {
  for (const t of ['keydown', 'keypress', 'keyup']) el.dispatchEvent(new KeyboardEvent(t, { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true }));
  const f = el.form || (el.closest && el.closest('form'));
  if (f) { if (f.requestSubmit) f.requestSubmit(); else f.submit(); submitted = true; }
}
return { ok: true, target: d(el), submitted };
""",
    )

    fun scroll(selector: String?, dy: Double?, to: String?): String = wrap(
        """
const sel = ${selector?.let(::lit) ?: "null"};
let target = null;
if (sel) {
  const r = q(sel);
  if (r.error) return { ok: false, error: r.error };
  if (!r.el) return { ok: false, error: 'No element matches ' + sel };
  r.el.scrollIntoView({ block: 'center', inline: 'nearest' });
  target = d(r.el);
}
const to = ${to?.let(::lit) ?: "null"};
const root = document.scrollingElement || document.documentElement;
if (to === 'top') window.scrollTo(0, 0);
if (to === 'bottom') window.scrollTo(0, root.scrollHeight);
${dy?.let { "window.scrollBy(0, ${num(it)});" }.orEmpty()}
return { ok: true, target, scrollY: Math.round(window.scrollY), scrollHeight: root.scrollHeight, viewport: window.innerHeight };
""",
    )

    /**
     * Evaluates [js] in the page's global scope; the value comes back as JSON text (elements described). This is the
     * point of `preview_eval`: it runs only in the project's own dev page on loopback (the controller checks the page's
     * origin first), code the agent could already change in the project's files.
     */
    fun eval(js: String): String = wrap(
        """
let v;
try { v = (0, eval)(${lit(js)}); } catch (e) { return { ok: false, error: String((e && e.stack) || e) }; }
if (v && typeof v.then === 'function') return { ok: true, value: '(a Promise: preview_eval does not await)' };
let s;
try { s = JSON.stringify(v, (k, x) => (x instanceof Node ? d(x) : x)); } catch (e) { s = String(v); }
return { ok: true, value: s === undefined ? String(v) : s };
""",
    )

    /** The page's address, title and viewport, for result texts. */
    const val PAGE_INFO = "(() => ({ ok: true, title: document.title, width: window.innerWidth, height: window.innerHeight, ready: document.readyState }))()"

    /**
     * Run at document start (WebViewCompat.addDocumentStartJavaScript) on loopback pages: uncaught errors and unhandled
     * rejections reported with their stacks as a console error that starts with [ConsoleBuffer.HOOK_MARK].
     */
    val ERROR_HOOK = """
(() => {
  if (window.__piboltHook || !/^(localhost|127\.0\.0\.1)$/.test(location.hostname)) return;
  window.__piboltHook = true;
  const mark = ${lit(ConsoleBuffer.HOOK_MARK)};
  const send = (o) => { try { console.error(mark + JSON.stringify(o)); } catch (e) {} };
  addEventListener('error', (e) => {
    if (e.target && e.target !== window) return;
    send({ message: e.message, source: e.filename, line: e.lineno, stack: (e.error && e.error.stack) || null });
  }, true);
  addEventListener('unhandledrejection', (e) => {
    const r = e.reason;
    send({ message: 'Uncaught (in promise) ' + (r && r.message ? (r.name ? r.name + ': ' : '') + r.message : String(r)), stack: (r && r.stack) || null });
  });
})();
""".trimIndent()
}
