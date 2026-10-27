package io.github.b077as.jolt.internal;

/**
 * A page-side script that turns request bodies a web view cannot hand to an in-process scheme into plain bytes before they leave
 * the page, so application code does not have to know.
 *
 * <p>WebKitGTK aborts the whole process when a request to an application-defined scheme carries a body that contains a
 * {@code Blob} (a {@code fetch} with a {@code Blob}, a {@code FormData} with a file or {@code Blob} in it, a multipart form with a
 * file input), even a few bytes. Bodies made of strings and byte arrays are fine. The script replaces such a body with an
 * {@code ArrayBuffer} holding exactly the bytes the browser would have sent (it serialises the body with a {@code Request} object,
 * so the multipart boundary and content type come from the browser itself), and covers every way a page sends one:
 *
 * <ul>
 *   <li>{@code fetch} with a {@code Blob} or {@code FormData} body, or a {@code Request} that has a body;
 *   <li>{@code XMLHttpRequest.send} (which htmx uses), by sending a moment later, when the bytes are ready;
 *   <li>{@code navigator.sendBeacon};
 *   <li>a {@code <form method=post enctype=multipart/form-data>} that holds a file, whether submitted by the user,
 *       {@code requestSubmit()} or {@code form.submit()}: the form is sent with {@code fetch} and the answer replaces the page,
 *       which is what a navigation would have shown (a redirect included).
 * </ul>
 *
 * <p>Limits, which the README states: a <i>synchronous</i> {@code XMLHttpRequest} with a {@code Blob} cannot be converted and
 * throws a {@code NotSupportedError} instead of crashing; and a Web Worker is a separate script context this script does not reach,
 * so a worker must send bytes ({@code ArrayBuffer}), not a {@code Blob}.
 */
public final class BlobBodyShim {

    private BlobBodyShim() {}

    /** The script, which does nothing in a page whose origin is not {@code origin}. */
    public static String script(String origin) {
        return SCRIPT.replace("__ORIGIN__", JsLiteral.quote(origin));
    }

    private static final String SCRIPT = """
            (() => {
              const ORIGIN = __ORIGIN__;
              if (location.origin !== ORIGIN || window.__joltBodies) return;
              Object.defineProperty(window, '__joltBodies', { value: true });

              const isBlob = (value) => typeof Blob !== 'undefined' && value instanceof Blob;
              // a body WebKitGTK cannot give to a native scheme handler
              const needsBytes = (body) => isBlob(body)
                  || (typeof FormData !== 'undefined' && body instanceof FormData && Array.from(body.values()).some(isBlob));
              // the bytes and content type the browser itself would send for this body
              const flatten = async (body) => {
                const request = new Request(ORIGIN + '/', { method: 'POST', body });
                return { bytes: await request.arrayBuffer(), type: request.headers.get('content-type') };
              };

              const nativeFetch = window.fetch;
              window.fetch = async function (input, init) {
                if (init && needsBytes(init.body)) {
                  const { bytes, type } = await flatten(init.body);
                  const headers = new Headers(init.headers || (input instanceof Request ? input.headers : undefined));
                  if (type && !headers.has('Content-Type')) headers.set('Content-Type', type);
                  return nativeFetch.call(this, input, { ...init, body: bytes, headers });
                }
                if (input instanceof Request && input.body && !(init && init.body !== undefined)) {
                  const bytes = await input.clone().arrayBuffer();
                  return nativeFetch.call(this, new Request(input, { body: bytes }), init);
                }
                return nativeFetch.call(this, input, init);
              };

              const open = XMLHttpRequest.prototype.open;
              const send = XMLHttpRequest.prototype.send;
              const setRequestHeader = XMLHttpRequest.prototype.setRequestHeader;
              XMLHttpRequest.prototype.open = function (method, url, async) {
                this.__joltAsync = async !== false;
                this.__joltContentType = false;
                return open.apply(this, arguments);
              };
              XMLHttpRequest.prototype.setRequestHeader = function (name, value) {
                if (String(name).toLowerCase() === 'content-type') this.__joltContentType = true;
                return setRequestHeader.call(this, name, value);
              };
              XMLHttpRequest.prototype.send = function (body) {
                if (!needsBytes(body)) return send.call(this, body);
                if (!this.__joltAsync) {
                  throw new DOMException('A synchronous XMLHttpRequest cannot send a Blob or file here; use an asynchronous request, fetch, or an ArrayBuffer', 'NotSupportedError');
                }
                flatten(body).then(({ bytes, type }) => {
                  if (type && !this.__joltContentType) setRequestHeader.call(this, 'Content-Type', type);
                  send.call(this, bytes);
                }).catch((error) => {
                  console.error(error);
                  this.dispatchEvent(new ProgressEvent('error'));
                });
              };

              if (navigator.sendBeacon) {
                const beacon = navigator.sendBeacon.bind(navigator);
                navigator.sendBeacon = function (url, data) {
                  if (!needsBytes(data)) return beacon(url, data);
                  flatten(data).then(({ bytes, type }) => nativeFetch(url, {
                    method: 'POST', body: bytes, keepalive: true, headers: type ? { 'Content-Type': type } : undefined }))
                    .catch(console.error);
                  return true;
                };
              }

              // A multipart form that holds a file goes out as a fetch, and the answer replaces the page, like a navigation.
              const formJob = (form, submitter) => {
                const enctype = String((submitter && submitter.formEnctype) || form.enctype || '').toLowerCase();
                const method = String((submitter && submitter.formMethod) || form.method || 'get').toUpperCase();
                const target = (submitter && submitter.formTarget) || form.target;
                if (enctype !== 'multipart/form-data' || method === 'GET' || method === 'DIALOG' || (target && target !== '_self')) return null;
                const data = new FormData(form, submitter || undefined);
                return needsBytes(data) ? { data, method, action: (submitter && submitter.formAction) || form.action } : null;
              };
              const submitAsFetch = async ({ data, method, action }) => {
                const { bytes, type } = await flatten(data);
                // asking for a page makes a redirect arrive as a page that moves on, so the address ends up right
                const headers = { Accept: 'text/html,application/xhtml+xml,*/*;q=0.8' };
                if (type) headers['Content-Type'] = type;
                const response = await nativeFetch(action, { method, body: bytes, headers });
                const html = await response.text();
                document.open();
                document.write(html);
                document.close();
              };
              window.addEventListener('submit', (event) => {
                if (event.defaultPrevented) return; // the page (htmx, for one) handles it
                const job = formJob(event.target, event.submitter);
                if (!job) return;
                event.preventDefault();
                submitAsFetch(job).catch(console.error);
              });
              const nativeSubmit = HTMLFormElement.prototype.submit;
              HTMLFormElement.prototype.submit = function () {
                const job = formJob(this, null);
                if (!job) return nativeSubmit.call(this);
                submitAsFetch(job).catch(console.error);
              };
            })();
            """;
}
