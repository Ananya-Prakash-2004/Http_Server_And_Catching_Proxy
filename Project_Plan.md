# Project Plan — HTTP/1.1 Server & Forward Caching Proxy

**Course:** Computer Networks (CS-30003), Project P1
**Team:** 4 members
**Language:** Java, raw sockets only (no frameworks — no `http.server`, Flask, FastAPI, Express, Netty, `net/http`, or Apache/Nginx as the server)

## Team split

| Owner | Part | Depends on |
|---|---|---|
| Prakash (this branch) | Core: TCP scaffold, request parser (buffering/state machine), static file serving, status codes, persistent connections, chunked encoding | — (foundation) |
| Member 2 | Thread pool concurrency model + benchmarking | Core parser |
| Member 3 | Event loop concurrency model (NIO `Selector`) + benchmarking | Core parser |
| Member 4 | Forward proxy + LRU cache + conditional GET + cache benchmarking | Core parser |

Interface contract: `HttpRequest` / `HttpResponse` class shapes must be frozen once Phase 3 is done, so Members 2–4 can build against a stable interface.

---

## Phase 1 — Bare TCP scaffold
**Goal:** Accept a connection, read raw bytes, write raw bytes back. No HTTP logic yet.
- `Server.java`: `ServerSocket`, accept loop, one thread per connection (temporary)
- `ConnectionHandler.java`: read whatever bytes arrive, print them, send a dummy response
- Prove the core problem: send a request split across two `write()` calls and watch a single `read()` only capture part of it

## Phase 2 — Buffering layer
**Goal:** Solve "TCP is a byte stream, not messages" before any parsing logic exists.
- Growable buffer per connection (`ByteArrayOutputStream` or similar), persisting across multiple `read()` calls
- Detect "have I received the full header block yet?" (look for `\r\n\r\n`)
- Leftover-bytes handling: after extracting one complete unit, keep the remainder in the buffer for the next parse (enables pipelining later)
- Test: split a request across multiple writes with a delay between them; confirm reassembly works

## Phase 3 — Request line + header parsing → `HttpRequest`
**Goal:** Turn raw bytes into a structured object the rest of the team codes against.
- State machine: `READING_REQUEST_LINE → READING_HEADERS → READING_BODY → COMPLETE`
- Parse method, path, HTTP version from the request line
- Parse headers into a case-insensitive map
- Validation → status codes: 400 (bad request line), 414 (URI too long), 431 (headers too large), 505 (bad HTTP version)
- **Freeze the interface here** for Members 2–4

## Phase 4 — GET/HEAD + static file serving
**Goal:** Actually serve files.
- Map URL path → filesystem path under a document root
- Directory traversal protection: canonicalize the resolved path, reject anything escaping the doc root
- MIME type detection (extension → content-type lookup table)
- GET returns headers + body; HEAD returns headers only
- Status codes: 200, 404, 405

## Phase 5 — Response writing: Content-Length, remaining status codes
**Goal:** Correct, well-formed responses.
- Accurate `Content-Length` for known-size bodies
- Remaining status codes: 200, 304, 400, 404, 405, 414, 431, 500, 505
- Catch-all exception handling → 500 without crashing the server

## Phase 6 — Persistent connections (keep-alive)
**Goal:** Don't close the socket after one request.
- Honor `Connection: keep-alive` (default in HTTP/1.1) vs `Connection: close`
- Loop back to parsing the next request on the same socket
- Exercises the Phase 2 leftover-buffer logic for real (pipelined requests on one connection)

## Phase 7 — Chunked transfer encoding
**Goal:** Support responses of unknown length.
- Format: `<hex-size>\r\n<data>\r\n` repeated, ending with `0\r\n\r\n`
- Needed for streaming / once gzip (stretch) is added

## Phase 8 — Integration
**Goal:** Hand off a stable base to the rest of the team.
- Freeze `HttpRequest` / `HttpResponse` shapes
- Merge `prakash` → `main`
- Members 2–4 branch their concurrency models / proxy off this stable base

---

## Downstream parts (owned by other members)

- **Concurrency models:** thread pool (`ExecutorService`) and event loop (NIO `Selector`, epoll-backed on Linux) — both benchmarked against each other (requests/sec, latency percentiles vs. concurrency level)
- **Forward proxy + LRU cache:** honors `Cache-Control`, `ETag`, `If-Modified-Since`, conditional GET; cache hit ratio and latency savings measured under a realistic (e.g. Zipfian) request distribution

## Stretch goals
HTTP range requests · gzip content encoding · minimal TLS wrapper · HTTP/2 framing layer