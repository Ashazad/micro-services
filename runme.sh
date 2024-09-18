#!/bin/bash

LOGGING_API="./slf4j-simple-{version}.jar"
JDBC_DRIVER="./compiled/JarFiles/postgresql-42.7.2.jar"  
API_PATH="./slf4j-api-1.7.36.jar"
GSON_JAR="./compiled/JarFiles/gson-2.8.5.jar" 

compile_code() {
    echo "Compiling"

    javac -source 16 -target 16 -cp ".:$GSON_JAR" -d compiled/OrderService src/OrderService/OrderService.java
    javac -source 16 -target 16 -cp ".:$GSON_JAR" -d compiled/ProductService src/ProductService/ProductService.java
    javac -source 16 -target 16 -cp ".:$GSON_JAR" -d compiled/UserService src/UserService/UserService.java
}

start_user_service() {
    echo "Starting User service"
    java -cp "compiled/UserService:$JDBC_DRIVER:$API_PATH:$GSON_JAR" UserService
}

start_product_service() {
    echo "Starting Product service"
    java -cp "compiled/ProductService:$JDBC_DRIVER:$API_PATH:$GSON_JAR" ProductService
}

start_order_service() {
    echo "Starting Order service"
    java -cp "compiled/OrderService:$JDBC_DRIVER:$API_PATH:$GSON_JAR" OrderService
}

start_iscs_service() {
    echo "Starting ISCS service"
    python3 src/ISCS/iscs.py
}

start_workload_parser() {
    workload_file=$1
    python3 workload_parser.py "$workload_file"
}

case "$1" in
    -c)
        compile_code
        ;;
    -u)
        start_user_service
        ;;
    -p)
        start_product_service
        ;;
    -o)
        start_order_service
        ;;
    -i)
        start_iscs_service
        ;;
    -w)
        start_workload_parser "$2"
        ;;
    *)
        echo "Usage: $0 {-c|-u|-p|-o|-i|-w <workloadfile>}"
        exit 1
        ;;
esac
