# Core Interface — Frozen as of Phase 3

This is the interface the rest of the team can build against. `HttpRequest` and
`HttpRequestParser` are stable — the method signatures below will not change
without a heads-up to the team. Static file serving, response writing, and
keep-alive logic (Phases 4–6) are still in progress and don't affect this
interface.

## Package
Everything lives in `httpserver` (`src/httpserver/`).

## `HttpRequest`

Represents one parsed HTTP request.

```java
HttpRequest request = HttpRequestParser.parse(headersOnlyString);

request.getMethod();          // e.g. "GET", "HEAD"
request.getPath();            // e.g. "/index.html"
request.getVersion();         // e.g. "HTTP/1.1"
request.getHeader("Host");    // case-insensitive lookup -> "localhost"
request.getHeaders();         // Map<String, String>, all keys lowercased
```

Notes:
- Header lookups are case-insensitive — `getHeader("Host")` and
  `getHeader("host")` return the same value.
- No body parsing yet (Content-Length / chunked handling comes later —
  irrelevant for GET/HEAD, relevant once proxy/POST support is needed).

## `HttpRequestParser`

```java
HttpRequest request = HttpRequestParser.parse(rawHeaderText);
```

- Input: raw request text from the request line through the headers
  (everything before the `\r\n\r\n` blank line — **not including** the
  blank line or body).
- Throws `IllegalArgumentException` if the request line doesn't have
  exactly 3 space-separated parts (method, path, version). Callers should
  catch this and respond with `400 Bad Request`.

## How to get a complete request off the wire

The parser expects a *complete* header block as input. Don't call it on a
single `read()` result — a request can arrive split across multiple TCP
reads. Buffer bytes into an accumulator and only call the parser once your
accumulated text contains `"\r\n\r\n"`. See `ConnectionHandler.java` for
the reference implementation of this buffering loop — copy the pattern
into your own concurrency model (thread pool / event loop) rather than
reinventing it, since it's the part most likely to have subtle bugs.

## What's still coming (don't block on these — build against the interface above)

- Static file serving, MIME detection, directory traversal protection (Phase 4)
- Full status code handling, Content-Length on responses (Phase 5)
- Persistent connections / keep-alive (Phase 6)
- Chunked transfer encoding (Phase 7)

If you need something from `HttpRequest` that isn't there yet (e.g. body
access), flag it in the group chat rather than modifying the class
yourself — since it's shared, changes should be coordinated so we don't
silently break each other's branches.