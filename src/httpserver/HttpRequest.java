package httpserver;
import java.util.*;
public class HttpRequest {
    private String method;
    private String path;
    private String version;
    private final Map<String, String> headers = new HashMap<>();

    public String getMethod(){
        return method;
    }
    public void setmethod(String method){
        this.method=method;
    }
    public String getPath(){
        return path;
    }

    public void setPath(String path){
        this.path=path;
    }
    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public void addHeader(String name, String value) {
        // Header names are case-insensitive per HTTP spec — normalize to lowercase as the key
        headers.put(name.toLowerCase(), value);
    }

    public String getHeader(String name) {
        return headers.get(name.toLowerCase());
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public String toString() {
        return "HttpRequest{method='" + method + "', path='" + path
                + "', version='" + version + "', headers=" + headers + "}";
    }
}
