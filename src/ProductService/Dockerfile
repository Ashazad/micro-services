# Dockerfile for ProductService
FROM openjdk:17-oracle
WORKDIR /app
COPY ./compiled/JarFiles/gson-2.8.5.jar /app/gson.jar
COPY ./compiled/JarFiles/postgresql-42.7.2.jar /app/postgresql.jar
COPY ./compiled/ProductService/ProductService.class /app
COPY ./config.json /app/config.json  
CMD ["java", "-cp", ".:gson.jar:postgresql.jar", "ProductService"]
#CMD ["tail", "-f", "/dev/null"]
