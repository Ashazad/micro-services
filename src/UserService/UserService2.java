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

public class UserService2 {

    private static Connection connection;

    // Initialize SQLite database connection
    private static void initializeConnection() throws SQLException {
        String url = "jdbc:sqlite:database.db"; 
        connection = DriverManager.getConnection(url);
    }

    private static void initializeDatabase() {
        String createTableSQL = "CREATE TABLE IF NOT EXISTS Users ("
                + "id INTEGER PRIMARY KEY, "
                + "username TEXT NOT NULL, "
                + "email TEXT, "
                + "password TEXT NOT NULL);"; 

        try (Statement statement = connection.createStatement()) {
            statement.execute(createTableSQL);
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
    

    public static void main(String[] args) throws IOException {

        //setup connection
        try {
            initializeConnection(); 
            initializeDatabase();   
        } catch (SQLException e) {
            System.out.println("Failed to initialize database connection.");
            e.printStackTrace();
            return;
        }

        // Default configuration filename
        String configFileName = "config.json";
        if (args.length > 0) {
            configFileName = args[0];
        }

        // Load configuration
        String configContent = new String(Files.readAllBytes(Paths.get(configFileName)), "UTF-8");
        String userServiceConfigContent = extractServiceConfig(configContent, "UserService");
        Map<String, String> userServiceConfig = JSONParser(userServiceConfigContent);

        // Start HTTP server
        int port = Integer.parseInt(userServiceConfig.get("port"));
        String ip = userServiceConfig.get("ip");
        HttpServer server = HttpServer.create(new InetSocketAddress(ip, port), 0);
        HttpContext context = server.createContext("/user");
        context.setHandler(UserService2::handleRequest);
        server.start();
        System.out.println("Server started on IP " + ip + ", and port " + port + ".");

    }



    private static void handleRequest(HttpExchange exchange) throws IOException {
        String requestMethod = exchange.getRequestMethod();
        String response = "";
        int responseCode = 200;

        switch (requestMethod) {
            case "GET":
                response = handleGetRequest(exchange.getRequestURI());
                break;
            case "POST":
                response = handlePostRequest(exchange.getRequestBody());
                break;
            default:
                response = "Unsupported method.";
                exchange.sendResponseHeaders(405, response.length());
        }

        exchange.sendResponseHeaders(responseCode, response.getBytes().length);
        OutputStream os = exchange.getResponseBody();
        os.write(response.getBytes());
        os.close();
    }

    private static String handleGetRequest(URI requestURI) {
        String path = requestURI.getPath();
        String[] pathParts = path.split("/");

        if (pathParts.length == 3 && "user".equals(pathParts[1])) {
            try {
                int userId = Integer.parseInt(pathParts[2]);
                return getUser(userId);
            } catch (NumberFormatException e) {
                return "Invalid user ID.";
            }
        }
        return "Invalid request.";
    }

    private static String getUser(int userId) {
        String query = "SELECT * FROM users WHERE id = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(query)) {
            pstmt.setInt(1, userId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    String username = rs.getString("username");
                    String email = rs.getString("email");
                    String passwordHash = rs.getString("password");
                    return String.format("{\"id\": %d, \"username\": \"%s\", \"email\": \"%s\", \"password\": \"%s\"}", userId, username, email, passwordHash);
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return "User not found";
    }

    private static String handlePostRequest(InputStream requestBody) {
        try (Scanner scanner = new Scanner(requestBody, "UTF-8")) {
            String body = scanner.useDelimiter("\\A").next();
            Map<String, String> data = JSONParser(body);
            String command = data.get("command");

            switch (command) {
                case "create":
                    return createUser(data);
                case "update":
                    return updateUser(data);
                case "delete":
                    return deleteUser(data);
                default:
                    return "Invalid command.";
            }
        } catch (Exception e) {
            return "Invalid request.";
        }
    }

    private static String createUser(Map<String, String> data) {
        // Get user ID from the data map
        int id = Integer.parseInt(data.get("id"));
        String username = data.get("username");
        String email = data.get("email");
        String password = data.get("password");
        String passwordHash = hashPassword(password);
    
        String sql = "INSERT INTO users(id, username, email, password) VALUES(?, ?, ?, ?)";
    
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setInt(1, id);
            pstmt.setString(2, username);
            pstmt.setString(3, email);
            pstmt.setString(4, passwordHash);
            pstmt.executeUpdate();
            return String.format("{\"id\": %d, \"username\": \"%s\", \"email\": \"%s\", \"password\": \"%s\"}", id, username, email, passwordHash);
        } catch (SQLException e) {
            System.out.println(e.getMessage());
            return "User creation failed";
        }
    }
    

    private static String updateUser(Map<String, String> data) {
        int id = Integer.parseInt(data.get("id"));
        String username = data.getOrDefault("username", "");
        String email = data.getOrDefault("email", "");
        String password = data.getOrDefault("password", "");
        String passwordHash = hashPassword(password);
    
        String sql = "UPDATE users SET username = ?, email = ?, password = ? WHERE id = ?";
    
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, username);
            pstmt.setString(2, email);
            pstmt.setString(3, passwordHash);
            pstmt.setInt(4, id);
            int affectedRows = pstmt.executeUpdate();
            if (affectedRows > 0) {
                return String.format("{\"id\": %d, \"username\": \"%s\", \"email\": \"%s\", \"password\": \"%s\"}", id, username, email, passwordHash);
            } else {
                return "User not found or no update needed";
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return "User update failed";
        }
    }
    
    private static String deleteUser(Map<String, String> data) {
        int id = Integer.parseInt(data.get("id"));
    
        String sql = "DELETE FROM users WHERE id = ?";
    
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setInt(1, id);
            int affectedRows = pstmt.executeUpdate();
            if (affectedRows > 0) {
                return "User deleted successfully";
            } else {
                return "User not found";
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return "User deletion failed";
        }
    }
    

    private static String hashPassword(String password) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(password.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Failed to hash password", e);
        }
    }

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

    private static String extractServiceConfig(String json, String serviceName) {
        String servicePattern = "\"" + serviceName + "\": {";
        int startIndex = json.indexOf(servicePattern);
        if (startIndex == -1) {
            return "{}";
        }
        startIndex += servicePattern.length() - 1;
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
        return json.substring(startIndex, endIndex + 1);
    }
}
