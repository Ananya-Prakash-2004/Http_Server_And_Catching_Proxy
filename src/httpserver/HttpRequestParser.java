package httpserver;
public class HttpRequestParser{
    public static HttpRequest parser(String rawRequest){
        HttpRequest request = new HttpRequest();

        String[] lines = rawRequest.split("\r\n");
        if(lines.length == 0){
            throw new IllegalArgumentException("Empty Request");

        }
        String requestLine = lines[0];
        String[] requestLineParts = requestLine.split(" ");

        if(requestLineParts.length !=3){
            throw new IllegalArgumentException("Malformed request line: "+ requestLine);
        }

        request.setmethod(requestLineParts[0]);
        request.setPath(requestLineParts[1]);
        request.setVersion(requestLineParts[2]);

        for(int i=1;i<lines.length;i++){
            String line = lines[i];

            if(line.isEmpty()){
                break;
            }
            String[] headerParts = line.split(":",2);
            if(headerParts.length!=2){
                continue;
            }

            String name = headerParts[0].trim();
            String value = headerParts[1].trim();
            request.addHeader(name, value);
        }
        return request;
    }
}