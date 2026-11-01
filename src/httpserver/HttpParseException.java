package httpserver;


public class HttpParseException extends IllegalArgumentException {
    private final int statusCode;
    private final String reason;

    public HttpParseException(int statusCode, String reason) {
        super(statusCode + " " + reason);
        this.statusCode = statusCode;
        this.reason = reason;
    }

    public int getStatusCode() { return statusCode; }
    public String getReason() { return reason; }
}