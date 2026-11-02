AI INTEGRATION



24051237



1\. Files Implemented Independently

The following files were initially written independently by me:



Server.java – Implements the main server functionality, including creating a ServerSocket, listening for incoming client connections, accepting connections, and creating separate threads for handling clients.



RequestReader.java – Reads incoming HTTP request headers from the client's InputStream using buffering.



HttpRequest.java – Defines the structure of an HTTP request and stores request information such as method, path, version, and headers.



HttpRequestParser.java – Parses raw HTTP request headers, extracts request information, and performs basic request validation.



HttpParseException.java – Implements a custom exception class for handling HTTP parsing errors with appropriate status codes and reason messages.



2\. Files Developed with AI Assistance



The following files were completely generated with AI assistance:



StaticFileHandler.java – Handles locating, resolving, and reading static files from the server's document root. It also handles MIME type detection for different file extensions.



HttpResponse.java – Handles the construction and transmission of HTTP responses, including status codes, response headers, response bodies, and chunked transfer encoding.



3\. Files Initially Written by Me and Later Enhanced with AI



ConnectionHandler.java – The initial implementation was written independently by me. AI assistance was later used to modify and enhance the implementation.



The AI-assisted modifications included:



Integration of static file serving functionality.



Implementation of chunked transfer encoding for HTTP/1.1 streaming.



Handling of streaming responses for HTTP/1.0 by collecting chunks and sending them as a normal response.



Integration of response handling, persistent connections (Keep-Alive), and related request-processing logic.



Testing and Test Cases



All test cases were suggested and provided with AI assistance.



The test cases were executed manually by me. I manually tested the server's functionality, observed its responses, and verified its behaviour under different request conditions. AI was used to help identify relevant testing scenarios, while the actual execution and verification of the test cases were performed manually.



24051236

I implemented the following main components:

- Server.java: Implemented the server-side concurrency model using Java's ThreadPoolExecutor to handle multiple client connections simultaneously.

- ConnectionHandler.java: Implemented connection handling using worker threads, allowing the server to process incoming client requests.

- Thread Pool Management: Implemented configurable thread pool size, bounded task queue, and rejection handling when the server reaches its capacity.

- Server Shutdown: Implemented graceful shutdown using a shutdown hook to close the server socket and terminate worker threads safely.

- BenchmarkRunner.java: Implemented a benchmarking harness to measure server performance at different concurrency levels.

- LoadGenerator.java: Implemented concurrent request generation to test server performance under different loads.

- Stats.java: Implemented performance statistics to calculate requests per second (RPS) and latency percentiles (p50, p95, p99).



2\. Parts Implemented with AI Assistance

- Benchmark and Test Codes: Used AI assistance for developing and refining benchmarking and testing code.

- Performance Measurement: Used AI assistance for implementing latency calculations, concurrency testing, and performance result analysis.



24051235

Parts Implemented by Anantika

I implemented the following main components as part of my individual responsibility:



NioEventLoopServer.java: Implemented the server-side concurrency model using Java NIO, with Selector, non-blocking ServerSocketChannel, and SocketChannel to manage multiple client connections through a single event-loop thread.

Selector-Based Event Loop: Implemented the event loop to monitor and process OP\_ACCEPT, OP\_READ, and OP\_WRITE events, allowing the server to accept new connections, read incoming requests, and send responses without relying on a separate blocking thread for every client.

Non-Blocking Connection Management: Implemented client connection handling using non-blocking socket channels and registered connections with the Selector to monitor their readiness for different I/O operations.

Per-Connection State Management: Implemented a separate ConnectionState for each client to maintain its input buffer, accumulated request data, pending response buffers, and connection status.

Partial Request Handling: Implemented buffering of incoming data across multiple read events so that incomplete HTTP requests can be preserved and processed when the remaining data arrives, rather than assuming that a complete request is received in a single read operation.

Response Buffer Management: Implemented a queue of pending response buffers to support non-blocking response transmission and handle situations where a response cannot be completely written in one operation.

HTTP Request Processing Integration: Integrated the NIO event-loop server with the team's existing HTTP request parser and static-file handler to process client requests and serve static resources.

2\. Parts Implemented with AI Assistance

NIO Implementation Guidance: Used AI assistance to understand Java NIO concepts, including Selector, non-blocking channels, SelectionKey operations, and event-loop architecture.

Partial Request Handling and Debugging: Used AI assistance to understand TCP stream behavior, per-connection buffering, and handling incomplete HTTP requests across multiple read operations.

Benchmark Development and Analysis: Used AI assistance while developing and refining the benchmarking harness, understanding latency calculations, evaluating concurrency levels, and interpreting performance results.

Testing and Validation: Used AI assistance to prepare test scripts, debug implementation issues, and validate the NIO server's functionality, including GET, HEAD, error responses, partial requests, and persistent connections.

24055342



1\. Parts Implemented by 24155342



I implemented the following main components:



LruCache.java: Implemented LRU caching using a HashMap and Doubly Linked List.



CachePolicy.java: Implemented the rules for deciding whether a response can be cached and how long it stays fresh.



CacheEntry.java: Implemented the storage of cached response details and freshness information.



2\. Parts Implemented with AI Assistance



ProxyHandler.java: Developed with a combination of my own understanding and AI assistance.



ResponseReader.java: Used AI assistance for implementation.



Benchmark and Test Codes: Used AI assistance for writing the benchmarking and testing code.

