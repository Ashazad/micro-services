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
import com.google.gson.Gson;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


public class UserService {

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
        String createTableSQL = "CREATE TABLE IF NOT EXISTS users ("
                + "id SERIAL PRIMARY KEY, "
                + "username VARCHAR(255) NOT NULL, "
                + "email VARCHAR(255), "
                + "password VARCHAR(255) NOT NULL);";
    
        try (Statement statement = connection.createStatement()) {
            statement.execute(createTableSQL);
        } catch (SQLException e) {
        }
    }
    public static void main(String[] args) throws IOException {

        while (!isDatabaseReady()) {
            try {
                Thread.sleep(5000); 
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
        String configContent = new String(Files.readAllBytes(Paths.get(configFileName)), StandardCharsets.UTF_8);
        String userServiceConfigContent = extractServiceConfig(configContent, "UserService");
        Map<String, String> userServiceConfig = JSONParser(userServiceConfigContent);
        int port = Integer.parseInt(System.getenv().getOrDefault("USER_SERVICE_PORT", "14001"));

        String ip = userServiceConfig.get("ip");

        // Start server
        HttpServer server = HttpServer.create(new InetSocketAddress(ip, port), 0);

        // Register handlers
        HttpContext context = server.createContext("/user"); // Endpoint /user
        context.setHandler(UserService::handleRequest);

        // Register health check handler
        HttpContext healthContext = server.createContext("/health");
        healthContext.setHandler(UserService::handleHealthCheckRequest);

        server.start();
        System.out.println("UserService started on IP " + ip + ", and port " + port + ".");
        
    }


    // handler method for all HTTP requests
    private static void handleRequest(HttpExchange exchange) {
        try {
            String requestMethod = exchange.getRequestMethod();
            String response = "";
            int responseCode = 200;
            InputStream requestBodyStream = exchange.getRequestBody();
            String requestBody = new BufferedReader(new InputStreamReader(requestBodyStream))
                                    .lines().collect(Collectors.joining("\n"));
    
            switch (requestMethod) {
                case "GET":
                    response = handleGetRequest(exchange.getRequestURI());
                    if (response.equals("user not found")) {
                        responseCode = 404;
                    }
                    break;
                case "POST":
                    response = handlePostRequest(requestBody);
                    if (response.equals("User already exists")) {
                        responseCode = 409;
                    } else if (response.equals("Cannot delete")) {
                        responseCode = 401;
                    } else if (response.equals("Missing fields")) {
                        responseCode = 400;
                    }
                    break;
                default:
                    response = "Unsupported method.";
                    responseCode = 405;
            }
    
            exchange.sendResponseHeaders(responseCode, response.getBytes().length);
            OutputStream os = exchange.getResponseBody();
            os.write(response.getBytes());
            os.close();
        } catch (IOException e) {
        }
    }
    

    private static String handleGetRequest(URI requestURI) {
        String path = requestURI.getPath();
        //.println("Received request URI: " + path);
        String[] pathParts = path.split("/");
        
    
        // should be 3 parts for basic GET, localhost / user / USERID
        if (pathParts.length == 3 && "user".equals(pathParts[1])) {
            try {
                int userId = Integer.parseInt(pathParts[2]);
                String sql = "SELECT username, email, password FROM users WHERE id = ?";
                try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                    pstmt.setInt(1, userId);
                    try (ResultSet rs = pstmt.executeQuery()) {
                        if (rs.next()) {
                            return new Gson().toJson(Map.of(
                                "id", userId,
                                "username", rs.getString("username"),
                                "email", rs.getString("email"),
                                "password", rs.getString("password")
                            ));
                        }
                        return "user not found";
                    }
                }
            } catch (NumberFormatException e) {
                return new Gson().toJson(Map.of("error", "Invalid user ID."));
            } catch (SQLException e) {
                //System.out.println(e.getMessage());
                return new Gson().toJson(Map.of("error", "Database error."));
            }
        }
        return new Gson().toJson(Map.of("error", "Invalid request 1."));
    }
    

// POST request handler to create, update, and delete USER
private static String handlePostRequest(String requestBody) {
    Map<String, String> data;
    try {
        data = JSONParser(requestBody);
    } catch (Exception e) {
        return new Gson().toJson(Map.of("error", "Error parsing JSON body: " + e.getMessage()));
    }

    // Handle the command from the request
    String command = data.get("command");
    if (command == null) {
        return new Gson().toJson(Map.of("error", "Command not specified."));
    }

    switch (command) {
        case "create":
            return createUser(data);
        case "update":
            return updateUser(data);
        case "delete":
            return deleteUser(data);
        default:
            return new Gson().toJson(Map.of("error", "Invalid command."));
    }
}

private static int checkUserExists(int id) {
    String sql = "SELECT COUNT(*) FROM users WHERE id = ?";
    try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
        pstmt.setInt(1, id);
        ResultSet rs = pstmt.executeQuery();
        if (rs.next() && rs.getInt(1) > 0) {
            // User exists
            return 409; // Conflict status code
        } else {
            return 404; 
        }
    } catch (SQLException e) {
        return 500; 
    }
}


    // actual methods to manipulate user data
    // method to create user
    private static String createUser(Map<String, String> data) {
        String username = data.get("username");
        String email = data.get("email");
        String password = data.get("password");
        String userIdString = data.get("id");
        int id = Integer.parseInt(userIdString);
    
        if ("placeholder".equals(username) || "placeholder".equals(email) || "placeholder".equals(password) || "placeholder".equals(userIdString)) {
            return "Error Placeholder";
        }

        if (username == null || username.isEmpty() ||
        email == null || email.isEmpty() ||
        password == null || password.isEmpty() ||
        userIdString == null || userIdString.isEmpty() || (!email.contains("@")))
        {
        return "Missing fields";
    }


        int userExists = checkUserExists(id);
        if (userExists == 409) {
            return "User already exists";
        }
    
        // Hash the password
        String passwordHash = hashPassword(password);
    
        String sql = "INSERT INTO users(id, username, email, password) VALUES(?, ?, ?, ?)";
    
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setInt(1, id);
            pstmt.setString(2, username);
            pstmt.setString(3, email);
            pstmt.setString(4, passwordHash);
            pstmt.executeUpdate();
            return new Gson().toJson(Map.of("id", id, "username", username, "email", email, "password", passwordHash));
        } catch (SQLException e) {
            //System.out.println(e.getMessage());
            return new Gson().toJson(Map.of("error", "Database error"));
        }
    }
    

    // method to update user
   private static String updateUser(Map<String, String> data) {
    int id = Integer.parseInt(data.get("id"));
    StringBuilder sql = new StringBuilder("UPDATE users SET ");
    List<Object> params = new ArrayList<>();

    if (data.containsKey("username")) {
        sql.append("username = ?, ");
        params.add(data.get("username"));
    }
    if (data.containsKey("email")) {
        sql.append("email = ?, ");
        params.add(data.get("email"));
    }
    if (data.containsKey("password")) {
        sql.append("password = ?, ");
        params.add(hashPassword(data.get("password")));
    }

    if (!params.isEmpty()) {
        sql.delete(sql.length() - 2, sql.length());
        sql.append(" WHERE id = ?");
        params.add(id);
    } else {
        return new Gson().toJson(Map.of("error", "No fields to update"));
    }

    try (PreparedStatement pstmt = connection.prepareStatement(sql.toString())) {
        for (int i = 0; i < params.size(); i++) {
            pstmt.setObject(i + 1, params.get(i));
        }
        int affected = pstmt.executeUpdate();

        if (affected == 0) {
            return new Gson().toJson(Map.of("error", "User not found"));
        }

        // Create a response map with updated data
        Map<String, Object> response = new HashMap<>();
        response.put("id", id);
        data.forEach(response::put);
        return new Gson().toJson(response);
    } catch (SQLException e) {
        return new Gson().toJson(Map.of("error", "Database error"));
    }
}
    

    // method to delete user only if all details are matched
    private static String deleteUser(Map<String, String> data) {
        int id = Integer.parseInt(data.get("id"));
        String username = data.get("username");
        String email = data.get("email");
        String password = data.get("password");
        String passwordHash = hashPassword(password);
    
        String sql = "DELETE FROM users WHERE id = ? AND username = ? AND email = ? AND password = ?";
    
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setInt(1, id);
            pstmt.setString(2, username);
            pstmt.setString(3, email);
            pstmt.setString(4, passwordHash);
            int affected = pstmt.executeUpdate();
    
            if (affected > 0) {
                return new Gson().toJson(Map.of("id", id, "username", username, "email", email, "password", passwordHash));
            } else {
                return new Gson().toJson(Map.of("error", "User not found or deletion failed"));
            }
        } catch (SQLException e) {
            return new Gson().toJson(Map.of("error", "Database error"));
        }
    }
    

    // ---Helper---
    // SHA-256 Hasher
    private static String hashPassword(String password) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(password.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Failed to hash password", e);
        }
    }

    // hash helper
    private static String bytesToHex(byte[] hash) {
        StringBuilder hexString = new StringBuilder();
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }

    // method to parse JSON data from str
    private static Map<String, String> JSONParser(String json) throws IllegalArgumentException {
        Map<String, String> data = new HashMap<>();
        json = json.trim().replaceAll("[{}\"]", "");
        String[] pairs = json.split(",");
        for (String pair : pairs) {
            String[] keyValue = pair.trim().split(":");
            if (keyValue.length == 2) {
                if ("placeholder".equals(keyValue[1].trim())) {
                    throw new IllegalArgumentException("Placeholder value detected.");
                }
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
