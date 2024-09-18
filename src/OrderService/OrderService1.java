import java.net.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpContext;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.sql.Statement;
import java.util.Map;
import java.util.HashMap;
import java.util.Scanner;
import com.google.gson.Gson;
import redis.clients.jedis.Jedis;




public class OrderService {

    private static String filename;

    private static Connection connection;

    private static Jedis jedis;
    private static final String REDIS_HOST = System.getenv("REDIS_HOST");
    private static final int REDIS_PORT = 6379;  // Default Redis port

    private static void initializeRedis() {
        jedis = new Jedis(REDIS_HOST, REDIS_PORT);
    }

    private static boolean isServiceReady(String serviceUrl) {
        try {
            URL url = new URL(serviceUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.connect();
    
            int responseCode = connection.getResponseCode();
            if (responseCode == 200) {  // HTTP OK
                return true;
            }
        } catch (IOException e) {
            System.out.println("Waiting for " + serviceUrl + " to be ready...");
        }
        return false;
    }

    

    //initialize database connection 
    private static void initializeConnection() throws SQLException {
        String url = System.getenv("DB_URL");
        String user = System.getenv("DB_USER");
        String password = System.getenv("DB_PASS");
    
        if (url == null || user == null || password == null) {
            throw new SQLException("Database connection environment variables (DB_URL, DB_USER, DB_PASS) are not set.");
        }
    
        connection = DriverManager.getConnection(url, user, password);
    }


    //initialize database and setup table 
    private static void initializeDatabase() {
        String createTableSQL = "CREATE TABLE IF NOT EXISTS orders (" +
                                 "order_id SERIAL PRIMARY KEY," +
                                 "user_id INT NOT NULL," +
                                 "product_id INT NOT NULL," +
                                 "quantity INT NOT NULL," +
                                 "order_date TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                                 "FOREIGN KEY(user_id) REFERENCES users(id)," +
                                 "FOREIGN KEY(product_id) REFERENCES products(id)" +
                                 ")";
        
        try (Statement statement = connection.createStatement()) {
            statement.execute(createTableSQL);
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
    

    //method to read config file
    public static Map<String, String> readConfigFile( String type) throws IOException {
        String configContent = new String(Files.readAllBytes(Paths.get(filename)), "UTF-8");
        String userServiceConfigContent = extractServiceConfig(configContent, type);
        return JSONParser(userServiceConfigContent);
    }
    public static void main(String[] args) throws IOException {

    String userServiceUrl = "http://userservice:14001/health";  // Replace with actual health check endpoint
    String productServiceUrl = "http://productservice:14010/health";  // Replace with actual health check endpoint

    while (!isServiceReady(userServiceUrl) || !isServiceReady(productServiceUrl)) {
        try {
            Thread.sleep(5000); // Wait for 5 seconds before trying again
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Thread interrupted", ie);
        }
    }


        //setup connection
        try {
            initializeConnection(); 
            initializeDatabase();   
        } catch (SQLException e) {
            System.out.println("Failed to initialize database connection.");
            e.printStackTrace();
            return;
        }
    
        // use "config.json" as the default config file name
        filename = "config.json";
        // if an argument is provided, use it as the configuration file name
        if (args.length > 0) {
            filename = args[0]; // get filename in same path
        }
    
        /// Retrieve port and IP from the config file
        Map<String, String> userServiceConfig = readConfigFile("OrderService");
        int port = Integer.parseInt(userServiceConfig.get("port"));
        String ip = userServiceConfig.get("ip");
    
        // Start the server
        HttpServer server = HttpServer.create(new InetSocketAddress(ip, port), 0);
        // endpoints
        HttpContext orderContext = server.createContext("/order");
        HttpContext productContext = server.createContext("/product");
        HttpContext userContext = server.createContext("/user");
        HttpContext userPurchasedContext = server.createContext("/user/purchased");
    
        // Set the same handler for all contexts
        orderContext.setHandler(OrderService::handleRequest);
        productContext.setHandler(OrderService::handleRequest); 
        userContext.setHandler(OrderService::handleRequest);
        userPurchasedContext.setHandler(OrderService::handleRequest);
    
        server.start();
        System.out.println("Server started on IP " + ip + ", and port " + port + ".");
    }
    
    // handler method for all HTTP requests
    private static void handleRequest(HttpExchange exchange) throws IOException {
        URI requestURI = exchange.getRequestURI();
        String path = requestURI.getPath();
        String[] segments = path.split("/");
        String response = "";
        int responseCode = 200;
    
        try {
            // for placing order
            if ("POST".equals(exchange.getRequestMethod()) && path.startsWith("/order")) {
                response = handleOrderRequest(exchange.getRequestBody());
            } 
            //  for retrieving user purchases
            else if ("GET".equals(exchange.getRequestMethod()) && path.startsWith("/user/purchased") && segments.length >= 4) {
                String userId = segments[3];
                if (!userExists(Integer.parseInt(userId))) {
                    responseCode = 404;
                    response = "User does not exist.";
                } else {
                    response = getUserPurchases(Integer.parseInt(userId));
                }
            } 
            
            else if (path.startsWith("/product") || path.startsWith("/user")) {
                response = forwardRequestToService(path, exchange);
            }
  
            else {
                response = "Unsupported endpoint.";
                responseCode = 404;
            }
        } catch (IOException e) {
            response = "Internal server error.";
            responseCode = 500;
        }
        exchange.sendResponseHeaders(responseCode, response.getBytes().length);
        OutputStream os = exchange.getResponseBody();
        os.write(response.getBytes());
        os.close();
    }
    
    
    // POST request handler to place Order
    private static String handleOrderRequest(InputStream requestBody) {
        try (Scanner scanner = new Scanner(requestBody, StandardCharsets.UTF_8)) {
            String body = scanner.useDelimiter("\\A").next();
            Map<String, String> data = JSONParser(body);
            String command = data.get("command");
            if ("place order".equals(command)) {
                return placeOrder(data);
            } else {
                return "Invalid command.";
            }
        } catch (Exception e) {
            return "Invalid";
        }
    }

    // type 1 = User, type 0 = Product
   // Forwarding request to the load balancer
private static String forwardRequestToService(String path, HttpExchange exchange) throws IOException {
    String targetUrl = "http://nginx" + path;
    System.out.println("Forwarding request to: " + targetUrl);

    HttpURLConnection connection = (HttpURLConnection) new URL(targetUrl).openConnection();
    String method = exchange.getRequestMethod();
    System.out.println("Received method: " + method);
    connection.setRequestMethod(method);

    String requestBodyStr = "";
    if ("POST".equals(method)) {
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");

        // Reading request body
        try (InputStream requestBody = exchange.getRequestBody();
             ByteArrayOutputStream result = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int length;
            while ((length = requestBody.read(buffer)) != -1) {
                result.write(buffer, 0, length);
            }
            requestBodyStr = result.toString(StandardCharsets.UTF_8.name());
            System.out.println("Request Body: " + requestBodyStr);
        }

        // Writing request body to the outgoing connection
        try (OutputStream os = connection.getOutputStream()) {
            os.write(requestBodyStr.getBytes(StandardCharsets.UTF_8));
        }
    }

    // Read and return the response
    int responseCode = connection.getResponseCode();
    InputStream responseStream = responseCode < HttpURLConnection.HTTP_BAD_REQUEST ? connection.getInputStream() : connection.getErrorStream();
    StringBuilder response = new StringBuilder();
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(responseStream))) {
        String line;
        while ((line = reader.readLine()) != null) {
            response.append(line);
        }
    } finally {
        connection.disconnect();
    }
    return response.toString();
}

    
    


   

    // get the amount purchased by user 
    private static String getUserPurchases(int userId) {
        String sql = "SELECT product_id, SUM(quantity) as total_quantity FROM Orders WHERE user_id = ? GROUP BY product_id";
        Map<Integer, Integer> purchases = new HashMap<>();
    
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setInt(1, userId);
            ResultSet rs = pstmt.executeQuery();
    
            while (rs.next()) {
                int productId = rs.getInt("product_id");
                int quantity = rs.getInt("total_quantity");
                purchases.put(productId, quantity);
            }
        } catch (SQLException e) {
            return new Gson().toJson(new HashMap<>()); // Return an empty JSON object in case of SQL exception.
        }
    
        return new Gson().toJson(purchases);
    }
    
    



    private static boolean userExists(int user_id) {
    String sql = "SELECT id FROM Users WHERE id = ?";
    try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
        pstmt.setInt(1, user_id);
        ResultSet rs = pstmt.executeQuery();
        return rs.next();
    } catch (SQLException e) {
        return false;
    }
}

private static boolean product_checker(int product_id, int requestedQuantity) {
    String updateSql = "UPDATE Products SET quantity = quantity - ? WHERE id = ? AND quantity >= ?";

    try (PreparedStatement updateStmt = connection.prepareStatement(updateSql)) {
        updateStmt.setInt(1, requestedQuantity);
        updateStmt.setInt(2, product_id);
        updateStmt.setInt(3, requestedQuantity);
        int affectedRows = updateStmt.executeUpdate();
        return affectedRows > 0;
    } catch (SQLException e) {
        return false;
    }
}


private static String placeOrder(Map<String, String> data) {
    int product_id = Integer.parseInt(data.get("product_id"));
    int user_id = Integer.parseInt(data.get("user_id"));
    int requestedQuantity = Integer.parseInt(data.get("quantity"));


    if (!userExists(user_id)) {
        return new Gson().toJson(Map.of("error", "User does not exist."));
    }

    if (!product_checker(product_id, requestedQuantity)) {
        return new Gson().toJson(Map.of("error", "Product amount not enough"));
    }

    String sql = "INSERT INTO Orders(user_id, product_id, quantity) VALUES(?,?,?)";
    try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
        pstmt.setInt(1, user_id);
        pstmt.setInt(2, product_id);
        pstmt.setInt(3, requestedQuantity);
        int affectedRows = pstmt.executeUpdate();

        if (affectedRows == 0) {
            return new Gson().toJson(Map.of("error", "Failed to insert order: No rows affected."));
        } else {
            return new Gson().toJson(Map.of("status", "Success"));
        }

    } catch (SQLException e) {
        return new Gson().toJson(Map.of("error", "Error inserting order: " + e.getMessage()));
    }
}



    // ---Helper---
    private static String sendGetRequest(String urlString) throws IOException {
        URL url = new URL(urlString);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
    
        int responseCode = connection.getResponseCode();
        if (responseCode != HttpURLConnection.HTTP_OK) {
            throw new IOException("Received HTTP " + responseCode + " from " + urlString);
        }
    
        StringBuilder response = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
        } finally {
            connection.disconnect();
        }
        return response.toString();
    }
    



    // method to parse JSON data from str
    private static Map<String, String> JSONParser(String json) {
        Map<String, String> data = new HashMap<>();
        json = json.trim().replaceAll("[{}\"]", "");
        String[] pairs = json.split(",");
        for (String pair : pairs) {
            String[] keyValue = pair.trim().split(":");
            if (keyValue.length == 2) {
                data.put(keyValue[0].trim(), keyValue[1].trim());
            }
        }
        return data;
    }

    // extracts the configuration for service
    private static String extractServiceConfig(String json, String serviceName) {
        String servicePattern = "\"" + serviceName + "\": {";
        int startIndex = json.indexOf(servicePattern);
        if (startIndex == -1) {
            return "{}"; // service not found, return empty JSON object
        }
        startIndex += servicePattern.length() - 1; // move to the opening brace

        int bracesCount = 1;
        int endIndex = startIndex;
        while (endIndex < json.length() && bracesCount > 0) {
            endIndex++;
            char ch = json.charAt(endIndex);
            if (ch == '{') {
                bracesCount++;
            } else if (ch == '}') {
                bracesCount--;
            }
        }
        return json.substring(startIndex, endIndex + 1); // include closing brace
    }
}
