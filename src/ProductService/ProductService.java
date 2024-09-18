import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.sql.Statement;
import java.util.UUID;
import java.util.Map;
import java.util.HashMap;
import java.util.Scanner;
import com.google.gson.Gson;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ProductService {

    private static Connection connection;


    private static boolean isDatabaseReady() {
        try (Connection testConnection = DriverManager.getConnection(System.getenv("DB_URL"), System.getenv("DB_USER"), System.getenv("DB_PASS"))) {
            if (testConnection != null) {
                return true;
            }
        } catch (SQLException e) {
            System.out.println("Waiting for database to be ready...");
        }
        return false;
    }
    private static void handleHealthCheckRequest(HttpExchange exchange) throws IOException {
        String response = "OK";
        exchange.sendResponseHeaders(200, response.getBytes().length);
        OutputStream os = exchange.getResponseBody();
        os.write(response.getBytes());
        os.close();
    }
    

    // Initialize SQLite database connection
    private static void initializeConnection() throws SQLException {
        String url = System.getenv("DB_URL");
        String user = System.getenv("DB_USER");
        String password = System.getenv("DB_PASS");
    
        if (url == null || user == null || password == null) {
            throw new SQLException("Database connection environment variables (DB_URL, DB_USER, DB_PASS) are not set.");
        }
    
        connection = DriverManager.getConnection(url, user, password);
    }
    private static void initializeDatabase() {
        String createTableSQL = "CREATE TABLE IF NOT EXISTS products (\n"
        + "	id integer PRIMARY KEY,\n"
        + "	name text NOT NULL,\n"
        + "	description text NOT NULL,\n"
        + "	price real NOT NULL,\n"
        + "	quantity integer NOT NULL\n"
        + ");";
    
        try (Statement statement = connection.createStatement()) {
            statement.execute(createTableSQL);
        } catch (SQLException e) {
        }
    }


    
    public static void main(String[] args) throws IOException {

        while (!isDatabaseReady()) {
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
            return;
        }

        // use "config.json" as the default config file name
        String configFileName = "config.json";
        // if an argument is provided, use it as the configuration file name
        if (args.length > 0) {
            configFileName = args[0]; // get filename in same path
        }

        // parse JSON to Map in helper
        String configContent = new String(Files.readAllBytes(Paths.get(configFileName)), "UTF-8");
        String productServiceConfigContent = extractServiceConfig(configContent, "ProductService");
        Map<String, String> productServiceConfig = JSONParser(productServiceConfigContent);
        // get server port and IP from config.json
        int port = Integer.parseInt(System.getenv().getOrDefault("PRODUCT_SERVICE_PORT", "14010"));
        String ip = productServiceConfig.get("ip");

        // start server
        HttpServer server = HttpServer.create(new InetSocketAddress(ip, port), 0);
        HttpContext context = server.createContext("/product");  // endpoint /product
        context.setHandler(ProductService::handleRequest);

        HttpContext healthContext = server.createContext("/health");
        healthContext.setHandler(ProductService::handleHealthCheckRequest);
        server.start();
        System.out.println("Server started on IP " + ip + ", and port " + port + ".");
    }

    // handler method for all HTTP requests
    private static void handleRequest(HttpExchange exchange) throws IOException {
        String requestMethod = exchange.getRequestMethod();
        String response = "";
        int responseCode = 200;
    
        try {
            switch (requestMethod) {
                case "GET":
                    response = handleGetRequest(exchange.getRequestURI());
                    if (response.charAt(0) == '{') {
                        responseCode = 200;
                    } else if (response.equals("Product not found") || response.equals("Invalid product ID")) {
                        responseCode = 404;
                    } else {
                        responseCode = 400; // Bad Request
                    }
                    break;
                case "POST":
                    response = handlePostRequest(exchange.getRequestBody());
                    if (response.equals("Product created") || response.equals("Product updated")) {
                        responseCode = 200;
                    } else if (response.equals("Product already exists")) {
                        responseCode = 409; // Conflict
                    } else if (response.equals("Product deletion failed") || response.equals("Product not found")) {
                        responseCode = 404; // Not Found
                    } else {
                        responseCode = 400; // Bad Request
                    }
                    break;
                default:
                    response = "Unsupported method";
                    responseCode = 405; // Method Not Allowed
            }
    
            exchange.sendResponseHeaders(responseCode, response.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        } catch (Exception e) {
        } finally {
            exchange.close();
        }
    }
    

    // GET request handler
    private static String handleGetRequest(URI requestURI) {
        String path = requestURI.getPath();
        String[] pathParts = path.split("/");
        if (pathParts.length == 3 && "product".equals(pathParts[1])) {
            try {
                int productId = Integer.parseInt(pathParts[2]);
                return getProduct(productId);
            } catch (NumberFormatException e) {
                return "Invalid product ID";
            }
        }
        return "Invalid request";
    }

    private static String getProduct(int productId) {
        String query = "SELECT * FROM Products WHERE id = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(query)) {
            pstmt.setInt(1, productId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    String name = rs.getString("name");
                    String description = rs.getString("description");
                    double price = rs.getDouble("price");
                    int quantity = rs.getInt("quantity");
                    return String.format("{\"id\": %d, \"name\": \"%s\", \"description\": \"%s\", \"price\": %.2f, \"quantity\": %d}",
                                         productId, name, description, price, quantity);
                }
            }
        } catch (SQLException e) {
        }
        return "Product not found";
    }
    


    // POST request handler to create, update and delete PRODUCT
    private static String handlePostRequest(InputStream requestBody) {
        try (Scanner scanner = new Scanner(requestBody, StandardCharsets.UTF_8.name())) {
            // read the entire input stream into a single string
            String body = scanner.useDelimiter("\\A").next();
            Map<String, String> data = JSONParser(body);
            String command = data.get("command");
            switch (command) {
                case "create":
                    return createProduct(data);
                case "update":
                    return updateProduct(data);
                case "delete":
                    return deleteProduct(data);
                default:
                    return "Invalid command.";
            }
        } catch (Exception e) {
            return "Invalid request.";
        }
    }

    private static int checkProductExists(int id) {
        String sql = "SELECT COUNT(*) FROM products WHERE id = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setInt(1, id);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next() && rs.getInt(1) > 0) {
                return 409;
            } else {
                return 404; 
            }
        } catch (SQLException e) {
            return 500; 
        }
    }


    // actual methods to manipulate product data
    // method to create product
    private static String createProduct(Map<String, String> data) {
        int id = Integer.parseInt(data.get("id"));
        String name = data.get("name");
        String description = data.get("description");
        String idString = data.get("id");
        String priceString = data.get("price");
        String quantityString = data.get("quantity");
        double quantity = Double.parseDouble(quantityString);
        double price = Double.parseDouble(priceString);

        if (idString == null || idString.isEmpty() ||
        name == null || name.isEmpty() ||
        description == null || description.isEmpty() ||
        priceString == null || priceString.isEmpty() ||
        quantityString == null || quantityString.isEmpty()) {
        return "Missing fields";
    }
    
        if (price < 0 || quantity < 1) {
            return  "Missing fields";
        }

        int exists = checkProductExists(id);
        if (exists == 409) {
            return "Product already exists";
        }
    
        String insertSQL = "INSERT INTO Products (id, name, description, price, quantity) VALUES (?, ?, ?, ?, ?)";
    
        try (PreparedStatement pstmt = connection.prepareStatement(insertSQL)) {
            pstmt.setInt(1, id);
            pstmt.setString(2, name);
            pstmt.setString(3, description);
            pstmt.setDouble(4, price);
            pstmt.setInt(5, (int)quantity);
            int affectedRows = pstmt.executeUpdate();
    
            if (affectedRows > 0) {
                return String.format("{\"id\": %d, \"name\": \"%s\", \"description\": \"%s\", \"price\": %.2f, \"quantity\": %d}",
                                     id, name, description, price, quantity);
            } else {
                return "Product creation failed";
            }
        } catch (SQLException e) {
            return "Database error: " + e.getMessage();
        }
    }

    private static String updateProduct(Map<String, String> data) {
        int id = Integer.parseInt(data.get("id"));
        String name = data.get("name");
        String description = data.get("description");
        double price = Double.parseDouble(data.get("price"));
        int quantity = Integer.parseInt(data.get("quantity"));
    
        String updateSQL = "UPDATE Products SET name = ?, description = ?, price = ?, quantity = ? WHERE id = ?";
    
        try (PreparedStatement pstmt = connection.prepareStatement(updateSQL)) {
            pstmt.setString(1, name);
            pstmt.setString(2, description);
            pstmt.setDouble(3, price);
            pstmt.setInt(4, quantity);
            pstmt.setInt(5, id);
            int affectedRows = pstmt.executeUpdate();
    
            if (affectedRows > 0) {
                return String.format("{\"id\": %d, \"name\": \"%s\", \"description\": \"%s\", \"price\": %.2f, \"quantity\": %d}",
                                     id, name, description, price, quantity);
            }
            return "Product update failed";
        } catch (SQLException e) {
            return "Database error";
        }
    }

    private static String deleteProduct(Map<String, String> data) {
        int id = Integer.parseInt(data.get("id"));
    
        String deleteSQL = "DELETE FROM Products WHERE id = ?";
    
        try (PreparedStatement pstmt = connection.prepareStatement(deleteSQL)) {
            pstmt.setInt(1, id);
            int affectedRows = pstmt.executeUpdate();
    
            if (affectedRows > 0) {
                return "Product deleted successfully";
            }
            return "Product deletion failed";
        } catch (SQLException e) {
            return "Database error";
        }
    }

    // ---Helper---
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

